# Sprint 14 — Resilience, security testing and load baseline

Detailed per-task changelog records for the resilience/security/load thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S14-11 — dependency/secret/container scanning](#s14-11--dependencysecretcontainer-scanning)
- [S14-10 — upload/parser abuse regression pack](#s14-10--uploadparser-abuse-regression-pack)
- [S14-09 — provider error matrix](#s14-09--provider-error-matrix)
- [S14-08 — Redis outage verification](#s14-08--redis-outage-verification)
- [S14-07 — PostgreSQL outage / unknown commit](#s14-07--postgresql-outage--unknown-commit)
- [S14-06 — concurrent lease claims](#s14-06--concurrent-lease-claims)
- [S14-05 — broker restart and publisher crash](#s14-05--broker-restart-and-publisher-crash)
- [S14-04 — duplicates and reordered events](#s14-04--duplicates-and-reordered-events)
- [S14-03 — cross-tenant regression tests](#s14-03--cross-tenant-regression-tests)
- [S14-02 — Testcontainers suite stability](#s14-02--testcontainers-suite-stability)
- [S14-01 — failure matrix](#s14-01--failure-matrix)

---

## S14-11 — dependency/secret/container scanning

### What changed

A new blocking security gate: three CI jobs (`secret-scan`, `dependency-scan`, `container-scan`) plus a single deterministic waiver gate, wired into `.github/workflows/security.yml` (PR + push to `main`, `contents: read` only, no untrusted input).

**Scanner config.** `.gitleaks.toml` extends the built-in default rules (`[extend] useDefault = true`) with **two narrow, documented exclusions**, never a whole directory: the sanctioned test-fixture files carrying clearly-fake dummy values (`CorrelationIdFilterTest.java`, `SecretRedactorTest.java` — scoped by exact path) and one historical `.env.example` local-dev placeholder (a minio access/secret pair committed at `7c162de`, already removed from the current file — scoped to that one commit **and** path via `condition = "AND"`, so a future real secret in `.env.example` is still caught). The two exclusions were verified empirically against the full-history scan: with the fixtures-only config the historical finding reappears (1 leak), and with both configs the scan is clean.

**Waiver gate.** `scripts/security/check-waivers.py` (stdlib, no dependencies, `security/waivers.json` as the registry) reads a Trivy JSON report (`fs` or `image` mode) and fails (exit 1) unless every finding at or above the blocking severity (`CRITICAL,HIGH`) is covered by a valid, unexpired waiver. It fails closed (exit 2) on an unreadable report or a malformed registry: missing required fields (`id`, `reason`, `approver`, `expires_at`), a duplicate id, a malformed or non-ISO `expires_at`, or a non-future expiry. Expired waivers fail the gate (an exception can never silently become permanent). Waivers match on the CVE id only; they cover **vulnerability** findings **only** — secret findings are never waived and always fail. The gate is the single gate: trivy is run with `--exit-code 0` so the report always reaches it.

**Registry.** `security/waivers.json` — empty by design: the handful of findings that exist today (13 CRITICAL, 20 HIGH, all in the `gradle.lockfile` pins) are not waived. Waivers require human approval by a named approver; they are never added by an agent.

**Local equivalent.** `scripts/security/run-local-scans.sh` (bash, repo-root relative) runs gitleaks over the full history (`--redact`), then Trivy fs over the lockfiles (`--exit-code 0`), then the waiver gate. Requires `trivy` and `gitleaks` in PATH or via `TRIVY_BIN` / `GITLEAKS_BIN`; `trivy-vulns.json` and `trivy-image.json` are git-ignored artifacts.

**Container job (dormant).** `container-scan` is wired with `if: hashFiles('infra/docker/**/Dockerfile*') != ''` — no Dockerfile exists yet (S15-01), so the job stays skipped until one lands; it then builds the image and scans it with the same waiver gate. This is the honest state of "critical findings break CI" for the container side.

### Why

`S14-11` is the P0 Sprint-14 task: "запускать vulnerability scanner, secret scan и Trivy; задать блокирующую severity policy; для ложных срабатываний требовать срок/обоснование waiver." Sprint 14's gate is "critical/high security findings устранены или задокументированы" — before this change there was no security gate at all (no workflow, no scanner, no waiver mechanism), so a CVE-bearing dependency could merge silently. The `.github/workflows/security.yml` split between the secret job and the vulnerability gate mirrors the plan's supply-chain line (§14.2: dependency vulnerability scan + secret scan; Trivy for the container) and the acceptance criterion ("critical findings break CI либо имеют явное одобренное исключение").

### Verification

- `.gitleaks.toml` — full-history scan clean: `gitleaks git --redact --no-banner --config .gitleaks.toml .` → "no leaks found" (278 commits), exit 0. Without the two exclusions, the historical `.env.example` minio placeholder reappears (1 finding) — the exclusions are scoped exactly as documented.
- `scripts/security/check-waivers.py` — against the real Trivy fs report (33 findings: 13 CRITICAL + 20 HIGH): exit 1 with every finding listed (13 CRITICAL, 20 HIGH), waived-nothing. Pass-path cases verified against synthetic reports: both-waivers-active → exit 0 (PASS); one expired waiver → exit 1 (expired warning + the covered finding reported unwaived); malformed registry (missing fields) → exit 2; duplicate id → exit 2.
- `bash scripts/security/run-local-scans.sh` (with `TRIVY_BIN` / `GITLEAKS_BIN` pointed at the pinned tools) — gitleaks clean → Trivy produces the report → the waiver gate exits 1 as it must today (the 33 unwaived findings block).
- Workflow YAML parses (PyYAML 1.1 quirk: the top-level `on:` key reads as boolean `True` — a PyYAML 1.1 artifact; the keys/jobs/`if:` are correct and GitHub parses `on:` natively).
- Workflow structure: `secret-scan` (fetch-depth 0, redacted), `dependency-scan` (locked install + fs report + waiver gate), `container-scan` (dormant until a Dockerfile exists).

### Scope / follow-up

The 33 current findings are **not fixed** — fixing is a dependency-bump PR (jackson-core/databind, tomcat-embed-core, tika-core / tika-parser-pdf-module, bcprov-jdk18on, lz4-java, snappy-java) with a re-lock and a re-run; accepting any finding via waiver needs human approval of the waiver itself. The container image scan activates with S15-01; CycloneDX SBOM is not in this task's scope. The one remaining gap in the gate: the fs scan covers the `gradle.lockfile` pins only, so OS-layer and OCI-image findings are covered by the container job once it is live, and the fs report is a point-in-time snapshot — a new finding after the last gate run is only visible in the next PR/push.

---

## S14-10 — upload/parser abuse regression pack

### What changed

One new P0 upload/parser abuse regression pack, [`UploadParserAbuseSecurityPackTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/parser/UploadParserAbuseSecurityPackTest.java) (12 tests, DB-free, direct construction — the same pattern as `IngestionServiceTest` / `TikaDocumentParserTest` / `BoundedDocumentParserTest`), plus the wall-clock bound it exercises.

**Production (one new bounded parser + one new classified failure code + one new config bound):** a new `BoundedDocumentParser` (`ingestion.application.parser`) wraps the real `TikaDocumentParser` and bounds the wait by a wall-clock limit — the underlying PDFBox/Tika parse is CPU-bound and uninterruptible, so a timeout cannot stop the work, it stops the *wait*: the caller returns `Status.FAILED` carrying `ExtractionError.Code.TIMEOUT` ("extraction exceeded the time limit of 60s") instead of an unbounded pin. The abandoned daemon thread holds no locks and the parser is stateless, so it is bounded by the payload size and the page / character caps the engine already enforces; Spring shuts the executor down on context close (`@PreDestroy`). One new `ExtractionError.Code.TIMEOUT` (the terminal, non-retryable, failure class alongside `CORRUPT` / `ENCRYPTED` — it is never a transient) and one new `agentflow.ingestion.max-extraction-seconds` bound (default 60, validated at `@Min(1)` — a nonsensical bound fails boot, mirroring `max-pages` / `max-chars`). `DocumentExtractionConfiguration` now wires the bean as `BoundedDocumentParser` → `TikaDocumentParser`.

**What the pack proves (acceptance criterion: no unsupported file becomes `INDEXED` and no abuse bypasses a resource limit):**

- **Upload boundary** (`DocumentUploadValidator`, the only reject-before-persist gate): oversized payload rejected; a MIME outside the allowlist rejected; magic bytes contradicting the declared type rejected; traversal filename reduced to the safe base name (`../../etc/passwd` → `passwd`); a traversal-only filename rejected outright.
- **Parser boundary** (`TikaDocumentParser`, real engine): a corrupt PDF classified `CORRUPT` (never EXTRACTED); an encrypted PDF classified `ENCRYPTED`; excessive extracted text capped at the configured character limit (truncated, never beyond).
- **Bounded parser** (`BoundedDocumentParser`): a hanging extraction is abandoned at the configured bound and reported as `TIMEOUT` within the bound — the test asserts the caller returns while the delegate is still parked, never an unbounded wait.
- **Pipeline end** (the real `IngestionService` with the real parser and mocked `knowledgebase` boundary — no fake parser, no fake outbox): a corrupt PDF, an encrypted PDF and a timeout extraction each end `FAILED` and never become `INDEXED` — `markFailed` with `EXTRACTION_CORRUPT` / `EXTRACTION_ENCRYPTED` / `EXTRACTION_TIMEOUT` carries a non-blank safe summary, and `markIndexed` / `chunkIndexingService.index` / `outboxService.enqueue` are all never called. The timeout pipeline pin uses a stub parser returning the typed `TIMEOUT` result (a wall-clock timing pin is already proven in `BoundedDocumentParserTest`) — every other row drives the real engine and the real classification.

Also new: [`BoundedDocumentParserTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/parser/BoundedDocumentParserTest.java) (5 tests — constructor validation, success pass-through, the runaway abandon, the delegate-fault propagation, executor shutdown) and [`DocumentExtractionConfigurationTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ingestion/application/config/DocumentExtractionConfigurationTest.java) (3 tests — the bean is the bounded wrapper around the real Tika engine, a configured bound is accepted, a non-positive bound fails the wiring).

### Why

`S14-10` is the P0 Sprint-14 task: "oversized file, неверный MIME, corrupt/encrypted PDF, path traversal filename, чрезмерный размер extracted text и parser timeout." The resource bounds were already in place (`max-pages` / `max-chars`, S10-07; the upload limits `max-bytes` / `max-file-name-length` / `max-media-duration-seconds`, S10-03) and the validator already rejected the oversized/MIME/magic-byte/traversal rows — but no test proved the acceptance criterion end to end, and the one real gap was a missing timeout: nothing bounded the wall-clock time a single extraction may take, so a pathological document could pin an ingestion worker thread indefinitely. The new `BoundedDocumentParser` closes exactly that gap with a configuration bound, a terminal classification, and a regression pack that exercises the whole abuse space at the two gates an upload actually passes through plus the pipeline end.

### Verification

- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ingestion.application.parser.BoundedDocumentParserTest' --tests 'com.example.agentflow.ingestion.application.parser.UploadParserAbuseSecurityPackTest' --tests 'com.example.agentflow.ingestion.application.config.DocumentExtractionConfigurationTest'` — BUILD SUCCESSFUL; XML-verified: `BoundedDocumentParserTest` **5 tests, 0 failures**; `UploadParserAbuseSecurityPackTest` **12 tests** (5 upload boundary + 3 parser boundary + 1 bounded parser + 3 pipeline end, 0 failures); `DocumentExtractionConfigurationTest` **3 tests, 0 failures**.
- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ingestion.*'` — BUILD SUCCESSFUL: whole `ingestion` module — **104 tests, 0 failures, 0 errors** (XML-verified; `IngestionServiceTest` 12/12, `IngestionConfigurationTest` 5/5, config/wiring tests 8/8).
- `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL (the background gate; see the final report for the real exit code).

### Scope / follow-up

The abandoned in-flight parse thread is not killable (CPU-bound, uninterruptible) — it is a daemon, bounded by the payload size and the page/character caps, holds no locks and terminates on its own. A hard cancellation (interruptible work) would require making the engine interruptible — explicitly out of scope for this task. The pipeline timeout pin uses a stub parser for the typed result so the wall-clock timing stays deterministic; the classification itself is the same production one the real parser emits. No production behavior changed for a file that parses within the bound.

---

## S14-09 — provider error matrix

### What changed

One new test suite, [`ProviderErrorMatrixTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/ProviderErrorMatrixTest.java) (7 tests, DB-free, direct construction — the same pattern as `ResilientChatModelTest`), plus one new fixture on [`FakeModelProvider`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/FakeModelProvider.java): the **`SERVER_ERROR`** fixture — the missing half of the provider error space. It reproduces an HTTP 5xx exactly like an SDK fault does (an `OpenAIServiceException` with status code 500, the same anonymous-subclass technique the 429 fixture uses), so the production `ProviderExceptionClassifier` maps it to `SERVER_ERROR` and the planner maps it to `TRANSPORT_FAILURE`, exactly like a live 500. One new `5XX`/`500` config alias alongside the existing `429` alias; the accepted-mode list in the config error and the javadoc updated to match, and `FakeModelConfigurationTest` gained a `server-error`/`500` config-wiring case (5 → 6 tests) pinning the alias through the `ApplicationContextRunner`.

The matrix drives every row of the error space through the **full production seam** — `PlannerService` → `ResilientChatModel` (bounded retry + concurrency cap, S09-09) → the deterministic fake provider — with the real `ProviderExceptionClassifier`, a `SimpleMeterRegistry` and a `ModelBudget`:

- **Fault rows — timeout / 429 / 5xx** (each an `OpenAIServiceException` or a `TimeoutException` cause chain): every wrapper attempt fails, so the whole call spends **2 wrapper attempts × 2 planner attempts = 4 model calls**, recorded as `timeout` / `rate_limit` / `server_error` in `agentflow.ai.provider.chat.errors.total{reason}` (one increment per failed wrapper attempt, never an invented reason). The terminal failure is `PlannerPlanParseException` with classification `TRANSPORT_FAILURE` — never a silent success. No tokens are recorded (the wrapper never holds a response), the whole-call latency timer records one sample per wrapper call (2), and the usage reservation is **kept** on both failed attempts (never settled on failure) — the committed headroom is `2 ×` the conservative estimate.
- **Invalid structured output** (the `INVALID_PLAN` fixture — well-formed JSON that is not a structurally valid plan): the wrapper succeeds and returns a response carrying the fixture's usage block, so the planner repairs once, then fails terminal as `PlannerFailure.SCHEMA_MISMATCH` with exactly **2 model calls**. The errors counter stays empty (the wrapper never fails — this is a planner-level failure), the latency timer records 2 samples, tokens are recorded from the provider's own report (12 input / 6 output per attempt × 2 = **24 / 12** — never invented), and both failed attempts keep their reservation.
- **Refusal** (`null` content, `content_filter` stop): never retried (not a repairable failure), exactly **1 model call**, terminal classification `PROVIDER_REFUSAL`, no errors counter, a single latency sample, **no tokens** (the refusal carries no usage block), and exactly one kept reservation.
- **Valid-plan control:** exactly 1 model call, the plan returned, tokens recorded once (12 / 6), one reserved estimate — the baseline that a clean path introduces no error counter and no extra calls.

A second, dedicated test proves the budget-blocking half of the same criterion: a budget whose headroom covers exactly one conservative estimate lets the initial attempt run (2 wrapper attempts, 2 model calls), then the S09-08 repair is refused at the pre-call reserve — the fake is never asked again (`calls == 2`), the refusal is the terminal failure (`InsufficientBudgetException`), the one reservation that did proceed is kept (never silently released).

No production change — the resilience contract was already in place (S09-09 / S09-08 / S09-10); the new suite proves the acceptance criterion that no single test covered: **retries bounded, expenses accounted, runaway calls absent**, with the sibling 5xx row filled in. The matrix is intentionally chat-side (`ResilientChatModel` + `PlannerService`); the embedding wrapper is covered by `ResilientEmbeddingModelTest` and the failure-matrix row cites both.

### Why

`S14-09` is the P0 Sprint-14 task: "fake provider возвращает timeout, 429, 5xx, invalid structured output и refusal; сверить retry count, status, usage reservation и metrics." The fake provider already reproduced `TIMEOUT` / `RATE_LIMIT` / `INVALID_PLAN` / `REFUSAL` (S09-11) — but a 5xx scenario was missing, and no test verified the whole matrix at once: the retry bound against the real wrapper, the terminal status, the usage reservation across failed attempts, and the metrics evidence — as one criterion set rather than four separate suites. The new suite makes the criterion one executable artifact: six parameterized rows (one per provider outcome + the valid control) plus the budget-blocking test, all through the production seam.

### Verification

- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.provider.ProviderErrorMatrixTest' --tests 'com.example.agentflow.ai.provider.FakeModelProviderTest' --tests 'com.example.agentflow.ai.provider.FakeModelConfigurationTest'` — BUILD SUCCESSFUL; `ProviderErrorMatrixTest` **7 tests, 0 failures, 0 errors** (JUnit XML `tests="7" failures="0" errors="0"`); `FakeModelProviderTest` **23 tests, 0 failures, 0 errors** (XML `tests="23"` — up from 20, +3 new 5xx/alias checks); `FakeModelConfigurationTest` **6 tests, 0 failures, 0 errors** (XML `tests="6"` — up from 5, +1 5xx config-wiring case).
- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.*'` — BUILD SUCCESSFUL, **260 tests, 0 failures, 0 errors** (whole `ai` module, XML-verified; 259 before the config-wiring test, +1).
- `./gradlew check` — BUILD SUCCESSFUL, exit 0.
- `./gradlew :apps:agentflow-app:check --rerun-tasks` — BUILD SUCCESSFUL, 8 executed (cache bypassed).
- Final gate on the finished tree (after the config-wiring test): `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL in 42s.

### Scope / follow-up

The embedding side stays in `ResilientEmbeddingModelTest` (S09-09/S09-11); the matrix intentionally covers the chat/planner path. No production change — the resilience contract already existed; the new suite proves the acceptance criterion and fills the missing 5xx fixture. The operational injection remains the fake provider — no live endpoint was needed.

---

## S14-08 — Redis outage verification

### What changed

One new test in [`RedisRateLimiterIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/common/rate_limit/RedisRateLimiterIT.java) (1 test, Testcontainers — real `redis:8.10.2` on the shared suite + a never-responding local TCP server, direct construction): the **slow-response / hanging-Redis** case that the existing unreachable-port test does not cover — a reachable Redis that accepts the connection but never replies is served by the in-memory failover within the configured command timeout (1 s in the test; 5 s in production config), never an unbounded pin, with `fallback_open` incremented exactly once. The test scaffolding adds a slow-socket factory (`slowFactory(int commandTimeoutSeconds)` — a daemon accept-and-hold thread over `ServerSocket(0)`, guarded in `@AfterEach`).

No production change — the fail-open contract was already in place (S12-19 / S13-22); the new evidence proves the one acceptance criterion that no existing test covered: a slow Redis is bounded by the command timeout. The cache purpose (`cache`) is documented as **not live** — it is planned for S14-18 and has no code to exercise, so its per-function row says so instead of showing an evidence cell.

Docs: `docs/testing/failure-matrix.md` (Redis section: per-function outage contract table + the overview/evidence rows updated + the "Redis outage full table" gap closed); `docs/architecture/redis-usage.md` §7 (the slow-response bound); `docs/runbooks/redis-outage.md` (the slow-response evidence bullet); this devlog.

### Why

`S14-08` is the P1 Sprint 14 task: "остановить Redis, а также имитировать недоступность по сети и медленные ответы; пройти по списку функций из `docs/architecture/redis-usage.md` (rate limiter по политике ADR-021, cache bypass, quota/ops rate limit) и проверить фактическое поведение каждой; убедиться, что durable workflow state остаётся в PostgreSQL, readiness остаётся UP, а health indicator, метрика и alert отражают сбой; проверить восстановление после запуска Redis без ручных действий; внести таблицу «функция → поведение при outage → evidence» в `docs/testing/failure-matrix.md`." The two outage presentations were covered: network-down (refused port — `fails_open_to_the_in_memory_limiter_when_redis_is_unreachable`) and slow-response (never-responding server — this test). Every function on the `redis-usage.md` list was checked and its behaviour recorded. Durable workflow state never leaves PostgreSQL (the worker never connects to Redis); readiness stays `UP` via the non-critical `cache` group; the health indicator and the metrics (`agentflow.redis.command.errors.total`, `latency.seconds{result="failed"}`, `decision.total{result="fallback"}`, `fallback_open`) reflect the failure; the Git-provisioned `agentflow-redis-unavailable` alert fires on the error series.

### Verification

- `./gradlew :apps:agentflow-app:integrationTest --tests '*RedisRateLimiterIT*'` — BUILD SUCCESSFUL, **3 tests, 0 failures, 0 errors** (JUnit XML `tests="3" failures="0" errors="0"`, fresh run).
- `./gradlew :apps:agentflow-app:integrationTest --tests '*RedisSmokeTest*' --tests '*QuotaRedisDependencyIT*'` — BUILD SUCCESSFUL, **3 + 2 tests** (XML `tests="3"` / `tests="2"`, failures=0).
- `./gradlew :apps:agentflow-app:test --tests '*RedisRateLimiterTest*' --tests '*RedisHealthIndicator*'` — BUILD SUCCESSFUL (XML `tests="10"` / `tests="1"` / `tests="2"`, failures=0).

### Scope / follow-up

The rebuildable cache row is honestly **not live** (planned for S14-18) — the per-function table records the contract without evidence. The live-boot health-group transitions (`redis-usage.md` §7) remain manual evidence — no automated stopped-container test exists (the operational injection stays `docker stop agentflow-redis`). S14-18 must close the cache row with real tests before it can be marked live.

---

## S14-07 — PostgreSQL outage / unknown commit

### What changed

One new integration suite, [`PostgresOutageUnknownCommitIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/workflowexecution/application/PostgresOutageUnknownCommitIT.java) (2 tests, Testcontainers — real PostgreSQL `pgvector/pgvector:0.8.6-pg18`, direct construction, no hand-set row), closing the PostgreSQL row of the S14-01 failure matrix — the connection-loss-mid-transaction / unknown-commit half of the Sprint 14 acceptance criterion, which the S14-01 matrix honestly marked as a gap:

- **`create_run_cut_mid_transaction_retries_with_the_same_key_and_creates_no_duplicate`** — the connection is cut *while a keyed create-run is blocked at the outbox append* (the last write of the create-run transaction, after the run row has already applied — the genuine unknown-commit window, modelled at the persistence seam, never a hand-set row). The first attempt fails with an error and the client cannot know whether the transaction committed; whatever the first attempt did by construction — run and outbox rows move together — the abort leaves both absent together. The same request is re-issued with the same idempotency key and payload fingerprint through a fresh connection pool; the retry succeeds and yields exactly one run and one outbox event, moved together, never a duplicate.
- **`worker_update_cut_mid_transaction_retries_cleanly_without_a_duplicate`** — the connection is cut *while the worker's fenced state transition is blocked at the history-event append* (the last write of the transition transaction, after the run transition has already applied). The first attempt fails with an error and the worker cannot know whether the transition committed; the rolled-back transaction leaves the run and its history absent together, never a moved run without its transition fact. The same fenced transition is re-issued through a fresh connection pool; the retry lands exactly one transition and exactly one history row, never a duplicate, never a partial state.

The cut is a real **session termination** — the gated connection is identified by the one holding an open transaction (`xact_start IS NOT NULL`) and killed with `pg_terminate_backend` from a separate admin connection, with a poll-until-gone guard so the gate is released only once the session is really dead (otherwise the gated write races the SIGTERM and commits on a not-yet-dead socket). No test DB is ever hand-edited: the run and the worker transitions are produced by the production `RunService.createRun` / `RunStateMachineService.transitionRun` paths, and the kill is a live backend death, not a hand-set row. A fresh container per test (and per test method) keeps the two independent.

### Why

`S14-07` is the P0 Sprint-14 task: "оборвать connections во время create-run и worker update; повторить запрос с тем же idempotency key; проверить наличие/отсутствие run и outbox вместе". The acceptance criterion — no accepted run without durable state/outbox and no duplicate on retry — was a documented gap in the S14-01 failure matrix (the one dedicated PostgreSQL live-outage scenario). Two windows were covered: create-run and worker update, each against a live PostgreSQL with the connection cut in flight, and each with the re-issue-on-retry guarantee that the retry lands a consistent state and never a double.

### Verification

- `./gradlew :apps:agentflow-app:integrationTest --tests '*PostgresOutageUnknownCommitIT*'` — BUILD SUCCESSFUL, **2 tests, 0 failures, 0 errors** (JUnit XML `tests="2" failures="0" errors="0" skipped="0"`, fresh `--rerun-tasks` on the final state).
- Sibling suites (S14-03/04/05/06) in the same thread green: `WorkflowWorkerConcurrencyIT` 2/0, `BrokerRestartPublisherCrashIT` 2/0, `WorkflowTransitionConcurrencyIT` 2/0, `InboxDedupConcurrencyIT` 1/0.
- `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL, EXIT=0 (including `spotlessCheck` on the new file).

### Scope / follow-up

The cut is modelled as a mid-transaction session termination rather than a container stop: in this suite the schema did not survive a container `stop()`/`start()`, which is the whole-service-outage pattern (S06-14/S07/S14-05) — but the **unknown-commit window is a connection-loss claim, not a whole-service outage**, so the test cuts the gated session and keeps the server and the schema intact. The operational `docker stop agentflow-postgres` path remains the failure injection in the matrix (the operator's real mitigation), and the atomicity guarantees above (`RunServiceAtomicIT`) are proven alongside it; this suite closes the mid-transaction half. `S14-08` (Redis outage) remains out of scope.

---

## S14-06 — concurrent lease claims

### What changed

One new integration suite, [`WorkflowWorkerConcurrencyIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/workflowexecution/application/WorkflowWorkerConcurrencyIT.java) (2 tests, Testcontainers — real PostgreSQL `pgvector/pgvector:0.8.6-pg18`, direct construction — the harness pattern of `WorkflowWorkerCrashRecoveryIT`), closing the concurrent-lease-claim half of the Sprint 14 acceptance criterion ("only the current lease owner can commit a result"):

- **worker_claim_is_atomic_and_only_one_owner_wins** — two independent `WorkflowWorker` instances race the same claimable run; exactly one wins the claim, the other gets nothing (its `claim` returns -1), and the winner's `lease_owner`/`lease_epoch` is recorded. This is the run-level half of the guarantee and is also covered by `RunLeaseClaimIT` (5 tests) at the repository boundary.
- **a_stale_owner_cannot_commit_a_result_after_another_worker_reclaims_the_run** — the stale owner is held mid-handler on a `CountDownLatch` while its lease lapses; a second worker re-claims the run (its own atomic claim bumps the run *and every live step* fence); the stale owner is released and must not commit anything: the step transition, the checkpoint write and the run transition are all refused — the run is still owned by the second worker, the checkpoint never landed, no second `workflow_events` fact exists, and the run is never reconciled by the stale owner.

The production fix that makes the second test pass closes a real gap the new test exposed. The atomic claim in `WorkflowRunRepository.claim` now bumps the run's `lease_epoch` **and every live step's `lease_epoch` in the same transaction** (the 6-arg repository transition is fenced by the step row's `lease_epoch`, so a step row that survived a re-claim would otherwise silently accept a stale write). Every worker transition now passes the **claimed** `lease_epoch` — not the row's freshly read one — into the state-machine service (`RunStateMachineService.transitionRun/Step`, clean and fault variants, run and step level); the service refuses the transition (`409`) when the claimed epoch no longer matches the row's fence — checked *before* the conditional update — and uses the claimed epoch for the update itself, so a write on a moved fence can never land. The checkpoint write (`CheckpointAdapter`) already fenced on the claimed epoch at the SQL layer (the row's `lease_epoch` is part of `saveCheckpoint`'s guard) and is now exercised here at the worker boundary.

Unit-level fencing pins (`RunStateMachineServiceTest`, +12 tests): the claimed-epoch clean/fault variants fence the conditional update on the claimed epoch (verified via the repository spy); a stale claim (fence moved) is refused with `409` before the update and before the event append; a non-positive claimed epoch is an `IllegalArgumentException`; the fault variant's 8-arg conditional update is fenced on the claim (verified via the 8-arg spy). The fresh-read variants are unchanged — they fence on the row's freshly read epoch and reject the same moved-fence case via the repository's own zero-rows conflict.

### Why

`S14-06` is the P0 Sprint-14 task: "запустить две worker replicas; одновременно claim один run; задержать heartbeat старого owner; проверить fencing epoch на write". Two gaps the existing suites did not cover: (1) the atomic claim did not bump the steps' fence — a step row surviving a re-claim kept the old epoch and would accept a stale write through the repository's own guard; (2) the worker's transition calls fenced on the freshly read row epoch rather than the claimed one — a write that won the row read but lost the claim window could still commit. The claim now bumps both in one statement, and the worker carries its claim everywhere it writes — clean updates, fault updates, the checkpoint and the run/step transitions alike.

### Verification

- `./gradlew :apps:agentflow-app:integrationTest --tests 'com.example.agentflow.workflowexecution.application.WorkflowWorkerConcurrencyIT'` — BUILD SUCCESSFUL, **2 tests, 0 failures, 0 errors** (JUnit XML `tests="2" failures="0" errors="0"`, fresh timestamp on this run). The RED variant of the stale-owner test (before the fix) failed exactly at the first stale-write assertion (`the stale owner's late commit must be refused`), confirming the test exercises the gap.
- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.workflowexecution.*'` — BUILD SUCCESSFUL (all workflowexecution unit suites, incl. the +12 new fencing tests).
- `./gradlew :apps:agentflow-app:integrationTest --tests 'com.example.agentflow.workflowexecution.*'` — BUILD SUCCESSFUL, **14 suites / 52 tests, 0 failures, 0 errors**.
- `./gradlew :apps:agentflow-app:check` — BUILD SUCCESSFUL, EXIT=0 (8 tasks).
- `./gradlew :apps:agentflow-app:integrationTest` — BUILD SUCCESSFUL in 4m54s, **40 suites / 186 tests, 0 failures, 0 errors, 0 skipped** (39/184 → 40/186, +1 suite/+2 tests; XML counted).

### Docs

- `docs/devlog/sprint-14-resilience.md` — this file (S14-06 section + contents line).
- `docs/testing/failure-matrix.md` — worker evidence line (the new suite).
- `CHANGELOG.md` — Unreleased / Added entry.

### Scope / follow-up

The two racing workers in the first test are in-process instances (no real deploy) — the honest limit of this task's coverage: the run-level guarantee is pinned at the repository boundary (`RunLeaseClaimIT`, 5 tests) and the step-level guarantee is pinned at the worker boundary (this suite). The heartbeat-kill / lease-expire half of the acceptance criterion is exercised in the second test by the blocked handler + the lapse of the lease, and the full kill-mid-step / restart lifecycle remains covered by `WorkflowWorkerCrashRecoveryIT` (the S07/S14 story). `S14-07` (PostgreSQL outage / unknown commit) and the rest of the Sprint 14 thread remain out of scope.

---

## S14-05 — broker restart and publisher crash

### What changed

One new integration suite, [`BrokerRestartPublisherCrashIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/messaging/worker/BrokerRestartPublisherCrashIT.java) (2 tests, Testcontainers — real PostgreSQL `pgvector/pgvector:0.8.6-pg18` + the real `apache/kafka:4.3.1` container, stopped and started mid-test, no hand-set row), closing the broker-restart / publisher-crash half of the Sprint 14 acceptance criterion — the event is **eventually delivered** and the duplicate is **handled idempotently** — against a live broker rather than a dead port:

- **`broker_restart_at_the_moment_of_publication_delivers_the_event_after_recovery`** — the broker is stopped while a publish attempt is in flight. The send is gated on a latch, so it provably happens while Kafka is down: the publisher thread reaches the send (armed), `kafka.stop()` runs, then the send is released. The producer is pointed at the dead bootstrap, so the attempt fails fast (`retries=0`, short `request.timeout.ms` / `connection.timeout.ms` — the S06-14 dead-port posture, but against the real stopped container) and is classified a retryable transient fault. The row survives the restart as a durable `FAILED` (`attempts=1`, never lost, never falsely `SENT`). After `kafka.start()` (a fresh container), the stale `FAILED` row — `next_attempt_at` aged into the past — is re-selected, claimed and delivered to the fresh broker: `SENT`, exactly one copy on the broker, inbox-deduped, exactly one business transition, outbox fully drained.
- **`publisher_crash_after_broker_ack_before_db_update_republishes_as_duplicate`** — the publisher delivers to the real broker through the production `MessageDeliverer` (a real send, broker ack) and is then simulated as crashed: the send happens, then the poll throws in-process — the row is left `CLAIMED` by the production claim path itself, not by a hand-edit (the S06-14 test models the same window by hand-setting the row back to `CLAIMED`). The broker holds one copy; `claimed_at` is aged a day to model the elapsed time before recovery. The stale claim is re-selected and re-published — a duplicate, never a loss: the broker ends with two copies, the inbox dedups both to a single business transition (`PROCESSED` then `OTHER_CLAIMED`, one transition, one `PROCESSED` inbox row), outbox fully drained.

Both tests drive the production `OutboxPublisherService` and `InboxDedupService` paths (the claim / publish / outcome state machine, not a test-local copy), with a fresh tenant and topic per test — so the two tests are isolated on the shared containers — and the real Flyway-applied schema (`classpath:migrations`, the same image as CI). No production change — test-only task.

### Why

`S14-05` is the P0 Sprint-14 task: "перезапустить Kafka в момент публикации; остановить publisher после broker ack до database update; восстановить сервис и проверить eventual delivery". The S06-14 outage test proves the same guarantee against a **dead port** (the producer never talks to a real broker), and the S06-10 crash-window test hand-edits the row after a full `pollOnce()` succeeded. `S14-05` closes the two gaps: a **real broker restart at the moment of publication** — the container is actually stopped while the send is in flight, the attempt provably happens against the dead broker, and the recovery pass delivers to a fresh broker; and a **crash in the real window** — the publisher dies after the broker ack and before the outcome update, leaving the row `CLAIMED` by the production path itself (the recovery pass re-claims the stale claim through the S06-08 stale-claim bound, exercised without a hand-edit).

### Verification

- `./gradlew :apps:agentflow-app:integrationTest --tests '*BrokerRestartPublisherCrashIT*'` — BUILD SUCCESSFUL, **2 tests, 0 failures, 0 errors** (JUnit XML: `tests="2" skipped="0" failures="0" errors="0"`, fresh `timestamp` on this run).
- Sibling suites in the package green in the same run window: `ReliabilityScenarioIT` 3/0, `KafkaMessageDelivererIT` 3/0.
- `./gradlew :apps:agentflow-app:integrationTest --rerun-tasks` — BUILD SUCCESSFUL in 5m22s, **39 suites / 184 tests, 0 failures, 0 errors, 0 skipped** (38/182 → 39/184; full XML counted with a script).
- `./gradlew :apps:agentflow-app:check --rerun-tasks` — BUILD SUCCESSFUL in 1m07s (8/8 tasks executed, not cached): unit + architecture + formatting (`spotlessCheck`) + OpenAPI baseline re-run, EXIT=0.

### Docs

- `CHANGELOG.md` — Unreleased / Added entry (newest-first).
- `docs/devlog/sprint-14-resilience.md` — this file (S14-05 section + contents line).
- `docs/testing/failure-matrix.md` — Kafka row evidence (the new suite).

### Scope / follow-up

No DLQ scenario is exercised here (the failure is transient/ambiguous, retried at bounded backoff — the DLQ path is `S06-11`, out of scope). The crash in the second test is simulated in-process (the poll throws after the broker ack), not a real process `kill -9` — the honest limit of this task's coverage: the durable row is left exactly as a real crash would leave it (`CLAIMED`, stale `claimed_at`, `published_at` never stamped), and the recovery pass re-claims it through the production stale-claim bound. The first test's restart half is fully real — the live `apache/kafka:4.3.1` container is stopped and started mid-test, and the send is made against the dead bootstrap. `S14-07` (PostgreSQL outage / unknown commit) and the rest of the Sprint 14 thread remain out of scope.

---

## S14-04 — duplicates and reordered events

### What changed

One new integration suite, [`DuplicateAndReorderedEventsIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/workflowexecution/application/DuplicateAndReorderedEventsIT.java) (3 tests, Testcontainers on real `pgvector/pgvector:0.8.6-pg18`, direct construction — the same pattern as `InboxDedupConcurrencyIT` / `WorkflowTransitionIntegrationTest`), closing the duplicate/reordered-event half of the Sprint 14 acceptance criterion — **no double business effects and no forbidden transitions** under at-least-once delivery (ADR-003):

- **`same_event_id_delivered_repeatedly_runs_the_business_action_exactly_once`** — the same `event_id` delivered five times sequentially through the production `InboxDedupService` (with the real `RunStateMachineService` as the business action). The first claim proceeds, the remaining four are `OTHER_CLAIMED`; the business effect runs exactly once (one transition + one `workflow_events` history row + one `PROCESSED` inbox row, asserted via the production read paths), and the run ends `RUNNING`.
- **`events_of_one_run_delivered_out_of_order_never_apply_a_forbidden_transition`** — the two events of one run (`run.started`, `run.succeeded`) delivered in reverse order. The terminal event arrives first (`QUEUED -> SUCCEEDED`, no edge) and is refused by the **production** domain gate (the 400 path of `RunStateMachineService`, which throws before any write); the inbox row is held `FAILED` with the bounded retry recorded (`attempts=2` for `maxAttempts=2`), never `PROCESSED`. The legal event then applies (`QUEUED -> RUNNING`), so the run ends `RUNNING` with exactly one history row — the refused event left nothing behind.
- **`events_of_one_run_delivered_in_order_apply_both_transitions_and_reach_the_terminal_state`** — the valid counterpart: both events delivered in order, both apply, the run reaches the terminal `SUCCEEDED` state, history has exactly two rows (`run.started` + `run.succeeded`), both inbox rows `PROCESSED`. This is also the proof that the inbox boundary drives a complete run end to end, not just the refusal path.

Events are modelled at the **inbox boundary** (the seam production consumers use), not at the broker: an event of a run is the transition its envelope carries, each delivered through a fresh claim — the broker-level partition-ordering question is orthogonal to the guards under test (Kafka preserves per-partition order; it does not create out-of-order delivery within a partition). The business action is the production `RunStateMachineService`, so the guard is the production gate, not a test-local re-check. No production change — test-only task.

### Why

`S14-04` is the P0 Sprint-14 task: "доставить один event ID многократно; перемешать события одного run; проверить inbox uniqueness и transition guards — нет двойных business effects и запрещённых transitions". The existing S06 duplicate/concurrency suites prove a single duplicate pair and a 4-thread race (one transition, one inbox row); `S14-04` closes the three gaps the S06 tests do not cover: sequential multi-delivery of one event id, out-of-order delivery of a run's own events (an inbox consumer must never apply a transition the run is not allowed to take), and the legal counterpart that proves the guards do not over-reject.

### Verification

- `./gradlew :apps:agentflow-app:integrationTest --tests '*DuplicateAndReorderedEventsIT*'` — BUILD SUCCESSFUL, **3 tests, 0 failures, 0 errors** (JUnit XML confirmed: `tests="3" skipped="0" failures="0" errors="0"`, fresh `timestamp` on this run).
- `./gradlew :apps:agentflow-app:check --rerun-tasks` — EXIT=0, BUILD SUCCESSFUL (48s, 8 tasks: unit + architecture + spotless + OpenAPI baseline re-run, not cached).
- `./gradlew :apps:agentflow-app:integrationTest` — BUILD SUCCESSFUL, **38 suites / 182 tests, 0 failures, 0 errors, 0 skipped** (37/179 → 38/182: +1 suite, +3 tests; full XML counted with a script, the new suite's XML confirmed separately above).
- `spotlessCheck` green on the new file (`spotlessApply` run once after the first draft — the file was re-wrapped, re-run green).

### Docs

- `CHANGELOG.md` — Unreleased / Added entry.
- `docs/devlog/sprint-14-resilience.md` — this file (S14-04 section + contents line; the S14-03 follow-up note that named S14-04 as out of scope is left as-is — it records the state of S14-03, not of S14-04).

### Scope / follow-up

No broker-level reordering is asserted — per-partition order is a Kafka guarantee, and the consistency guarantee under test lives at the inbox/transition seam where the guards are. Step-level event reordering is not separately exercised (the step state machine is guarded by the same domain-gate + conditional-update + fence pattern, covered by the S07/S14-06 worker-concurrency tests). `S14-05` (broker restart / publisher crash) and the rest of the Sprint 14 thread remain out of scope.

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
