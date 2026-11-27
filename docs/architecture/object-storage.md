# Object Storage — provider-neutral `ObjectStoragePort` contract

> **Status:** Accepted
> **Owner:** `storage` module (`com.example.agentflow.storage`)
> **Task:** `S02-19`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 2, `S02-18`/`S02-19`, Sprint 7 `S07-06A`…`S07-06H`) ·
> `docs/architecture/module-map.md` ("Storage ownership and boundaries") ·
> `docs/architecture/module-api.md` (`storage` row) ·
> `ADR-013` (Garage is the local S3 backend) · `ADR-024` ([object-storage lifecycle/consistency/security](../adr/ADR-024-object-storage.md) — consistency/security/lifecycle, `S02-20`)
> **Purpose:** the provider-neutral contract between the consuming modules and the object store — the five operations, the value types, the stream-ownership and idempotency rules, and the error policy — so that a future Garage adapter (`S07-06C`…`S07-06H`) is a conformance implementation, not a re-interpretation. It is written for the two sanctioned consumers (document originals, workflow checkpoints) and is the specification the contract test suite (`S07-06B`/`S07-06H`) verifies.

---

## 0. Where this sits

`storage` is the 13th bounded context (`S02-18`). It owns the **bytes**; the consuming modules own their
metadata rows in PostgreSQL:

| Object kind | Metadata row (owner) | Bytes (owner) |
|---|---|---|
| Document original | `documents` (`knowledgebase`): `object_key`, `sha256`, `size_bytes`, `mime_type`, `pipeline_version` | `storage` (Garage) |
| Workflow large-payload checkpoint | `workflow_step_runs.output_ref` (`workflowexecution`): ref + checksum + size + `output_schema_version` | `storage` (Garage) |

The contract lives in the `storage` **root package** (`com.example.agentflow.storage`) — that is the
module's public-API boundary, enforced by `StorageBoundaryTest` (no `storage.adapter.*`, no AWS SDK
types, no Garage classes). The adapter is internal (`com.example.agentflow.storage.adapter`, `GarageS3Adapter`)
and is never imported by any other module.

**This document defines the contract, not the implementation.** The `storage` module has no code
yet beyond the S07-06A foundation: the typed `agentflow.storage.*` config (`AgentFlowProperties.Storage`)
and its opt-in singleton S3 client wiring (`StorageClientConfiguration`) exist today — no `storage`
root-package code, port or adapter (`S07-06B` lands the models and port; `S07-06C`…`S07-06H` land the
adapter). Every Java-shaped signature below is an **indicative contract sketch**, not compilable code —
the signatures are normative in behaviour (arguments, return types, failure codes, semantics), not in
syntax. Names, `@`-annotations and exact method shapes are settled at implementation time and must not
deviate from this contract without an ADR.

## 1. The operations

The port exposes exactly five operations. There is no public surface beyond them.

| Operation | Signature (indicative) | Returns | Notes |
|---|---|---|---|
| `put` | `ObjectReference put(CreateObjectRequest request)` | `ObjectReference` | Store a stream; returns the persistable handle |
| `get` (`openStream`) | `OpenedObject get(String key)` | `OpenedObject` | Streaming read; caller owns the stream |
| `head` | `Optional<ObjectMetadata> head(String key)` | `Optional` | Presence + metadata; never throws for a missing key |
| `list` | `ObjectListPage list(String prefix, String cursor, int limit)` | `ObjectListPage` | Paginated, server-side prefix; not user-facing |
| `delete` | `DeleteResult delete(String key)` | `DeleteResult` | Idempotent; never fails for a missing key |

Only `knowledgebase` (document originals) and `workflowexecution` (large-payload checkpoints) may call
the port. `ingestion` reaches object bytes through `knowledgebase`'s `DocumentPayloadStore` — never
through `storage` directly. No other module depends on `storage` (arrows 22/23 in the module map).

## 2. Value types

All value types are Java, immutable, in `com.example.agentflow.storage`, and carry no provider types.

### 2.1 `ObjectReference` — the opaque, persistable handle

| Field | Type | Rule |
|---|---|---|
| `bucket` | `String` | The configured bucket the object was written to (self-describing; never re-chosen by the caller) |
| `key` | `String` | The object key (server-generated; see §8) |
| `contentType` | `String` | The media type stored with the object, as accepted by the contract |
| `size` | `long` | The stored size in bytes, `≥ 0` |
| `sha256` | `String` | Lower-case hex SHA-256 of the stored bytes (64 chars) |

