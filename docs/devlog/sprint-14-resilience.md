# Sprint 14 — Resilience, security testing and load baseline

Detailed per-task changelog records for the resilience/security/load thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S14-03 — cross-tenant regression tests](#s14-03--cross-tenant-regression-tests)
- [S14-02 — Testcontainers suite stability](#s14-02--testcontainers-suite-stability)
- [S14-01 — failure matrix](#s14-01--failure-matrix)

---

## S14-03 — cross-tenant regression tests

### What changed

The cross-tenant regression surface for the endpoint and data layers was closed out in two seams:

**HTTP boundary (MockMvc, the fast unit tier)** — the four missing 404-masking cases the existing
suite only covered partially:

- [`WorkflowRunControllerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/workflowexecution/web/WorkflowRunControllerTest.java) (10 tests) — `GET /api/v1/runs/{runId}` and `POST /api/v1/runs/{runId}/cancel` on a run owned by another tenant now render a `404 not_found` exactly like a missing run: the body carries no `id` / `workflowId` / `attempt` (the run's identity), the problem only carries the standard envelope, and the service is invoked with the configured tenant (never anything from the request). The nested-resource case (`GET /api/v1/runs/{runId}/attempts`) was already covered and asserts the same.
- [`ApprovalControllerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/workflowexecution/web/ApprovalControllerTest.java) (9 tests) — `POST /api/v1/runs/{runId}/approvals/{approvalId}` on a cross-tenant approval, on both the `approve` and the `reject` path, renders the identical `404 not_found` problem and no resource field (`approvalId` / `runId` / `stepKey` / `decisionActor` / `decisionComment`), with the service invoked under the configured tenant.

**Storage layer (Testcontainers, real `pgvector/pgvector:0.8.6-pg18`)** — two new suites close the data-side gaps; each uses its own seeded tenant and rows, so the suites are independent:

- `AuditRepositoryIT` (new) — inserts a row for the owner tenant and reads it back with its fields; a foreign tenant's read (or any missing id) throws the same `EmptyResultDataAccessException`, so the caller cannot tell a foreign audit record from a missing one (invariant 1, the 403/404 masking policy).
- `UsageRepositoryIT` (new) — inserts a one-attempt `MODEL` measurement for the owner tenant, reads its run's measurements back with the same values, and a foreign tenant's read returns the same empty list as a missing run — indistinguishable from a missing one.

The new integration suite also exposed a genuine latent bug: `UsageRepository.insert` bound the `provider_metadata` column — `jsonb` in the migration — as a plain string, so **every** insert against a real PostgreSQL failed with `BadSqlGrammarException` ("column provider_metadata is of type jsonb but expression is of type character varying"). No existing test exercised the insert on a real database, so this shipped. The fix is a single cast in the INSERT — `CAST(? AS JSONB)` on the metadata parameter — keeping the bind contract (`setString`) intact; unit-test binds remain unchanged.

### Why

`S14-03` is the P0 Sprint-14 task: the acceptance criterion is "чужой tenant resource нельзя получить ни через endpoint, ни через retrieval" (a foreign tenant resource cannot be obtained neither via endpoint nor via retrieval). The endpoint and data layers were the two halves of that criterion and the two seams above close them: every resource in the matrix (definitions, runs, results, documents, chunks, approvals, usage, audit) now has a cross-tenant test at the tier it actually reaches — endpoint for the four controllers, repository for the data-only modules (usage, audit) — plus the nested-resource case (run attempts, approvals under their run) and the error-disclosure check (the 404 body carries no resource identity). The existing suite already covered definitions, documents, chunks (vector + lexical) and the tool-invocation read path; those were not re-written.

### Verification

- `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL (the fast gate: unit tests, architecture gate, spotless, OpenAPI baseline).
- `./gradlew :apps:agentflow-app:integrationTest` — BUILD SUCCESSFUL, **37 suites / 179 tests, 0 failures, 0 errors, 0 skipped** (35/175 → 37/179: the two new suites and their four tests). JUnit XML read for the counts; `AuditRepositoryIT` (2 tests) and `UsageRepositoryIT` (2 tests) verified green individually, then the whole tier re-run green.
- The fixed production path was also proven by the new `UsageRepositoryIT` green run (insert + read round-trip on the real database) — the bug is no longer live.
- Unit XML (`WorkflowRunControllerTest` 10/0, `ApprovalControllerTest` 9/0) verified by grep, not assumed.

### Docs

- `docs/devlog/sprint-14-resilience.md` — this file (S14-03 section + contents line).
- `CHANGELOG.md` — Unreleased / Added entry.

### Scope / follow-up

The DLQ inspection endpoints are an operator surface (`ops:dlq`-gated, not tenant-scoped — a cross-tenant distinction does not exist by design, per the controller contract), so no tenant test is added there. Usage and audit remain data-only: they have no HTTP surface, so their tenant boundary is proven at the repository/storage layer, not through an endpoint. `S14-04` (duplicate/reordered events) and the rest of the Sprint-14 thread remain out of scope.

## S14-02 — Testcontainers suite stability

### What changed

The unit/integration split is now machine-enforced in both directions instead of a convention. The fast `test` gate excludes `*IT` classes (`filter { excludeTestsMatching("*IT") }`), so a stray integration class in the unit source set can never be discovered there; `integrationTest` runs the separate `src/integrationTest` source set unaltered and additionally excludes the opt-in `real-provider` tag (that tag stays opt-in via `realProviderContractTest`, which needs `OPENAI_API_KEY`). Both tasks keep `failOnNoDiscoveredTests = true`, so an empty suite fails the build rather than silently passing.

Per-test data isolation and cleanup, no flat sleeps:

- `OutboxPublisherBenchmarkIT` (one shared container per class) now deletes the `outbox_events`/`tenants` rows it seeds in `@AfterEach` and closes its pool there — previously the seeded rows accumulated across the class, so a repeated run saw stale rows from a prior test.
- `RedisSmokeTest` deletes the `greeting`/`session` keys it writes in `@AfterEach`.
- `RedisRateLimiterIT` (one shared container per class, fixed `TENANT` + key across the class) flushes the namespace in `@AfterEach`, via a fresh live connection so the flush runs even when the test wired a dead one — the test's own `@AfterEach` factory close is unaffected.
- The one remaining flat `Thread.sleep(1)` in the drain benchmark's poll loop is removed. The loop was already a bounded readiness condition (`rows < DRAIN_ROWS` and `polls <= 1_000`, asserting the delivered count, the per-row count and that no rows remain pending); it needed no artificial tick. Across the suite, the only remaining waits are explicit bounded polls with a real assertion (e.g. `WorkflowWorkerCrashRecoveryIT`) or the JUnit container-extension readiness waits.

### Why

`S14-02` is a P0 stability task: "suite проходит несколько раз подряд без flaky timing failures". The split, the cleanup and the readiness-over-sleep contract make determinism a structural property rather than a matter of hope — and keep `check` free of Docker while `integrationTest` stays the one Docker-backed tier.

### Verification

- `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL (the fast gate: unit tests with the new `*IT` exclusion, architecture gate, spotless, OpenAPI baseline).
- `./gradlew :apps:agentflow-app:integrationTest --rerun-tasks` — BUILD SUCCESSFUL, 35 suites / 175 tests, 0 failures, 0 errors, 0 skipped. This was run three times in a row (all green), so the result is not a one-off.

### Docs

- `README.md` — the Tests section: the split, the per-class container pattern and the readiness/isolation contract.
- `CHANGELOG.md` — Unreleased / Changed entry.
- `docs/devlog/sprint-14-resilience.md` — this file (S14-02 section + contents line).

### Scope / follow-up

Cross-tenant regression (`S14-03`), duplicate/reordered events (`S14-04`), the load-test baseline (`S14-05`) and the test-strategy document (`S14-17`, `docs/testing/README.md`) stay out of scope. The four fault-injection workflow classes (`WorkflowWorkerCrashRecoveryIT`, `WorkflowWorkerLimitsIT`, `WorkflowWorkerEndToEndIT`, `WorkflowCancelEndToEndIT`) keep their manual `@BeforeAll`/`@AfterAll` container management — they are per-class containers, identify their rows by random `UUID`s, and use bounded readiness polls; they are intentionally not given a `@Container`/`@Testcontainers` extension in this pass.

## S14-01 — failure matrix

### What changed

The platform now has a single operational record of how each critical dependency fails — [`docs/testing/failure-matrix.md`](../testing/failure-matrix.md). One outage scenario per critical dependency, with the failure injection, the expected behaviour, the recovery target, the observable metric/alert and the concrete evidence (test / runbook / live check) behind each claim. The six critical dependencies covered are: **PostgreSQL**, **Kafka**, **Redis**, **object storage** (Garage / S3), the **AI provider** and the **worker role** (workflow-worker / ingestion-worker).

Each component is documented in a compact overview table plus a per-component section with the six required columns. Two gaps are recorded in the matrix's [Known gaps](../testing/failure-matrix.md#known-gaps) instead of being papered over: the dedicated live PostgreSQL-outage integration test is `S14-07`, and the object-storage client is not wired yet (`ADR-014`), so the S3 outage path is verified only up to the port boundary — no live `docker stop agentflow-garage` behaviour is asserted.

### Why

Sprint 14's gate is "отказоустойчивость покрыта automated tests" — every critical dependency must have an outage scenario someone can check. The matrix is the honest map of what is proven today (atomicity, idempotency, fail-open limiter, bounded retries, lease recovery) and what is still planned (live PostgreSQL outage, a wired object-storage client), so each `S14-*` task closes its row rather than the matrix claiming a guarantee it cannot show. No code or contract changed — documentation only, so `CHANGELOG.md` records the documentation addition and no runtime behaviour.

### Verification

Documentation-only change; no build or test gate applies to a new markdown file. The verification is the documentation-verification procedure:

- `git diff --check` — whitespace / final newline / trailing-whitespace.
- Every relative in-document link in the new/touched files was resolved against the file that owns it (the documentation-verification resolver) — no dead links; planned targets are plain code paths with a `⚠ planned` badge, never links.
- `git status --short` — the change touched only the new doc, the index, the devlog and `CHANGELOG.md`.

Every cited evidence test was inspected in the tree before being cited (name and package confirmed), and every metric / alert UID, threshold and `for` duration in the matrix was taken from `infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml` and `docs/observability/metrics.md` — none is invented.

### Documentation

- [`docs/testing/failure-matrix.md`](../testing/failure-matrix.md) — new: the matrix (overview table + per-component sections + known gaps).
- [`docs/README.md`](../README.md) — the `testing/` tree entry and the two table rows (the failure matrix, and the `S14-17` test-strategy marker).
- [`docs/devlog/sprint-14-resilience.md`](sprint-14-resilience.md) — this file.
- [`docs/devlog/README.md`](README.md) — the new sprint devlog link.
- `CHANGELOG.md` — Unreleased / Added entry.

### Scope / follow-up

The matrix is deliberately not a test suite: each row is an honest "proven / unproven" statement, and each `S14-*` task (`S14-02` … `S14-08`) closes its own row — `S14-07` for the PostgreSQL outage scenario, the Sprint 10 object-storage client work for the Garage outage scenario, `S14-03` for cross-tenant regression and `S14-08` for the Redis per-function outage table. The `S14-17` test strategy document (`docs/testing/README.md`) will be the entry point that consolidates this matrix with the rest of the testing strategy.
