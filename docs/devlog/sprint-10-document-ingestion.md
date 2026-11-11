# Changelog


# Sprint 10 — Document ingestion, pgvector, hybrid retrieval and citations


## S10-06 — Commit the upload and enqueue ingestion through the transactional outbox (async, non-blocking).

### What changed

- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentService.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentService.java) — the `S10-06` upload / ingestion-enqueue use case (`knowledgebase.application`). `#upload` is the single entry point that turns a `S10-03` `ValidatedUpload` (the accept/bound-checked payload) into a durable, queued document: it generates a server-issued `documentId` up front (stable across a retry) and its `object_key` (`DocumentObjectKey#of`); inserts the `documents` metadata row in status `UPLOADED` (the state `S10-03` returns) through `DocumentRepository`; and appends the `agentflow.document.ingestion-requested.v1` event to the transactional outbox through `OutboxService`. Both writes run inside one `@Transactional` (`REQUIRED`) method, so a document is never committed without its event and the event is never committed without durable state (invariant 3, `ADR-004`). The `tenantId` comes from the verified security context (invariant 1); the event carries only correlation metadata (opaque ids + `CorrelationHeaders`, never a secret / full prompt / full document); `correlationId = documentId`, `causationId = null` (the request event has no predecessor).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentDto.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentDto.java) — the API view of the persisted document returned by `upload` (`documentId`, `knowledgeBaseId`, `status = UPLOADED`).
- [`apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/persistence/DocumentRepository.java`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/persistence/DocumentRepository.java) — the single write path for a `documents` metadata row. Backed by a plain `JdbcTemplate` (`ADR-009`), the row is written under the tenant passed in (the `tenant_id` column is the isolation key), and the bean is registered only with a `DataSource` (`@ConditionalOnBean(DataSource.class)`) — the application boots without a database (`S01-02`) and `upload` fails loudly with `503` when the repository is absent, exactly as `workflowexecution` does for its repositories (`@Autowired(required = false)` seam on the constructor parameter).
- [`apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentServiceTest.java`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentServiceTest.java) — the DB-free unit suite (5 tests) pinning the three `S10-06` facts that must hold before any storage backend or worker exists.
- `docs/adr/ADR-015-knowledgebase-outbox-edge.md` — the new `knowledgebase → messaging` edge: the upload enqueues `ingestion-requested` through the transactional-outbox port.
- `docs/architecture/module-map.md`, `apps/.../ArchitectureBoundaryTest.java`, `docs/adr/README.md` — edge 18 (`knowledgebase → messaging`) added to the diagram, the boundary ALLOW map and the ADR register.

### Why

- **Row + event in one transaction.** The two writes run inside one `@Transactional` method with the default `REQUIRED` propagation, so neither commits without the other (invariant 3, `ADR-004`). This is precisely what makes the upload <b>non-blocking on parsing/embedding</b>: the HTTP response returns `UPLOADED` as soon as the row + event are durably written, and the `QUEUED → PROCESSING → INDEXED` work is done later by the ingestion worker on the event. The parser (`S10-07`) and the embedding pipeline (`S10-11`) are <em>never</em> invoked here, directly or indirectly, so an upload never waits for them (the `S10-06` acceptance criterion).
- **The edge needed an ADR.** `knowledgebase` did not previously depend on `messaging`, so `ADR-015` records the trade-off: the upload enqueues `ingestion-requested` through the same transactional-outbox port `workflowexecution` uses (`OutboxService`), keyed `tenantId:documentId` (events §7), delivered at-least-once and idempotent by `tenant_id + sha256 + pipeline_version` at the consumer (`S10-13`).

### Verification

The suite (5 tests, JUnit XML checked: `tests="5" skipped="0" failures="0" errors="0"`) pins, DB-free (`DocumentRepository` / `OutboxService` mocked, no `DataSource`): the row is committed tenant-scoped under the exact `tenantId` in `UPLOADED` with the server-generated `object_key`; the `ingestion-requested` event is appended in the same call (`aggregateType = document`, keyed to the document, tenant-scoped, `correlationId = documentId`, `causationId = null`) and `topic = agentflow.document.ingestion-requested.v1`; the flow returns the async `UPLOADED` and never transitions to `QUEUED`; and the row insert + outbox append happen together (each exactly once) in one flow.