The reference is the value persisted alongside the metadata row (documents keep the key; checkpoints
keep the ref/checksum/size) and returned by `put` and `list`. It is a plain value — no provider hooks.

### 2.2 Request / response views

| Type | Role |
|---|---|
| `CreateObjectRequest` | `put` input: `key` (required, validated), `stream` (required), `contentType` (required), optional `contentLength` (declared `long`, `≥ 0`), optional `sha256` (declared hex), optional `metadata` (bounded map) |
| `OpenedObject` | `get` result: `ObjectMetadata metadata()` + `InputStream stream()` — the stream is opened eagerly with the metadata, and the caller must close it (try-with-resources) |
| `ObjectMetadata` | `head` result: the five `ObjectReference` fields plus `lastModified` (`Instant`) and `etag` (`String`, provider-neutral) |
| `ObjectListPage` | `list` result: `List<ObjectReference> items` (ascending `key` order), `nextCursor` (`String?` — null when the page is the last), `hasMore` (`boolean`) |
| `DeleteResult` | `delete` result: an enum outcome — `DELETED` (the object existed and was removed) or `ALREADY_ABSENT` (it did not exist; an idempotent success). Never a failure |

### 2.3 Errors — `ObjectStorageException`

The port's failure taxonomy. Codes are provider-neutral and stable; the adapter converts provider
exceptions into these — no AWS SDK exception, class name or detail may escape through the contract.

| Code | Class of failure | Retryable? | Typical cause |
|---|---|---|---|
| `INVALID_KEY` | Deterministic caller-side rejection | No | blank/oversized/illegal key |
| `INVALID_REQUEST` | Deterministic caller-side rejection | No | bad `contentType`, bad `limit`, unexpected/mismatched declared length, bad metadata |
| `NOT_FOUND` | Typed miss | No | `get` (or a future operation) on a missing key |
| `ACCESS_DENIED` | Permission/auth | No | credentials lack the operation on the bucket |
| `UNAVAILABLE` | Transient infrastructure | Yes (bounded backoff) | network failure, throttling, 5xx |
| `TIMEOUT` | Transient infrastructure | Yes (bounded backoff) | bounded wait exceeded |
| `PERMANENT_FAILURE` | Non-transient infrastructure | No | structural provider fault (e.g. malformed bucket config) |

Retries (when a caller retries at all) are bounded and exponential with jitter, and applied only to
`UNAVAILABLE` / `TIMEOUT` — never to `NOT_FOUND`, `ACCESS_DENIED`, `INVALID_*` or `PERMANENT_FAILURE`.
The full retry policy is the caller's responsibility; `storage` classifies and reports, it does not
sleep.

## 3. Streaming and ownership

No operation may require the whole object to fit in heap:

- **`put`** consumes a caller-provided `InputStream`. The caller transfers stream ownership to the
  adapter when the call starts; the adapter must close (or release) the stream on success, failure
  and cancellation, and the caller must not reuse it after `put` returns. The adapter may not
  buffer the stream (it may use a bounded chunk — e.g. 64 KiB — but never `readAllBytes()`).
- **`get`** returns `OpenedObject` whose `stream()` is the caller's to read and close. The caller
  must close the stream (try-with-resources). The adapter guarantees the underlying provider stream
  is released on success, exception and cancellation. Reading is bounded-memory — the consumer
  reads in chunks, not as a single buffer.
- **`head`** reads metadata only; it never opens the object content.
- **`list`** reads keys and list metadata only; it never opens object content.
- **`delete`** never opens content.

The concrete bounded-memory test (a large stream read in chunks, asserting the consumer never holds
the full object) is part of the contract suite (§9).

## 4. Behaviour — the four required cases

