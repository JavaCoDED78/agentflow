# ADR-024. Object Storage: consistency, security and lifecycle

- **Status:** Accepted
- **Date:** 2026-11-27
- **Task:** S02-20 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none. Extends [`ADR-013`](ADR-013-s3-object-storage-garage.md), which
  fixed Garage as the local S3-compatible backend; this record fixes how the backend
  participates in consistency, security and lifecycle decisions so that a later
  adapter (`S07-06C`…`S07-06H`) is a conformance implementation, not a re-interpretation.
- **Superseded by:** none

## Context

The project stores two kinds of large payload outside PostgreSQL:

- **document originals** (`knowledgebase`): the uploaded file behind a `documents`
  row — metadata (`object_key`, `sha256`, `size_bytes`, `mime_type`,
  `pipeline_version`) lives in PostgreSQL, the bytes live in Garage;
- **workflow large-payload checkpoints** (`workflowexecution`): a step output
  recorded in `workflow_step_runs.output_ref` (ref + checksum + size +
  `output_schema_version`) — metadata in PostgreSQL, the bytes in Garage.

The provider-neutral contract (`docs/architecture/object-storage.md`, `S02-19`)
defines what `storage` may do (five operations, the value types, stream
ownership, idempotency, error taxonomy). It deliberately does **not** define the
lifecycle, consistency or security policy — those are the decisions this ADR
fixes. The task requires a single answer for four scenarios (DB failure after
object upload, Garage outage, duplicate upload, delete retry) plus credential
separation, key-convention reconciliation and retention.

The forces on the decision:

- PostgreSQL is the source of truth for metadata and ownership (`ADR-002`);
  Kafka transports, Redis caches, PostgreSQL decides. Losing Garage must never
  lose a run or a document (`ADR-002`, `ADR-003`).
- Tenant isolation: the tenant comes from the verified security context, never
  from a request body or a Kafka header (`ADR-007`). `storage` never derives or
  checks tenant scope — the owning module does, against its PostgreSQL row,
  before the port is called.
- At-least-once + idempotency (`ADR-003`): any upload/delete flow may be retried
  or replayed, so object operations must be safe to repeat.
- The object store is an external, independently failing system — there is no
  transactional link between PostgreSQL and Garage (`ADR-004` makes the same
  point for Kafka). Promises of exactly-once or atomic two-system commit are
  forbidden.
- Documents and workflow artifacts are untrusted content (invariant 7): they may
  contain prompt-injection text, and they must never be stored, logged, cached or
  rendered as instructions.

## Decision

**1. Roles — PostgreSQL owns metadata and ownership, Garage owns only bytes.**

`storage` moves bytes and reports what happened; it never owns a row. The
`documents` row (`knowledgebase`) and the `workflow_step_runs.output_ref` row
(`workflowexecution`) carry the reference, checksum and size; Garage holds the
payload. The object store is **never** transactionally linked to PostgreSQL —
no 2PC, no shared transaction, no atomic commit across the two stores. Each
side commits on its own, in its own transaction, and the gap between them is
handled by the policy below.

**2. Upload flow — object first, then the metadata row, never the reverse.**

