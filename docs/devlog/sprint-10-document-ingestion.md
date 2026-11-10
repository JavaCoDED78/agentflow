# Changelog


# Sprint 10 — Document ingestion, pgvector, hybrid retrieval and citations


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