| Case | `put` | `head` | `get` | `delete` |
|---|---|---|---|---|
| **Missing object** | n/a — `put` is a create/replace | `Optional.empty()` (no exception) | `ObjectStorageException` `NOT_FOUND` | `DeleteResult.ALREADY_ABSENT` (success) |
| **Empty object (0 bytes)** | Stores a zero-size object; reference carries `size = 0` and `sha256` = hex of the empty string (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`) | `ObjectMetadata` with `size = 0`, same `sha256` | `OpenedObject` whose stream yields 0 bytes | `DELETED` on first delete, `ALREADY_ABSENT` after |
| **Repeated write (same key)** | Deterministic: re-`put` is a replace, never a second logical object. Identical content → same reference, one logical object. Different content → the new content replaces the old (the reference's `size`/`sha256` change) | Reflects the current content | Reads the current content | n/a |
| **Repeated delete (same key)** | n/a | `Optional.empty()` | `NOT_FOUND` | First → `DELETED`; subsequent (or after an external delete) → `ALREADY_ABSENT` — always a success |

Guarantees:

- `put` on the same key is **idempotent in identity**: repeated writes never create a second logical
  object (the contract is not an append log). Immutability of a key's *content* is the consumer's
  responsibility — the consumer (`knowledgebase`/`workflowexecution`) never reuses a live key for
  different content (the key is generated once, at upload time, and never changes).
- `list` returns objects in ascending `key` order. The cursor is opaque — a caller must not parse it.
- `delete` of a missing object is a success, never an error — this is what makes replay/redrive safe.

## 5. The operations in detail

### 5.1 `put`

- **Input:** `key`, a `stream`, `contentType`, and optionally `contentLength`, `sha256`, `metadata`.
- **Validation (deterministic):** blank or oversized key (> 1024 chars, S3's limit), keys containing
  control characters (NUL/CR/LF) or `..` path segments, or a blank `contentType` → `INVALID_KEY` /
  `INVALID_REQUEST`. Bounded metadata (≤ 10 entries, keys ≤ 128 chars, values ≤ 256 chars, no
  secrets, no prompts, no document text — the contract refuses anything outside its whitelist) →
  `INVALID_REQUEST`. If a declared `contentLength` or `sha256` is provided, the adapter must verify
  the actual bytes against it and fail `INVALID_REQUEST` on mismatch (never silently accepts a
  mismatched write).
- **Semantics:** a streaming upsert. Identical re-`put` is an idempotent no-op in identity (same
  reference — one logical object); different content replaces the object atomically.
- **Errors:** `INVALID_KEY` / `INVALID_REQUEST` (caller-side); `ACCESS_DENIED`, `UNAVAILABLE`,
  `TIMEOUT`, `PERMANENT_FAILURE` (adapter-side). Never a provider-specific exception.

### 5.2 `get` (`openStream`)

- **Input:** the key (never a bucket — the adapter is bound to its configured bucket).
- **Behavior:** opens the object for streaming read and returns `OpenedObject` (metadata + stream).
  Missing key → `NOT_FOUND` (typed — never a raw provider exception). The stream is caller-owned:
  close it after reading (try-with-resources); an adapter that fails mid-stream surfaces `UNAVAILABLE`
  / `TIMEOUT`.
- **No heap-loading:** the read is chunked; the consumer never holds the whole object in memory.

### 5.3 `head`

- **Input:** the key.
- **Behavior:** `Optional<ObjectMetadata>`. Present → metadata (five reference fields + `lastModified`
  + `etag`). Missing → `Optional.empty()` — presence is checked without throwing.

### 5.4 `list`

- **Input:** a prefix, an opaque cursor (`null` for the first page), a `limit` (`1 ≤ limit ≤ 100`,
  default `50`).
- **Behavior:** returns `ObjectListPage` — ascending-`key` items, `hasMore`, and a `nextCursor`
  (non-null iff `hasMore`). Paginated: a page is stable while other objects appear/disappear
  *after* it (the cursor encodes the last returned key).
- **Scope:** the prefix is server-generated and tenant-scoped (a consumer passes the prefix it is
  authorized to see; `storage` never derives or checks tenant scope — see §8/§10). A blank or
  out-of-contract prefix, or an out-of-range limit, is rejected deterministically
  (`INVALID_KEY` / `INVALID_REQUEST`).
- **Safety:** `list` is never used for authorization — it is a reconciliation/inspection operation,
  never a path to a cross-tenant read (the consumer scopes the prefix itself).

### 5.5 `delete`

- **Input:** the key.
- **Behavior:** `DeleteResult` — `DELETED` if the object existed and was removed, `ALREADY_ABSENT` if
  it did not. Always a success; replay/redrive safe. Never opens content.

## 6. What the contract deliberately does not do

- **No tenant scope enforcement.** `storage` does not inspect, parse or authorize keys. A consumer
  derives the tenant from the verified security context (HTTP/stored event re-checked against
  PostgreSQL), generates the tenant-scoped key itself, checks access against its own metadata row,
  and only then calls the port. A foreign tenant's object is indistinguishable from a missing one —
  by the consumer's own `(tenant_id, resource_id)` query, not by `storage`.
- **No metadata rows.** `storage` never owns a table; it moves bytes and reports what happened.
  The `documents` row and the `workflow_step_runs.output_ref` row carry the reference/checksum/size;
  `storage` is the bytes only.
- **No public URLs.** No public buckets, no anonymous access, no public URLs — an object is addressed
  only through the app (see [ADR-024](../adr/ADR-024-object-storage.md), `S02-20`).
- **No logging of credentials or object content.** The contract carries no credential fields;
  the metadata rows carry no credential-shaped values; logs, metrics, traces, prompts and responses
  never contain credentials, object content, document text, prompts or unrestricted keys (see
  [ADR-024](../adr/ADR-024-object-storage.md) rule 11 and `ADR-020`'s shared redaction gate).
- **No retry loop.** `storage` classifies and reports; bounded backoff/jitter is the caller's policy.
- **No provider types.** No AWS SDK / Garage class crosses the contract boundary in either direction
  (arguments, returns, exceptions). The gate is `StorageBoundaryTest` (contract purity) plus a
  reflection check in the contract suite (§9).
- **No arbitrary keys from the client.** The port stores what it is told; the key is always
  application-generated (§8).
- **Not lifecycle/consistency policy.** Upload/delete state transitions, orphan cleanup, checksum
  verification, retry/compensation policy and the PostgreSQL↔Garage consistency gap are ADR-024's
  business ([ADR-024](../adr/ADR-024-object-storage.md), `S02-20`) — recorded there, not here.

## 7. Sufficiency — document originals and workflow artifacts

The contract is sufficient for both sanctioned consumers:

| Consumer | Use | How the contract covers it |
|---|---|---|
| `knowledgebase` (document originals) | Upload: generate the tenant-scoped key (`documents/<tenantId>/<documentId>[.<ext>]`), `put` with declared `sha256`/`contentLength`; read: `get`/`head`; delete: `delete` | `put` (streaming, checksum-verified, empty-object safe) · `get`/`head` (bounded read, typed miss) · `delete` (idempotent) |
| `workflowexecution` (large-payload checkpoints, S07-07) | Persist `output_ref` (ref + checksum + size + `output_schema_version`), `put` of the checkpoint payload; read for recovery; cleanup on fix-up | `put` (streaming, replace-on-repeat, checksum-verified) · `get` (bounded read) · `head` + `delete` (presence, size, cleanup) · `list` (reconciliation/inspection over the checkpoint prefix) |

Neither consumer needs anything beyond these five operations and the value types above. Nothing in the
contract carries document state, run state, a workflow step state or a tenant id — so the contract
never constrains a consumer's lifecycle.

## 8. Keys are application-generated — never the user's

- The object key is always **server-generated** from the server-issued identifiers, never from the
  client's filename, body, query parameter or a Kafka header (invariant 1 / ADR-007).
- `knowledgebase` generates the document key: `documents/<tenantId>/<documentId>[.<ext>]`, with the
  extension a content-type hint (a closed whitelist) and never a path segment — so a hostile or
  prompt-injected filename can never become an object path or leak across tenants.
- `workflowexecution` generates the checkpoint key (its convention is `S07-07`'s, not yet built).
- The port treats the key as an opaque, structurally-validated string; it never interprets it.
  `storage` never derives, rewrites or validates tenant scope from the key.
- A client-supplied key is a contract violation: the port validates the key structurally (blank,
  oversized, control characters, `..` segments) and rejects it deterministically — the app layer
  generates keys and the port refuses anything else.

## 9. Contract test suite — how future adapters are tested

Unit tests for a future adapter are written through a **shared, parameterized contract suite** — the
same suite drives any implementation of `ObjectStoragePort`, with the adapter under test injected as
the single substitution point. The suite lives next to the adapter (e.g. a shared base class in the
`storage` test source set, parameterized over `ObjectStoragePort`); the adapter supplies one instance
and a fixture per run. The actual suite lands with `S07-06B` (contract unit tests) and `S07-06H`
(integration); this section is its specification.

| # | Case | Proves |
|---|---|---|
| 1 | **Type safety** — `ObjectReference` rejects blank/oversized/illegal keys; `CreateObjectRequest` rejects a blank content type; bounded metadata | Required-field discipline |
| 2 | **Empty object** — `put` a 0-byte stream; `head` reports `size = 0` + the empty-string SHA-256; `get` yields an empty stream; `list` includes it | Empty-object contract |
| 3 | **Missing object** — `head` → empty; `get` → `NOT_FOUND`; `delete` → `ALREADY_ABSENT` (success) | Typed miss policy |
| 4 | **Repeated put** — identical content re-`put` yields the same reference and one logical object; different content re-`put` replaces | Idempotency / replace determinism |
| 5 | **Repeated delete** — first → `DELETED`; subsequent → `ALREADY_ABSENT`; either is a success | Idempotent delete |
| 6 | **Invalid key / metadata** — blank, oversized, control-char, `..` keys and out-of-bound metadata are rejected deterministically | Contract validation |
| 7 | **Declared length/checksum mismatch** — a declared `contentLength` or `sha256` that disagrees with the actual bytes is rejected | Fail-loud verification |
| 8 | **Streaming / bounded memory** — a large stream is `put` and `get` in chunks; the consumer never holds the full object | No heap-loading; bounded memory |
| 9 | **Stream ownership** — the incoming stream is released after `put` (success and failure); the returned stream is released on success and failure | No leaked streams |
| 10 | **Pagination** — `list` honours `limit`, returns ascending keys, yields a cursor whose page is stable, and `hasMore`/`nextCursor` are consistent with the data | Paginated listing |
| 11 | **No provider leakage** — reflection over the public API: no `software.amazon.awssdk.*` (or Garage class) in any public parameter, return type or exception | Provider neutrality |

Coverage discipline: the suite runs against **every** adapter (the current Garage adapter in
`S07-06H` and any future provider), and it is reusable — a second provider adds a new adapter package
and a new wiring of the same suite, not a new suite.

## 10. Example calls (indicative — the module is not yet implemented)

Illustrative pseudo-Java, shown for the two consumers. Signatures are indicative spec sketches; the
real signatures are settled at `S07-06B` and must not drift from this contract.

```java
// knowledgebase — upload a document (S10-02's DocumentPayloadStore path)
String key = documentObjectKey.generate(tenantId, documentId, contentType); // server-generated
ObjectReference ref = storage.put(new CreateObjectRequest(
        key, stream, "application/pdf", bytes, declaredSha256, Optional.empty()));
// ref = ObjectReference(bucket, key, "application/pdf", size, sha256) — persisted as documents.object_key + sha256

// knowledgebase — read back for re-ingestion / download
try (OpenedObject obj = storage.get(key)) {
    ObjectMetadata meta = obj.metadata();   // size, sha256, contentType, lastModified
    try (InputStream in = obj.stream()) { readChunks(in); }
} catch (ObjectStorageException e) { /* NOT_FOUND → the caller reports insufficient evidence */ }

// knowledgebase — lifecycle cleanup
DeleteResult r = storage.delete(key);       // DELETED — or ALREADY_ABSENT on replay; never an error

// workflowexecution — large-payload checkpoint (S07-07)
ObjectReference ref = storage.put(new CreateObjectRequest(
        checkpointKey, stream, "application/octet-stream", null, declaredSha256, Optional.empty()));

// workflowexecution — recovery: check presence + size before reading
Optional<ObjectMetadata> meta = storage.head(checkpointKey);  // Optional.empty() → the payload is gone

// workflowexecution — paginate a tenant prefix for reconciliation (never for authorization)
ObjectListPage page = storage.list("checkpoints/" + tenantId + "/", null, 50);
while (page.hasMore()) { page = storage.list("checkpoints/" + tenantId + "/", page.nextCursor(), 50); }
```

## 11. Trace to the plan and the pending work

| Task | Role in the lifecycle |
|---|---|
| `S02-19` (this doc) | Defines the contract — the spec above |
| `S02-20` (`Accepted`) | ADR-024: consistency/security/lifecycle — the PostgreSQL↔Garage gap, upload/delete state transitions, retry/compensation, orphan cleanup, checksum verification policy, Garage-outage policy, credential separation, key-convention reconciliation |
| `S07-06A` (landed) | AWS SDK for Java v2 + `agentflow.storage.*` typed config — validated `@ConfigurationProperties` (endpoint/region/bucket/path-style/timeouts/retry limits), env-var credentials, singleton `S3Client` wiring, opt-in |
| `S07-06B` | Contract unit tests (§9's suite) + `ObjectStoragePort` and the value types in code |
| `S07-06C/D/E` | Garage `put` / `get` streaming / `head` + idempotent `delete` (+ `list`) |
| `S07-06F` | Timeout/retry/error mapping |
| `S07-06H` | Garage integration/contract test suite (the §9 suite against a real Garage) |

The contract is **sufficient** for both document originals and workflow artifacts and is **not** a
Garage-specific type. Unit tests for future adapters are specified in §9 and land with `S07-06B` /
`S07-06H`.

### 11.1 Integration scenarios — Sprint 7 / 10 / 15 (planned)

These are the cross-module scenarios that exercise the contract end to end. **None of them is built
today** — the `storage` module has no code — so they are recorded here as planned, each checked
against this contract and ADR-024, never as shipped behaviour:

| Sprint | Task | Scenario (planned) |
|---|---|---|
| 7 | `S07-06A` (landed) | AWS SDK for Java v2 + validated `agentflow.storage.*` typed config (endpoint/region/bucket/timeouts); secrets only from env/Secret references |
| 7 | `S07-06B` | `ObjectStoragePort` + value types in code; §9 contract unit suite over the contract |
| 7 | `S07-06C` | `put` — streaming write to Garage, deterministic replace on the same immutable key |
| 7 | `S07-06D` | `get` — bounded streaming read, no `readAllBytes()`; stream released on success/failure/cancellation |
| 7 | `S07-06E` | `head` + paginated `list` (server-side prefix only) + idempotent `delete` |
| 7 | `S07-06F` | Timeout/retry classification and error mapping (`UNAVAILABLE`/`TIMEOUT` retryable, everything else not) |
| 7 | `S07-06G` | Credentials: provisioning/admin vs runtime separation; tenant authorization stays in the consuming application service |
| 7 | `S07-06H` | Garage integration/contract test suite — the §9 suite against a real Garage fixture |
| 7 | `S07-06I` | `StorageHealthIndicator` + bounded storage metrics (`operation`, `result` — never tenant/key/filename) |
| 7 | `S07-06J` | Garage adapter developer guide (`docs/storage/garage-s3.md`) |
| 7 | `S07-07` | Workflow checkpoints — `output_ref` (ref + checksum + size + `output_schema_version`) + the large-payload path; checkpoint key convention `checkpoints/<tenantId>/<runId>/<stepRunId>[.<ext>]` |
| 10 | `S10-02` | Garage bucket provisioning + key convention (`documents/<tenantId>/<documentId>[.<ext>]`, private bucket) |
| 10 | `S10-02A` | Idempotent bucket provisioning Job/script; bucket exists check before storage integration tests |
| 10 | `S10-02B` | `DocumentObjectStorageService` — server-generated keys via a key builder, checksum + metadata, provider-neutral `ObjectReference` |
| 10 | `S10-02C` | Upload orchestration — `UPLOAD_PENDING → object stored/verified → UPLOADED/QUEUED + outbox` in one transaction; no event on an unverified object |
| 10 | `S10-02D` | Safe document read — tenant/document ownership from PostgreSQL, streaming read, checksum re-verify before Tika/PDFBox |
| 10 | `S10-02E` | Document deletion — `DELETE_REQUESTED → DELETED`, object deleted first, `DELETED` only after successful removal |
| 10 | `S10-02F` | Orphan object reconciliation — bounded windows, grace period, dry-run, audited decisions (never blind deletion) |
| 10 | `S10-02G` | Garage outage / recovery / tenant isolation — upload/download/delete failure modes, cross-tenant denial, checksum mismatch detection |
| 10 | `S10-02H` | Garage document lifecycle guide (`docs/storage/garage-s3.md` + `docs/rag/pipeline.md`) |
| 15 | `S15-22` | Garage deployed in the local Kubernetes cluster (Helm StatefulSet, internal Service, PVC) |
| 15 | `S15-23` | Kubernetes bucket + runtime credentials provisioning (`Secret` references, rotation) |
| 15 | `S15-24` | Garage backup/restore — strategy, restore runbook, checksum verification of originals/checkpoints |
| 15 | `S15-25` | NetworkPolicy + smoke test — only API/ingestion/workflow roles reach Garage; CRUD smoke via `GarageS3Adapter` |

Every one of these scenarios must be checked against [ADR-024](../adr/ADR-024-object-storage.md)
(upload/delete policy, failure behaviour, orphan reconciliation, credential separation) and this
contract — none of them may introduce a storage-specific assumption that contradicts either.