The consuming module (e.g. `knowledgebase`'s upload path) generates the key,
uploads the bytes to Garage (with a declared `sha256` and `contentLength`),
verifies the stored object (`head` / checksum), and only then writes the
metadata row **in the same transaction as** any outbox record. If the object
upload fails, the metadata row is never created — there is nothing to point at
and no outbox record to replay. A crash between a completed upload and the
metadata-row commit leaves an orphan object, which is handled by the
compensation policy below; it never leaves a metadata row pointing at a missing
object, and it never mutates a row that has not been committed.

Upload is idempotent in identity: the key is server-generated from
server-issued identifiers, so a retried upload of the same document reuses the
same key and replaces the same object (same reference, one logical object).
`storage`'s `put` is a deterministic replace; the contract's idempotency rules
(`docs/architecture/object-storage.md` §4) apply.

**3. Delete flow — the object is removed before the metadata transitions to
terminal.**

The document lifecycle (`docs/architecture/document-lifecycle.md`) drives
deletion two-phase: `… → DELETE_REQUESTED` (drain the lease of a `PROCESSING`
document) → object + chunks removed → `DELETED` (terminal). The physical object
removal happens inside the `DELETE_REQUESTED → DELETED` step. The delete is
**idempotent** (`DELETED` is a success even for an already-deleted object —
`DeleteResult.ALREADY_ABSENT`), so a retry, a redrive or a crash-then-retry
cannot fail the lifecycle. The metadata row is removed/tombstoned only after the
object deletion succeeds — never before, so a surviving row always points at a
present object.

**4. No public buckets, no permanent public URLs.**

No bucket is ever public; no object is addressable by a permanent public URL.
An object is addressed only through the app: the owning module checks access
against its PostgreSQL metadata row (`(tenantId, resourceId)`), then reads or
streams via the port. Short-lived, expiring signed URLs may be used later for a
direct-download path (e.g. an authenticated download endpoint that must not
stream bytes through the API) — if a signed-URL path is ever added, it must be
time-bounded, tenant-scoped in the key convention and never the default.

**5. Credentials — provisioning/admin and runtime are separate.**

- *Runtime credential.* The app uses a single least-privilege S3 key granted
  read/write/head/list/delete on the configured bucket only (Garage grants at
  the key level; a key is granted on buckets). The runtime key is never used to
  create buckets, keys or metadata.
- *Runtime settings (concrete, `S07-06G`).* The runtime credential is injected as
  `AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY` / `AGENTFLOW_OBJECT_STORAGE_SECRET_KEY` — never from Git, the image, the application config file, logs, metrics, traces, prompts or responses. The typed `agentflow.storage.*` group carries no credential field (a structural check in the `S07-06A` suite), so a secret can never be bound into config or dumped by a properties export. No credential ever reaches object metadata, an error message or an exception `toString()` (the contract boundary is message-safe — `error_path_never_echoes_the_runtime_credential`), and the runtime adapter never issues an administrative SDK operation (the source scan asserts every `s3Client.<method>(` is one of the five contract operations). Public access is not configurable — there is no public-access knob in `AgentFlowProperties.Storage` at all (an unknown `agentflow.storage.*` key, such as a stale `public-access=public`, is ignored and fails closed); an operator never sees a public-access setting to enable. Tenant authorization is deliberately not implemented by the adapter — the owning module authorizes by PostgreSQL ownership record before the port is called (Sprint 10 acceptance).
- *Provisioning/admin credential.* Bucket and key creation, rotation and other
  administrative operations use a separate root/admin credential (or the `garage`
  CLI via `docker exec`, per `ADR-013`) — never the app's runtime key, never
  the app's env/config. The admin path is operator-driven, out of the app
  process, and is not part of the runtime data path.
- *Injection.* Both come from the environment / a Secret reference — never from
  Git, the image, the application config file, logs, metrics, traces, prompts or
  responses. Locally: `infra/compose/docker-compose.yml` + the root `.env`
  (`minio.access-key` / `minio.secret-key` today, renamed to object-storage
  naming in a cleanup task), the RPC secret via `GARAGE_RPC_SECRET`. In
  production: a Kubernetes Secret, mounted read-only, with the runtime and admin
  credentials as distinct material.
- *No secrets in the contract, logs, prompts or telemetry.* Credentials never
  travel through the `ObjectStoragePort` contract, the error taxonomy, the
  metadata rows or any log line.

**6. Tenant-scoped key convention.**

The object key is always server-generated from server-issued identifiers —
never from the client's filename, body, query parameter or a Kafka header
(`ADR-007`). The key prefix is the tenant boundary:

| Kind | Key convention |
|---|---|
| Document original | `documents/<tenantId>/<documentId>[.<ext>]` (`S02-19`; the extension is a content-type hint from a closed whitelist, never a path segment) |
| Workflow large-payload checkpoint | `checkpoints/<tenantId>/<runId>/<stepRunId>[.<ext>]` (`S07-07` must implement exactly this) |

`storage` treats the key as an opaque, structurally-validated string (blank,
oversized, control characters, `..` segments rejected) and never derives or
validates tenant scope from it; the owning module derives the tenant from the
verified security context, generates the key, and rejects any client-supplied
key. A client-supplied key is a contract violation.

**7. Checksum verification.**

Every `put` verifies a declared `contentLength` and `sha256` against the actual
bytes (`INVALID_REQUEST` on mismatch, never a silent acceptance) and returns a
reference carrying the stored size and the SHA-256 of the stored bytes. A
consumer re-verifies the checksum when reading (metadata `head` + the streamed
hash) before trusting the content; a mismatch is a typed failure, never a silent
acceptance. The checksum is persisted in the metadata row (`documents.sha256` /
`workflow_step_runs.output_ref`) and is the single verification anchor — the
storage layer does not re-derive it from the stored bytes on every read.

**8. Retention — the consumers own the lifecycle; the store does not delete on a
schedule.**

No Garage bucket lifecycle rule, TTL or automatic garbage collection is used in
the MVP. Retention is owned by the consumer's state machine:

- *Document originals* — retained until the document reaches terminal `DELETED`
  (the object and the chunks are removed together in that transition) or is
  `FAILED`/`DELETE_REQUESTED` per the lifecycle. After a terminal state, the
  object is removed as part of the terminal transition (or by the orphan sweep
  below).
- *Workflow checkpoints* — retained until the run reaches a terminal state and
  the checkpoint is no longer needed for recovery; cleanup is the
  `workflowexecution`-owned fix-up step (`S07-07`), which deletes the object
  and clears the `output_ref`.

**9. Failure behaviour and compensation.**

| Scenario | Policy |
|---|---|
| **DB failure after object upload** (object stored, metadata row not committed) | The upload is retried/failed — no row, no outbox. The orphan object is either (a) compensated synchronously by the upload path (best-effort `delete` of the object; the key is server-generated and deterministic, so a retry cannot create a second logical object), or (b) left and reconciled by the orphan sweep (below). The caller reports the failure; the document is not created. |
| **Garage outage (write)** | `put` fails `UNAVAILABLE` / `TIMEOUT` (retryable) or a deterministic rejection (not retryable). The upload fails; the metadata row is never written (rule 2). `get` / `head` fail `UNAVAILABLE` / `TIMEOUT`; a missing object is `Optional.empty()` / typed `NOT_FOUND`. The caller retries bounded with backoff/jitter; a hard outage surfaces as a typed error, never a silent success. |
| **Duplicate upload / replay** | `put` on the same key is a deterministic replace: identical content → the same reference (one logical object); different content → the new content replaces the old (the reference's `size`/`sha256` change). Replay of an identical upload is a no-op in identity. |
| **Delete retry / redrive** | `delete` is idempotent: `DELETED` on first delete, `ALREADY_ABSENT` on any subsequent delete (or after an external delete). Either outcome is a success; the metadata row removal proceeds. |
| **Orphan object** (object without a metadata row — e.g. failed commit, killed worker) | Reconciled, never silently forgotten: a periodic sweep (`list` over the tenant-scoped keys + `head`) reports objects with no metadata row and metadata rows with no object. The sweep never deletes blindly — it logs, reports and lets an operator decide; deletion of a confirmed orphan is a documented, reviewable action. `list` is used for reconciliation only — never for authorization. |
| **Metadata row without object** (crash between object delete and metadata removal) | The metadata row is authoritative for ownership. If the object is gone and the row remains, the row is marked for cleanup / reconciliation; a `get`/`head` on the key returns `NOT_FOUND` / empty and the consumer reports the loss honestly (the document/step is not "healthy"). |
| **Garage outage at delete time** | The delete fails `UNAVAILABLE` / `TIMEOUT`; the document stays in `DELETE_REQUESTED` (the lease is already released) and is retried. The terminal `DELETED` state is never entered without a successful object delete. |

**10. Retries and classification.**

`storage` classifies and reports; bounded backoff/jitter is the caller's
policy (the contract test suite `S07-06B` asserts classification, not sleeps).
In the landed adapter, the retry loop itself lives in the bounded wired SDK client —
`StandardRetryStrategy` with `maxAttempts = agentflow.storage.max-retry-attempts + 1` (default 3 → at
most 4 attempts) and bounded connect/socket timeouts — so the adapter issues exactly one request per
operation and never retries or sleeps internally. The adapter classifies every provider failure
through one shared `mapFailure` mapper: a deterministic caller-side rejection (verification failure,
model-level `400`) → `INVALID_REQUEST`; a typed miss → `NOT_FOUND`; the SDK's own per-attempt / total-call
timeout → `TIMEOUT`; a throttling classification or `429`/`5xx` → `UNAVAILABLE`; a `401`/`403` → `ACCESS_DENIED`;
a `408`/`504` → `TIMEOUT`; any other structural `4xx` → `PERMANENT_FAILURE`; a transport-level fault →
`UNAVAILABLE` (or `TIMEOUT` by cause); anything else → `PERMANENT_FAILURE`. Retriable codes: `UNAVAILABLE` / `TIMEOUT` (bounded, jittered). Not retryable:
`INVALID_KEY` / `INVALID_REQUEST` / `NOT_FOUND` / `ACCESS_DENIED` /
`PERMANENT_FAILURE`. A storage outage never becomes a run failure — it is a
typed infra failure the caller classifies and retries within a bounded budget
(invariant 12).

When the provider supplies a request/correlation id (the S3 `x-amzn-request-id`), the adapter
preserves it on the contract exception (`ObjectStorageException.requestId()`) — never a secret, never
a header, never fabricated. `toString()` echoes only the code and the optional id — never the message.

**11. No secrets in the contract, rows, logs or telemetry.**

The `ObjectStoragePort` contract carries no credential fields; the metadata rows
carry no credential-shaped values; logs, traces, metrics, prompts and responses
never contain credentials, document text, prompts or object content.
`storage`'s error taxonomy carries no secret-shaped values either (see
`ADR-020` for the shared redaction gate).

## Alternatives considered

1. **Store the bytes in PostgreSQL (`bytea` / `large objects`).** Simpler
   transaction story (one store), but it bloats the source-of-truth database,
   kills streaming (no bounded read of a 50 MB object), and contradicts the plan
   (PostgreSQL is for metadata/ownership; object payloads live in Garage).
   Rejected.
2. **Transactional linking — 2PC or a shared commit between PostgreSQL and
   Garage.** A true two-store atomic commit would make the gap disappear, but
   Garage has no participating transaction protocol and no two-phase commit
   support; a fake "commit" across two systems is a lie (`ADR-002`/`ADR-003`).
   Rejected in favour of the explicit gap + compensation + reconciliation above.
3. **Bucket-per-tenant / bucket-per-consumer** (physical isolation). Tempting
   for strong tenant isolation, but Garage grants at the key level (a key is
   granted on buckets), one bucket per tenant explodes the provisioning and key
   rotation surface, and the tenant scope already belongs to the app layer
   (`ADR-007`). Rejected for the MVP; the single `agentflow` bucket with
   tenant-scoped keys, enforced by the owning module's PostgreSQL query, is
   sufficient and is what `ADR-013` fixed.
4. **Signed public URLs as the default download path.** Convenient and direct,
   but makes the object itself addressable by the world — permanent or very long
   expiring URLs are forbidden (no public buckets, no permanent URLs). A
   short-lived signed URL (if a direct-download path is ever needed) is a
   bounded, tenant-scoped, reviewable path, not the default. Rejected as the
   default.
5. **Storage-side lifecycle rules / TTLs (e.g. Garage bucket lifecycle).**
   Automatic expiry would delete objects ahead of the consumer's state machine
   (e.g. a document still in `PROCESSING`, a run still recovering), and Garage
   gives no reliable S3 lifecycle abstraction to drive. Rejected; consumers own
   retention.
6. **No checksum verification (size only, or none).** A size is not a content
   guarantee — a truncated or corrupted write would silently pass. Rejected;
   the declared `sha256` is verified at upload and re-verified at read.

## Consequences

- **Positive:** PostgreSQL stays the single source of truth for ownership and
  lifecycle; Garage is never a correctness dependency; upload/delete flows are
  retry-safe and idempotent; orphaned objects are reconciled, not forgotten;
  tenant scope is enforced by the owning module (the contract stays
  provider-neutral); no public buckets, no permanent URLs, credentials never
  leak into the contract, the rows, the logs or the telemetry. This is the
  contract every Sprint 7/10/15 task (`S07-06A`…`S07-06H`, `S07-07`, `S10-02`,
  `S15-22`…`S15-25`) must be checked against.
- **Negative / operational:**
  - The PostgreSQL↔Garage gap is real and visible: a crash between object
    upload and metadata commit leaves an orphan object (compensated or swept),
    and a crash between object delete and metadata removal leaves a dangling
    row (reported, never silently accepted). Both are bounded, observable and
    reconcilable — not silently correct.
  - A Garage outage blocks uploads/downloads for the affected `storage` call,
    never unrelated API operations; the caller must surface a typed failure and
    not present a failed upload as a successful one.
  - The orphan sweep (reconciliation) is a scheduled, operator-visible process,
    not a background guarantee — it cannot be "always on" for the local stack
    in the MVP.
  - Tenant isolation still lives entirely in the owning module's PostgreSQL
    query; Garage's bucket is shared across tenants for the MVP. If a future
    requirement needs physical per-tenant isolation, that is a new ADR — not a
    one-line change.
- **Follow-ups:**
  - `S07-06C`/`S07-06D`/`S07-06H` — the Garage adapter implements the contract
    against this policy (streaming put/get, `head`, idempotent `delete`,
    checksum-verified writes, error classification).
  - `S07-06G` (landed) — the storage access policy: least-privilege runtime
    credentials, public access not configurable, no administrative operations at the
    runtime credential, tenant authorization left to the consuming application service.
  - `S10-02F` — the orphan cleanup / reconciliation sweep (`list` over
    tenant-scoped prefixes + `head` + a diff against the metadata rows,
    operator-visible output, never blind deletion).
  - `S10-02` — `knowledgebase`'s upload path implements rule 2 (object → verify →
    metadata row + outbox in one transaction) and rule 7 (checksum re-verify on
    read).
  - `S07-07` — `workflowexecution`'s checkpoint adapter implements the
    checkpoint key convention (rule 6), the retention fix-up (rule 8) and the
    orphan sweep integration.
  - `S15-22`/`S15-23`/`S15-24`/`S15-25` — Garage in the local Kubernetes cluster,
    bucket/runtime-credential provisioning, backup/restore and the network-policy
    + smoke test — re-verify this policy against the cluster deployment.
  - A signed-URL path, if ever needed, must be a new, bounded design — not a
    silent exception.