`./gradlew :apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest`, `NoCyclicModuleDependenciesTest`) + no-cycles + `spotless` + OpenAPI gates. The new `knowledgebase → messaging` edge does **not** create a module cycle: the document-upload wiring (`DocumentUploadConfiguration`, `S10-03`) lives in the `knowledgebase` module rather than the global `config` package, so `config` does not depend upward on `knowledgebase` — that edge, together with `knowledgebase → messaging` and the existing `messaging → config` (`AgentFlowProperties`) edge, would otherwise have formed `config → knowledgebase → messaging → config`.

### Not changed

- The upload controller / OpenAPI (`POST …/documents`, `202`) — a follow-up wires `DocumentService#upload` to HTTP.
- The ingestion worker (`S10-07` … `S10-13`), the `(tenant_id, id)` read, the object storage client (`S10-03`), the append-only audit event the upload writes (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement are the rest of the secure upload flow; they complete when their schema / persistence / wiring land (`S10-03` … `S10-08`).

---

## S10-05 — The knowledge base and document schema (migration + duplicate-upload policy).

### What changed

- `apps/agentflow-app/src/main/resources/migrations/V3_3_18__create_knowledge_bases_documents.sql` — the migration. It is step 19 of the ordered Flyway line (`V3_3_17` … `V3_3_18`), owner `knowledgebase`, and applies cleanly against PostgreSQL 18 (pgvector `pgvector/pgvector:0.8.6-pg18`) — the exact image Compose and Testcontainers use.
- `CHANGELOG.md` — an `Unreleased → Added` entry (S10-05) describing the schema and the duplicate-upload policy.

The `knowledgebase` module boundary, the OpenAPI contract and the Java code are unchanged: this is a schema + documentation change (no controller, no repository, no listener).

`knowledge_bases` (one tenant-scoped collection per `unique (tenant_id, name)`):

| column | type | constraint / default | meaning |
|---|---|---|---|
| `id` | `UUID` | PK | collection id (assigned by the domain factory) |
| `tenant_id` | `UUID` | FK `tenants.id`, `NOT NULL` | owning tenant (isolation by `(tenant_id, name)` + `(tenant_id, id)` read) |
| `name` | `TEXT` | `NOT NULL`, part of unique | fixed after creation — collection is immutable |
| `embedding_model` | `TEXT` | `NOT NULL` | pinned model; dimensionality is fixed by `S10-11` |
| `chunking_config` | `JSONB` | nullable | optional JSON chunker configuration |
| `status` | `TEXT` | `NOT NULL DEFAULT 'ACTIVE'` | application-managed collection status |
| `created_by` | `TEXT` | nullable | audit |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` | audit (immutable-row set — no `version`, no `updated_at`) |

`documents` (one row per uploaded object; created after `knowledge_bases` so the FK resolves):

| column | type | constraint / default | meaning |
|---|---|---|---|
| `id` | `UUID` | PK | document id (canonical id on `GET /api/v1/documents/{documentId}`) |
| `tenant_id` | `UUID` | FK `tenants.id`, `NOT NULL` | owning tenant |
| `knowledge_base_id` | `UUID` | FK `knowledge_bases.id`, `NOT NULL` | parent collection |
| `object_key` | `TEXT` | `NOT NULL` | server-generated, tenant-scoped object path (`documents/<tenantId>/<id>[.<ext>]`), generated once at upload, immutable |
| `filename` | `TEXT` | `NOT NULL` | safe, sanitized display name (never a path) |
| `mime_type` | `TEXT` | `NOT NULL` | canonical content type resolved by the validator |
| `size_bytes` | `BIGINT` | `NOT NULL` | payload length (the `S10-03` size bound is applied before commit) |
| `sha256` | `TEXT` | `NOT NULL` | payload SHA-256; content fingerprint for duplicate detection + ingestion idempotency |
| `version` | `INTEGER` | `DEFAULT 0` | optimistic-lock counter → `409` on conflict |
| `status` | `TEXT` | `DEFAULT 'UPLOADED'`, CHECK over 7 states | lifecycle (`document-lifecycle.md`) |
| `pipeline_version` | `TEXT` | `NOT NULL DEFAULT 'v1'` | chunking/embedding pipeline version; part of the idempotency key + reindex trigger (`S10-19`) |
| `page_count` | `INTEGER` | nullable | parser metadata (extractor output; NULL at `UPLOADED`) |
| `error_code` | `TEXT` | nullable | safe machine-readable failure token (`S10-08`) |
| `error_summary` | `TEXT` | nullable | short redacted failure summary (`S10-08`) |
| `retained_until` | `TIMESTAMPTZ` | nullable | retention deadline for object + chunks |
| `created_by` / `created_at` / `updated_at` | `TEXT` / `TIMESTAMPTZ` / `TIMESTAMPTZ` | `created_at` default `now()` | unified audit set on the mutable row |

Indexes: `(tenant_id, knowledge_base_id, status)` — a knowledge base's document list, tenant-scoped, status-filterable (plan §8.1); `(tenant_id, sha256)` — duplicate detection / idempotent lookup.

### Why

- **Retention + lifecycle as constraints, not post-filters.** The status `CHECK` pins the seven canonical states (`UPLOADED → QUEUED → PROCESSING → INDEXED`, `FAILED`, `DELETE_REQUESTED`, `DELETED`); a transition is a conditional update + optimistic `version` bump + append-only audit record in one transaction, never set directly from a controller/listener (invariant 4). `retained_until` carries the retention deadline at the row, so retention is enforced at query time, not after the fact.
- **Duplicate upload policy (determined here, implemented by `S10-19`).** A re-upload whose `(tenant_id, sha256)` matches an existing document is *content-identical*, so it is treated as a duplicate — the upload API returns the existing document and creates no new row. This keeps ingestion idempotent by content ("a repeated upload does not create duplicate logical chunks", `S10-13`) and never treats delivery as exactly-once (invariant 3). The `sha256` column is **indexed, not made unique**: a change in `pipeline_version` reindexes as a new document (`S10-19`), which a `unique (tenant_id, sha256)` would wrongly forbid.
- **`documents` is a mutable row, `knowledge_bases` is immutable.** `documents` gets an optimistic `version` counter + `updated_at` (the row changes status); `knowledge_bases` is fixed after creation, so it gets no counter — mirroring the run-state-machine separation in `S05-01`.

### Verification

All of it ran against a live PostgreSQL 18 container (`pgvector/pgvector:0.8.6-pg18`), the same image Compose and Testcontainers use. The migration line applied cleanly as step 19 (`./gradlew flywayMigrate`, now at `v3.3.18`).

- **Schema.** `information_schema` + `pg_constraint` confirm: FKs `documents.knowledge_base_id → knowledge_bases`, `documents.tenant_id → tenants`, `knowledge_bases.tenant_id → tenants`; PKs; the `unique (tenant_id, name)` on `knowledge_bases`; the `documents_status_check` over the seven states; and `NOT NULL` on every required column. `information_schema` confirms `object_key`, `status`, `version`, `pipeline_version`, `page_count`, `error_code`, `error_summary`, `retained_until` exist on `documents`.
- **Positive path.** A tenant → knowledge base → document chain inserts (`INSERT 0 1` ×3); the persisted document reads back `status = UPLOADED`, `page_count = 7`, the right `sha256`/`object_key`, tenant-scoped.
- **Negative paths.** A bogus `tenant_id` is rejected by the FK ("Key … is not present in table tenants"); `status = 'PROCESSED'` is rejected by the CHECK; a duplicate `(tenant_id, name)` is rejected by the unique constraint; a `tenant-b` lookup returns 0 rows for a `tenant-a` document (isolation); `EXPLAIN` shows the `(tenant_id, sha256)` index backing the duplicate-detection lookup ("Index Scan using documents_tenant_sha256_idx").

`git diff --check` clean. The `knowledgebase` module boundary and the OpenAPI contract are unchanged (no Java, no controller) — this is a schema + documentation change, so the Java `check` gate (unit + architecture + no-cycles + formatting + OpenAPI) does not need to re-validate anything here, though the broader `:apps:agentflow-app:check` run is green.

### Not changed

- The row binding of `documents` to the verified `TenantContext`, the `(tenant_id, id)` row lookup, the object storage client (`S10-03`), the append-only audit event the upload writes (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement are the rest of the secure upload flow; they complete when their schema / persistence / wiring land (`S10-03` … `S10-06`, `S10-08`).
- The ingestion worker, the pgvector/FTS chunk indexing and the live duplicate-detection enforcement are `S10-11` / `S10-13` / `S10-19`.

---
## S10-04 — Tenant-safe file upload (negative tests for path traversal and cross-tenant access).


### What changed
The part of `S10-04` that is verifiable before the `documents` schema (S10-05), the object storage
client and the `(tenant_id, id)` row lookup exist — the two invariants the future upload flow builds
on (invariant 1), captured as a DB-free negative suite in `knowledgebase.domain`, exactly as `S10-02`
gave a usable value type and `S10-03` gave the accept/bound layer before their real dependencies:
[`DocumentUploadTenantSafetyTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentUploadTenantSafetyTest.java).

- **Path traversal.** The object key is generated <b>server-side</b> from the server-issued UUIDs,
  never from the client `filename` — see `S10-02` [`DocumentObjectKey`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKey.java); two uploads of
  the same document with two different (even hostile) filenames map to the exact same key, whose shape
  is exactly `documents/<tenantId>/<documentId>[.<ext>]` (3 segments, no `..`, no extra segment), so a
  traversal or extra path a malicious filename would inject is impossible; a prompt-injected extension
  (`exe` / `jsp` / `php` / `..` / `a/b` / …) is rejected as a non-whitelisted content-type hint; and
  [`FileNameSanitizer`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizer.java)
  reduces any path/traversal to a bare base name (defense-in-depth: the display filename the row carries
  can never be a path).
- **Cross-tenant access.** An object key is <b>bound</b> to exactly the tenant that issued it (a key
  parsed from its string still resolves to that tenant, it cannot be re-issued under another); the same
  document maps to <b>distinct</b> keys across tenants (`of(TenantA, doc) != of(TenantB, doc)`); another
  tenant's key, read in tenant A's context, still resolves to tenant B, so a tenant-scoped lookup keyed
  by `(tenant_id, document_id)` treats it as a missing one (the S10-04 "indistinguishable from a missing
  one" policy); a tenant cannot authorize another tenant's key string, and a forged key for a different
  document under the same tenant is a distinct, non-authorizable key.

### Why
`S10-02` proved the key is generated server-side and tenant-scoped; `S10-03` proved the accept/bound
layer rejects a hostile filename and content type. `S10-04`'s acceptance — *path traversal and access to
another tenant's document covered by negative tests* — is the isolation guarantee those two layers make
usable: without it, a tenant could read another tenant's object, or a hostile filename could become an
object path. Those are exactly the negative scenarios `AGENTS.md` lists as mandatory, and the object-key
shape plus the filename sanitizer already encode them, so a DB-free suite pins them today. No behaviour,
interface or design choice is left open beyond the closed `knowledgebase` value types, so
`brainstorming` is skipped.

### Verification
- **Path traversal** — the key is generated from the ids only (never the filename) and a hostile
  extension is rejected: `DocumentUploadTenantSafetyTest#PathTraversal` (3 tests, JUnit XML
  `tests="3"` `failures="0"`).
- **Cross-tenant access** — the key is bound to its issuing tenant, the same document yields distinct
  keys across tenants, and another tenant's key resolves to that tenant (missing here):
  `DocumentUploadTenantSafetyTest#CrossTenantAccess` (5 tests, JUnit XML `tests="5"` `failures="0"`).
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + spotless + OpenAPI gates);
  `git diff --check` clean; the `knowledgebase` module boundary is unchanged — the suite compiles only
  against the closed `knowledgebase` value types plus `java.util.*` (no new edge, no ADR).

### Not changed
The row binding of `documents` to the verified `TenantContext`, the real `(tenant_id, id)` read that
enforces cross-tenant isolation, the object storage client (`S10-03`), the append-only **audit event**
the upload must write (needs the `knowledgebase → audit` edge, an ADR) and the no-public-URL enforcement
(the private-by-bucket policy from `S10-02`) are the rest of the `secure upload flow`; they complete when
their schema / persistence / wiring land (`S10-05` … `S10-06`) and are not on disk yet, so they are not
tested here. Related changelog entry: **Tenant-safe file upload (S10-04)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
## S10-01 — Define the document lifecycle (status state machine of `knowledgebase`).


### What changed
One architecture document, [`docs/architecture/document-lifecycle.md`](../architecture/document-lifecycle.md), is the single source of truth for `documents.status`: the set of states, which are terminal, the state diagram (Mermaid `stateDiagram-v2`), the per-transition event/command + guard table, and the wider-system interaction. It transcribes the already-fixed state set from the domain glossary (`docs/domain-glossary.md` §7 — `UPLOADED → QUEUED → PROCESSING → INDEXED`, with `FAILED`, `DELETE_REQUESTED`, `DELETED`) into the same state-machine form the run machine uses (`docs/architecture/run-state-machine.md`, `S05-01`), so the two documents read the same way. The design:

- **States.** `UPLOADED` (object stored + metadata row committed; the upload API returns `202`), `QUEUED` (the `ingestion-requested` event is enqueued via the outbox, the document awaits a worker), `PROCESSING` (an ingestion worker holds the lease and is extracting → chunking → embedding → indexing), `INDEXED` (all chunks indexed — pgvector + FTS), `FAILED` (permanent failure or retry budget exhausted), `DELETE_REQUESTED` (deletion requested, the lease is drained), `DELETED` (object + chunks removed). **Terminal:** `INDEXED`, `FAILED`, `DELETED` — all absorbing.
- **Transitions (7).** (1) `UPLOADED → QUEUED` on the enqueued `ingestion-requested`; (2) `QUEUED → PROCESSING` on `worker.claim` (a lease, tenant re-checked against PostgreSQL); (3) `PROCESSING → INDEXED` on the `document.indexed` event; (4) `PROCESSING → QUEUED` — the only **retry** edge, a bounded transient re-queue; (5) `PROCESSING → FAILED` — permanent failure or an exhausted retry budget; (6) any non-terminal / `FAILED` state → `DELETE_REQUESTED` on the `DELETE /api/v1/documents/{documentId}` command; (7) `DELETE_REQUESTED → DELETED` once the object and chunks are gone.
- **Design points.** Deletion is two-phase and one-way (the run's `CANCEL_REQUESTED → CANCELLED` mirror); a `PROCESSING` delete drains cooperatively and never overwrites a completed result (optimistic `version` + `lease_epoch` fencing); retry is bounded (the only backward edge) and redelivery is idempotent (at-least-once, keyed by `tenant_id + sha256 + pipeline_version`); every transition goes through the single `knowledgebase` transition service (conditional update + optimistic lock + append-only audit record in one transaction), never set directly from a controller or listener; the `ingestion` worker leases + indexes + publishes `document.indexed`, but `knowledgebase` owns and applies the status.

### Why
The `S10-01` acceptance: every transition is explicitly described and the retry/terminal states are defined. The knowledge base (`knowledgebase`) and the ingestion pipeline (`ingestion`) are being built across `S10-03` … `S10-13`, but a document's lifecycle had no diagram or transition table yet — only the seven state names in the glossary. The upload enqueue, the ingestion worker, the delete command and the indexed event already exist as events (`docs/architecture/events/README.md`), so this is a documentation task that fixes the state machine before the code that implements it, exactly as `S05-01` fixed the run machine before `S05-10`. No behaviour, interface or design choice is left open beyond the standard state-machine shape the plan and glossary already dictate, so `brainstorming` is skipped.

### Verification
- **Link integrity.** Every in-document link resolves to a document that exists and is on-topic: `run-state-machine.md`, `step-state-machine.md`, `events/README.md`, `durable-execution.md`, `error-taxonomy.md`, `domain-glossary.md`, `module-map.md` — all confirmed present at the referenced path; the downstream `S10-03` … `S10-13` are referenced as planned code, not linked (a not-yet-existing file is never linked, per `docs/AGENTS.md`).
- **In sync with the source of truth.** The seven states and the `UPLOADED → QUEUED → PROCESSING → INDEXED` spine match the glossary §7 verbatim; the `document.indexed` payload carrying `status = INDEXED` (`docs/architecture/events/README.md`) matches transition 3; the `tenantId:documentId` partition key and the two document topics match `events/README.md` / `kafka-topics.md`; the one-transition-service / absorbing-terminal / two-phase-cancellation rules match `run-state-machine.md` and the root invariants.
- **Well-formed.** `git diff --check` clean on all touched files; the two index tables (`docs/README.md` tree, `docs/architecture/README.md` documents table) and the glossary table row were diff-reviewed to confirm the leading pipe count is unchanged (a context-aware patch had doubled a leading `|` and was corrected before finishing).
- **Not a build.** This is a documentation-only change (`S10-01`), so there is no `./gradlew check` gate to point to (the state machine enum, the transition service and the upload/delete API do not exist yet — they are `S10-03` … `S10-13`). No build or test was run; the verification is link integrity + `git diff --check` + the glossary/events cross-check above, per `docs/AGENTS.md` ("for a documentation-only change there is often no CI gate yet — verify by resolving every in-document link … `git diff --check`, and a diff review").

### Not changed
The code, the `documents` schema (S10-05), the upload/delete API, the ingestion worker and the events are untouched — this is documentation only; the enum + transition service they will implement are not on disk yet.

## S10-02 — Storage config and object-key convention.


### What changed
Two additions in the `knowledgebase` module (object storage is a `knowledgebase` concern per `docs/architecture/module-map.md`), both DB-free, both part of the S10-02 deliverable: a typed, validated `agentflow.storage.*` configuration group, [`AgentFlowProperties.Storage`](../../apps/agentflow-app/src/main/java/com/example/agentflow/AgentFlowProperties.java), and the server-generated, tenant-scoped object-key convention [`DocumentObjectKey`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKey.java) in `knowledgebase.domain`.

- **The config group** describes the S3-compatible bucket the objects live in — which bucket (`bucket`, default `agentflow`, matching the Garage bucket in `infra/compose` + `ADR-013`), where (`region`, default `garage`), the policy (`public-access`, `disabled` by default — the bucket is private, an object is never addressable through a public or anonymous URL) and the endpoint (`endpoint`, environment-specific, supplied per profile by the S3 client built in `S10-03`; a `null` endpoint fails clearly when that client is built, never silently). It binds the whole `agentflow.storage.*` tree, mirrors `AgentFlowProperties` with the same `@Validated` + `@ConfigurationProperties` binding, `@Valid` on the nested group and `@Autowired(required = false)`-style defensiveness. The S3 credentials (`AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY` / `AGENTFLOW_OBJECT_STORAGE_SECRET_KEY`) come from the environment only, never from configuration or source (invariant 8).
- **The object-key convention** is `documents/<tenantId>/<documentId>[.<ext>]` — generated **server-side** from the server-issued identifiers, never from the client `filename` and never a path the filename controls (invariant 1 / the S10-04 key-generation acceptance). The only separators are the fixed `documents/` prefix plus the two server UUIDs, and an extension (when present) is a content-type hint only, validated against a closed whitelist and never a path segment — so a hostile or prompt-injected `filename` can never become an object path or leak across tenants.


### Why
`S10-02` provides the storage configuration and the object-key convention the future ingestion / upload path (`S10-03` … `S10-13`) builds on: without a typed, validated group the endpoint / bucket / region would be magic numbers, and without a server-generated key there is no guarantee that an object's path is tenant-scoped or that a hostile `filename` cannot become a path. The private-by-bucket policy, the env-only credentials, and the server-generated key are the S10-02 acceptance — the parts that must be right before any payload is written. They are captured now as a DB-free value type and a validated config group (no `DataSource` / S3 dependency), so no Testcontainers integration test is needed at this stage; the real S3 client and ingestion round-trip are `S10-03`.


### Verification
- **The key is generated server-side from the ids, never from the filename** — [`DocumentObjectKeyTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentObjectKeyTest.java) (9 tests, JUnit XML checked: `tests="4"` + `tests="2"` + `tests="3"`): server-side generation, tenant-scoping, the exact canonical shape (3 segments, no `..`, exact UUIDs) and rejection of a hostile `filename`;
- **The config binds the safe defaults** — [`StoragePropertiesTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/StoragePropertiesTest.java) (5 tests): private-by-default, no public URL, custom `endpoint` / `bucket` / `region` bind and an invalid value fails startup through the validated binding;
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + formatting + OpenAPI gates); `git diff --check` clean; the `knowledgebase` module boundary is unchanged (it still compiles only against `ai` / `workflowexecution`).


### Not changed
The S3 client, the credentials binding and the ingestion / upload wiring are `S10-03`; they are not on disk yet. Related changelog entry: **The storage config and object-key convention (S10-02)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).

## S10-03 — Document upload validation (accept/bound layer).


### What changed
The accept/bound checks the `POST /v1/documents` upload API runs before a single byte is written to
object storage and before a single `documents` metadata row is committed. This is the whole of `S10-03`
that is fully verifiable today: it needs no storage backend, no `documents` schema (S10-05) and no
ingestion worker (S10-03 … S10-13) — only the configured limits — so it is the safe core the upload API
can drive from an early API and unit-test in isolation, exactly as `S10-02` gave a usable value type and
a validated config group before the real S3 client. The pieces:

- **Config** — `agentflow.upload.*` on [`AgentFlowProperties.Upload`](../../apps/agentflow-app/src/main/java/com/example/agentflow/AgentFlowProperties.java): `max-bytes` (default `10485760`, 10 MiB), `max-filename-length` (default `255`), `max-media-duration-seconds` (default `300`, 5 minutes; media types only). Bound with the same `@Validated` + `@ConfigurationProperties` path as the other `agentflow.*` groups; a value below the minimum fails boot. Documented in [`application.yml`](../../apps/agentflow-app/src/main/resources/application.yml) and bound by [`DocumentUploadConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/config/DocumentUploadConfiguration.java), following the `ToolExecutionConfiguration` precedent (the validator is a value-bound bean built from the config group, never a `@Component`).
- **Domain** — [`DocumentContentType`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentContentType.java): the closed allowlist (application/pdf, image/png, image/jpeg, image/gif, image/bmp, audio/wav, text/plain, text/markdown, text/csv, text/richtext, application/json, application/xml, text/xml); `from(String)` rejects anything not on it (an untrusted header cannot expand the surface), `matches(byte[])` verifies the real leading bytes — magic bytes, RIFF/WAVE or a light text check — so a payload that misrepresents itself is rejected, `isMedia()` flags the duration-bounded types, `isPermitted(String)` is the non-throwing allowlist probe; [`FileNameSanitizer`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizer.java): strips any path/traversal/control character to a bare base name, rejecting a blank, `.`-only or `..`-only input so the filename can never become an object path (invariant 1 / `S10-04`); [`MediaDuration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/MediaDuration.java): decodes the WAV duration from the header (RIFF/WAVE + fmt/data) so it can be bounded; [`DocumentUploadLimits`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentUploadLimits.java): immutable domain view of the three limits, constructor validation; [`DocumentUploadStatus`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/domain/DocumentUploadStatus.java): the upload lifecycle state (UPLOADED → QUEUED → PROCESSING → INDEXED, FAILED, DELETE_REQUESTED, DELETED).
- **Application** — [`DocumentUploadValidator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidator.java): runs the checks in reject-before-persist order — filename → content type → size → magic bytes → media duration → SHA-256 checksum — and every rejection is one [`DocumentUploadValidationException`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidationException.java) (the HTTP layer maps it to a `400`, `common.ErrorClass`); the payload bytes are never written by this call. [`ValidatedUpload`](../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/ValidatedUpload.java): the safe filename, canonical type, size, checksum and (for media) duration the row carries.


### Why
`S10-02` gave the storage configuration and the object-key convention the upload path builds on; `S10-03`
gives the upload API the accept/bound layer it needs before it ever reaches storage. The limits are the
first line of defense against oversized / hostile uploads and a prompt-injected content type, and they are
the S10-03 acceptance: an oversized or unsupported upload is rejected without leaving a dangling metadata
row or object. They are captured as pure, DB-free value types and a validated config group (no `DataSource`
/ S3 dependency), so no Testcontainers integration test is needed at this stage — the real S3 client, the
`documents` schema and the ingestion worker are `S10-03` … `S10-13`. No behaviour, interface or design
choice is left open beyond the closed allowlist / three limits / reject-before-persist order the plan and
invariants already dictate, so `brainstorming` is skipped.


### Verification
- **The closed allowlist and magic-byte verification** — [`DocumentContentTypeTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentContentTypeTest.java): `from` resolves a declared type, strips parameters and rejects anything not on the allowlist; `matches` verifies the real leading bytes for a PDF, PNG and WAV (a payload that misrepresents itself is rejected), an empty payload never matches, a JSON payload needs a structural character; `isMedia()` / `isPermitted()`.
- **The filename can never carry a path or traversal** — [`FileNameSanitizerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/FileNameSanitizerTest.java): a plain name is returned, a path is reduced to its trailing segment, a blank/null / null-byte / control-char / `..`-only input is rejected, and `../secret` / `../../etc/passwd` are sanitized to their safe base rather than trusted.
- **The WAV duration decode** — [`MediaDurationTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/MediaDurationTest.java): duration equals `data / byte_rate`, and a non-WAV payload returns empty.
- **The limits are a validated value** — [`DocumentUploadLimitsTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/domain/DocumentUploadLimitsTest.java): value equality by the three fields, a non-positive limit is rejected.
- **The reject-before-persist contract** — [`DocumentUploadValidatorTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/application/DocumentUploadValidatorTest.java): the accept case (safe filename, canonical type, size, real SHA-256 checksum, media duration) and every reject case — oversized, unsupported content type, tampered magic bytes, over-long filename, traversal name, over-long media, undecodable media, null payload — each throwing `DocumentUploadValidationException`.
- **The config binds the safe defaults** — [`UploadPropertiesTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/UploadPropertiesTest.java): the `agentflow.upload.*` group binds the defaults, custom values override them and a negative value fails startup through the validated binding.
- `:apps:agentflow-app:check` green (unit + architecture + no-cycles + formatting + OpenAPI gates); `git diff --check` clean; the `knowledgebase` module boundary is unchanged — the accept/bound layer compiles only against the closed `knowledgebase` value types.


### Not changed
The S3 client, the credentials binding, the `documents` schema (S10-05), the ingestion worker and the upload/delete API are `S10-03` … `S10-13`; they are not on disk yet. The upload validation layer is a `knowledgebase` concern; it writes nothing and owns no row, so it changes no existing module. Related changelog entry: **The document upload validation layer (S10-03)** in [`CHANGELOG.md`](../../CHANGELOG.md) (Unreleased → Added).
