# Changelog


# Sprint 9 — AI layer / Planner


## S09-15 — Document the AI integration: provider behind a port (opt-in, key from env/secret), the offline fake provider for CI, prompt versioning, structured-output validation, refusal/malformed/timeout/rate-limit handling, budget reservation, and the Planner eval run command — an integration guide without a local-environment dependency.


### What changed
Two documents complete the `docs/ai/` tree: [`docs/ai/README.md`](../../docs/ai/README.md) (the AI integration guide) and [`docs/ai/provider-configuration.md`](../../docs/ai/provider-configuration.md) (provider wiring). They transcribe the already-solved AI layer (S09-01 … S09-14) into an operator/developer reference; the code is unchanged, so `brainstorming` is skipped for this documentation task.

**The AI integration guide** (`docs/ai/README.md`) leads with the `ADR-006` / invariant-7 premise and the verifyable seams: the port-not-provider boundary (S09-01), the untrusted-planner gate where the model *proposes* and the backend *authorizes before executing* (S09-07), the bounded call and the pre-call budget reservation (S09-09 / S09-10), the versioned/hashed prompt catalogue with the SYSTEM/USER split (S09-04), the refusal / malformed / schema-mismatch / empty handling (S09-06 / S09-08), the opt-in live-provider contract test, and the eval run command with the four per-case metrics (S09-14).

**The provider configuration guide** (`docs/ai/provider-configuration.md`) is the wiring reference. It pins the exact configuration keys and defaults verified in code: the `agentflow.ai.provider` gate (`openai` | `fake`, `matchIfMissing = false` so unset = no provider bean); the OpenAI secret + endpoint (`agentflow.ai.provider.openai.{api-key|model|base-url}`, the key also from `OPENAI_API_KEY`, model default `gpt-4o-mini`, base URL default the public endpoint); the resilience keys and defaults (`agentflow.ai.timeout.{connect-seconds,read-seconds,overall-seconds}` = 10/120/150, `agentflow.ai.retry.{max-attempts,base-delay-seconds,multiplier,max-delay-seconds,jitter}` = 3/1/2.0/60/true, `agentflow.ai.max-parallel-calls` = 4); the fake fixture modes (`agentflow.ai.provider.fake.mode`, default `valid-plan`; `valid-plan`, `invalid-plan`, `malformed-response`, `timeout`, `429`/rate-limit, `refusal`); and the `ai.prompt.prompts[]` declaration (`planner` / `version "1"`). Secrets are documented as env/secret-store only.

### Why
The S09-15 acceptance: the AI integration guide must be usable without binding it to one local environment, real credentials must reach a deployment only through secret/env configuration, and the eval command with its expected result fields must be documented. The `ai` module was built across S09-01 … S09-14 but only had inline Javadoc; the two new documents are the single source of truth an operator reads to wire a provider, reason about resilience, enable the offline fake, and run the eval — the promised `docs/ai/` tree entry (the index and the `ai/AGENTS.md` package layout were the only `docs/` owners of AI; `docs/evaluation/` is S09-14, the opt-in contract test is S09-12).

### Verification
- The commands documented in the guides are the repository's own, run verbatim in the S09-09 … S09-14 records and still green: `./gradlew :apps:agentflow-app:check` (the standard gate runs the Planner against the fake fixtures with no key and no network); `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.evaluation.*'` (the offline eval suite); `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.provider.FakeModelProviderTest'` and `FakeModelConfigurationTest` (every fixture, the opt-in gate).
- The configuration keys and defaults quoted in the guide were cross-checked against `AgentFlowProperties.Ai` (`agentflow.ai.timeout.*` / `agentflow.ai.retry.*` / `max-parallel-calls`), `application.yml` (`ai.prompt.prompts`), `OpenAiChatModelConfiguration` (`agentflow.ai.provider.openai.*`), and `FakeModelConfiguration` / `FakeModelProvider.Fixture` (`agentflow.ai.provider.fake.mode`); the `@ConditionalOnProperty` gate (`matchIfMissing = false`) and the test-source-set placement of the fake (S04-10) are re-checked in the code, not restated from memory.
- No key, credential or connection string is written in the guides or the examples.
- `docs/README.md`: the `docs/ai/` index entry and the AI/RAG/evaluation row now point at the two new documents; the `provider configuration ⚠ planned (S09-15)` marker is removed (a document that now exists is never marked planned). `CHANGELOG.md`: the S09-15 entry under `Unreleased / Added` (newest-first).
- **Not changed:** the code, the port, the adapter, the resilience policy, the prompt catalogue and the eval harness are untouched — this is documentation only; `./gradlew check` was not run against a code change, so the architecture / formatting gates are unchanged from the S09-09 … S09-14 runs.

### Docs
- `docs/ai/README.md` — AI integration guide (S09-15), newly created.
- `docs/ai/provider-configuration.md` — provider wiring, secrets, resilience, fake provider, budget, eval command (S09-15), newly created.
- `docs/README.md` — `docs/ai/` index entry + AI/RAG/evaluation row updated; `provider configuration ⚠ planned` marker removed.
- `CHANGELOG.md` — S09-15 entry under `Unreleased / Added` (newest-first).
- This file.

### Follow-up
`S13` (the RAG pipeline: document ingestion, pgvector hybrid retrieval, citations) completes the `docs/ai/` family with an `ai/` RAG document; the eval harness (`docs/evaluation/README.md`, S09-14) and the opt-in contract test (`docs/ai/real-provider-contract-test.md`, S09-12) already link to this guide as part of the AI integration reference.


## S09-14 — Planner baseline eval cases: a prompt/model change is compared against a fixed, structurally scored baseline, never on text similarity

### What changed
- The internal `ai.evaluation` package holds the harness and the fixtures:
  - [`PlannerEvalCase`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalCase.java)
    — a fixed task, the tool allowlist handed to the Planner for it, and the exact expected plan
    shape (step count + referenced tool set);
  - [`PlannerEvalCases`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalCases.java)
    — the fixed baseline: `calc-then-finalize` (minimal tool pipeline), `calc-report-finalize`
    (two-tool chain), `finalize-only` (no-tool answer against an empty allowlist),
    `parallel-calc-finalize` (fork into one finalize), using the built-in `calculator` / `report`
    registry names;
  - [`PlannerEvalRunner`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalRunner.java)
    — drives the production `PlannerService` (bounded repair and pre-call budget guard included)
    and re-checks every plan with the production `ValidationGate`; scores four structural metrics
    per case: schema validity, tool allowlist conformance, expected shape, limits. A failed model
    attempt is a failed case carrying its `PlannerFailure` classification, never a rethrown fault;
  - [`PlannerEvalReport`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalReport.java)
    — aggregates pass/fail and renders a byte-stable report (fixed `\n` endings, no timestamps,
    fixture order) so two configurations diff directly.

### Why
- The S09-14 acceptance: a prompt or model change can be compared against the baseline. The eval
  deliberately scores structure — schema validity, allowed tools, steps, limits — not textual
  similarity: a plan is a typed graph to validate, not prose. Expectations are exact ("close
  enough" is not a pass); dependency wiring is covered by the schema-validity metric, so step-id
  naming taste never fails a case.

### Verification
- `./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.evaluation.*'` green; the
  JUnit XML shows `tests="7" skipped="0" failures="0"` — the suite really ran.
- The 7 tests pin: the baseline passes against an ideal scripted model; a tool outside the
  allowlist fails with the gate's `unknown tool` violation; an over-limit plan (`PlanLimits(1, …)`)
  fails the limits metric; a malformed response is a `MALFORMED_JSON` schema failure with null
  model/prompt metadata; an unexpected step count fails the shape metric only (schema/limits stay
  green); the rendered report is identical across runs; null construction arguments are contract
  errors.

### Docs
- `CHANGELOG.md`: S09-14 entry under `Unreleased / Added` (newest-first).
- `docs/evaluation/README.md`: the cases, the metrics, the run/compare commands — the promised
  `docs/evaluation/` tree entry is no longer ⚠ planned; `docs/README.md` (tree + topic table) and
  the `ai` module `AGENTS.md` package layout are updated.

### Follow-up
- S09-15 (AI integration guide) documents the eval run command and the real-provider eval wiring
  on top of this harness; see below for the earlier S09-12/S09-13 records.


## S09-12

### What changed
- The opt-in real provider contract test (S09-12).
  [`RealProviderContractTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/RealProviderContractTest.java)
  (test source set, `ai.provider`, `@Tag("real-provider")`) verifies the OpenAI adapter contract
  against a **live** provider, so the mapping can never silently drift from the real API shape the
  S09-11 fake stands in for. Three checks, each through the production wiring
  (`OpenAiChatModelConfiguration` + resilience layer booted via `ApplicationContextRunner`, so the
  rendering/mapping under test is exactly what production runs):
  - response mapping + token usage — a plain completion maps to the neutral `ChatResponse` with
    non-null content, the provider-reported model and finish reason, and `ChatUsage` with positive
    input/output counts whose total matches the sum;
  - structured output — a neutral `outputSchemaJson` renders into the provider's
    structured-output form and the completion parses as JSON conforming to the schema (the
    Planner's S09-05 seam end to end);
  - timeout — the production `ResilientChatModel` aborts a real in-flight call (a forced
    ~150-token generation cannot finish inside the bound) and the production
    `ProviderExceptionClassifier` maps the abort to `TIMEOUT`.
- The opt-in mechanism. The default `test` task excludes the `real-provider` tag (so `./gradlew
  check` stays free, offline and deterministic), and a new `realProviderContractTest` Gradle task
  runs only that tag, only when `OPENAI_API_KEY` is set (`onlyIf` with an explicit skip reason).
  The key is read from the environment / a `agentflow.contract-test.*` system property only; model,
  base URL and the timeout bound are overridable for compatible gateways.

### Why
- The S09-12 acceptance: the adapter is verified against the real provider, but the standard CI
  gate remains free and deterministic. The offline gate keeps using the S09-11
  `FakeModelProvider` fixtures; this suite is the scheduled/manual smoke that catches a provider
  contract drift the fake cannot see (response envelope shape, usage accounting, structured-output
  rendering, real timeout faults). An invalid key fails loudly from the provider's real error —
  a contract test working as intended, never a silent pass.

### Verification
- `./gradlew :apps:agentflow-app:test --tests '*ai.provider*'` green, and the JUnit XML of the
  default `test` task contains **no** `RealProviderContractTest` results — the tag exclusion is
  real.
- `./gradlew :apps:agentflow-app:realProviderContractTest` without `OPENAI_API_KEY` →
  `SKIPPED` with the explicit reason; build successful.
- `OPENAI_API_KEY=<dummy> AGENTFLOW_CONTRACT_TEST_TIMEOUT_MILLIS=50 ./gradlew
  :apps:agentflow-app:realProviderContractTest` → the task runs against the live endpoint: the
  timeout check **passed** (the abort fired in flight and classified `TIMEOUT`), while the two
  mapping checks failed loudly with the provider's real `403 Country, region, or territory not
  supported` — this sandbox's egress IP is geo-blocked and the key is a dummy, which is exactly
  how a contract test must behave. With a valid key from a supported region all three checks
  exercise the real happy path (RTT here ~100–200 ms; a forced long generation takes seconds, so
  the default 500 ms bound is safe for healthy endpoints).
- Design note from that run: a millisecond SDK read timeout was rejected for the timeout check —
  it also interrupts the TLS handshake on some network paths (classified `CONNECTION`, not
  `TIMEOUT`), which is environment noise. The platform's own overall-timeout seam
  (`ResilientChatModel`) is the deterministic bound against a live provider; SDK read-timeout
  classification stays covered offline by `ProviderExceptionClassifierTest` and the fake `TIMEOUT`
  fixture.
- `./gradlew :apps:agentflow-app:check` green (see below).

### Docs
- `CHANGELOG.md`: S09-12 entry under `Unreleased / Added` (newest-first).
- `docs/ai/real-provider-contract-test.md`: opt-in mechanism, configuration table, the three
  checks, report location and format, scheduling and cost notes.
- `docs/README.md`: the `docs/ai/` index entry points at the new document; `rag/` and
  `evaluation/` stay ⚠ planned.

### Follow-up
- S09-13 (AI redaction tests) is now complete; see below.


## S09-13 — Redact the value of sensitive prompt / document fields: the system / user prompt, the document body or the injected messages is masked to end of value, so none of that untrusted content — a canary included — reaches the log, Loki, disk or the Actuator.



### What changed
The redaction guard from `S09-03` proved that the registered
[`SecretRedactingValueMasker`](apps/agentflow-app/src/main/java/com/example/agentflow/observability/SecretRedactingValueMasker.java)
masks a *single-token* secret embedded in a log line (`api_key=<canary>` → `api_key=***`), but it left
the whole value of a sensitive prompt / document field untouched: `documentBody=<body>` and
`prompt=<text>` were logged verbatim. Such a value is untrusted content (`ADR-006`) that can span many
words — a document body, a paragraph, a JSON `messages` payload — so the existing `api_key=<secret>`
rule, which stops at the first whitespace, would leave the canary behind.

`SecretRedactingValueMasker` therefore gains a `PROMPT_CONTENT` pattern that masks the *whole* value of
a sensitive field to end of value, not only up to the first whitespace. It covers `document` /
`documentBody` / `prompt` / `systemPrompt` / `userContent` and the injected `messages`; the safe
observability surface (model, latency, token count, safe error category) is left unchanged, and a bare
`promptVersion=<id>` identifier is not mistaken for content. The three secret-key patterns
(`BEARER_TOKEN`, `AWS_ACCESS_KEY`, `KEY_SECRET`) are unchanged, so every `S09-03` case still holds. The
redaction filter stays in the cross-cutting `observability` module (no new module dependency); the
redaction tests live in the per-domain `ai.security` package, the `S09-03` pattern, reusing the same
`SecretRedactingValueMasker` the `logback-spring.xml` registers — so the change covers the log path in
production.


### Why
`S09-03` closed the *provider key*; `S09-13` closes the same leak on the *other* secret surface the AI
layer logs: the prompt and the document. Invariant 8 says a secret never lands in a log, and the
prompt / document body is exactly where a secret (or a prompt-injection payload carrying one) is most
likely to appear. Masking to end of value — not up to the first word — is the only way to keep the whole
value out when it is a multi-word document or a `messages` JSON blob.


### Verification
`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.security.AiObservationRedactionTest' --tests 'com.example.agentflow.observability.SecretRedactingValueMaskerTest' --tests 'com.example.agentflow.ai.security.CanaryLeakScanTest'`
— green (19 tests: 6 + 9 + 4). The acceptance cases run DB-free against the real masker: the planted
canary is redacted from an `api_key` pair, a document body, every sensitive prompt field and the
`messages` payload, survives nowhere in the safe observation, and is committed only to the sanctioned
fixture (git scan). `:apps:agentflow-app:check` green (compilation, unit, architecture gates, spotless
formatting, OpenAPI contract).


### Tests
- `AiObservationRedactionTest` (`ai.security`, DB-free, 6): the canary is redacted from an `api_key`
  pair, a document body, every sensitive prompt field and the `messages` payload; it survives nowhere
  in the safe observation; the git scan asserts the canary is committed only to the sanctioned fixture.
- `SecretRedactingValueMaskerTest` (`observability`, unit, +3 new): the document body and sensitive
  prompt fields are masked to end of value; the safe AI observability fields (model, latency, token
  count, safe error category) and a bare `promptVersion=<id>` are left unchanged.
- `CanaryLeakScanTest` (`ai.security`, DB-free, unchanged, 4): still green — the `S09-03` guard is not
  regressed.


### Docs
- `CHANGELOG.md` — S09-13 entry under `Unreleased / Added` (newest-first).
- `docs/security/secrets.md` — the redaction of prompt / document fields is noted alongside the
  provider-key masking it extends.
- This file.


### Follow-up
Wire the redaction into the actual AI call path once observability / tracing is integrated (`S13`):
today the guard is the `logback-spring.xml` filter, which covers the log path; trace-attribute and
diagnostic-surface coverage follows the same pattern.


## S09-11

### What changed
- The deterministic fake model provider (S09-11). [`FakeModelProvider`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/FakeModelProvider.java)
  (test source set, `ai.provider`) is a `ChatModelPort` that reproduces every provider outcome the
  platform must survive — one fixture per behaviour, each fully deterministic: `VALID_PLAN` (a
  fixed two-step plan that parses through the real `PlannerPlanParser`), `INVALID_PLAN`
  (well-formed JSON with a duplicated step id), `MALFORMED_RESPONSE` (not JSON), `TIMEOUT` (a
  fault carrying a `TimeoutException` cause), `RATE_LIMIT` (a 429 `OpenAIServiceException`) and
  `REFUSAL` (`null` content with a `content_filter` stop). Every call is counted, so bounded-repair
  tests assert the exact number of model calls. The fault fixtures reuse the production fault
  *types* rather than parallel fake ones, so the real `ProviderExceptionClassifier` maps them to
  `TIMEOUT` / `RATE_LIMIT` (retryable under the S09-09 policy) exactly as it would a live provider
  fault.
- Test-only wiring. [`FakeModelConfiguration`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/FakeModelConfiguration.java)
  exposes the fake as the `ChatModelPort` bean when `agentflow.ai.provider=fake`, with the fixture
  selected by `agentflow.ai.provider.fake.mode` (default `valid-plan`; `429` accepted as the
  rate-limit alias; an unknown mode fails the context at bean creation). It lives in the test
  source set — the S04-10 pattern for the permissive security chain — so a misconfigured profile
  can never point a real deployment at it, and the default / local / kubernetes profiles keep the
  S09-02 contract (no provider bean, no key, bootable core app).

### Why
- The S09-11 acceptance: the standard `./gradlew check` gate must need neither an API key nor an
  internet connection. The Planner (and later the AI step handler) is exercised against fixed
  fixtures instead of a live model, so CI is deterministic, free and offline — while the fixtures
  flow through the *real* seams (parser, classifier, Planner bounded repair), not parallel
  reimplementations of them.

### Verification
- Three new suites (26 tests, JUnit XML checked, all green): `FakeModelProviderTest` (20 — each
  fixture through the real parser / classifier / Planner: valid plan parses into the expected
  two-step plan, invalid plan rejected with the duplicate-id signal, malformed rejected as
  `MALFORMED_JSON`, timeout and 429 classify as `TIMEOUT` / `RATE_LIMIT` by the production
  classifier, refusal returns `null` content, every fixture deterministic across repeated calls,
  call counting, the planner's bounded repair against the malformed / invalid / timeout / 429
  fixtures with exact call counts, the refusal never retried, the valid plan returned with its
  metadata, config parsing incl. the `429` alias and fail-fast unknown modes);
  `FakeModelConfigurationTest` (4 — the port is wired only for `agentflow.ai.provider=fake`, the
  configured mode is honored, an unknown mode fails the context, no port without the property);
  `FakeProviderPlannerEndToEndTest` (2 — the full application context boots with the fake provider
  and the `PlannerService` proposes a plan with no key and no network).
- Parser follow-up evidence: `PlannerPlanParserTest` (7 tests) pins the `SCHEMA_MISMATCH`
  classification for the duplicate-id and dangling-dependency payloads and the
  `IllegalArgumentException` null guard.
- `./gradlew :apps:agentflow-app:check` green.

### Docs
- `CHANGELOG.md`: S09-11 entry under `Unreleased / Added` (newest-first).

### Follow-up
- Parser follow-up (done in the same change): [`PlannerPlanParser`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanParser.java)
  now unwraps Jackson 3's `ValueInstantiationException` wrapping, so well-formed JSON that
  violates a plan invariant (duplicated / dangling / self dependency) classifies as
  `SCHEMA_MISMATCH` as documented — before the fix it misclassified as `MALFORMED_JSON`, because
  Jackson 3 wraps record-canonical-constructor violations in a `JacksonException` and the parser
  caught that first, making `SCHEMA_MISMATCH` unreachable for real content. A `null` payload is
  now a contract `IllegalArgumentException`, not a parse failure. Pinned by
  `PlannerPlanParserTest` (duplicate id, dangling dependency, the null guard) and the `INVALID_PLAN`
  fixture paths; retry semantics are unchanged (both classes are repairable exactly once).
- S09-12 (opt-in real-provider contract test) builds on this seam: manual/scheduled, never on PR.


## S09-10

### What changed
- Pre-call model-budget guard. [`ModelBudget`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ModelBudget.java)
  (`ai.application`) is a pure, in-memory value that **reserves before the call**: `reserve()`
  charges the conservative (upper-bound) per-call estimate against the committed `reserved` headroom
  and throws [`InsufficientBudgetException`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/InsufficientBudgetException.java)
  when the reservation would exceed the `limit`, so the caller never sends a model request against
  an exhausted budget. `settle(...)` on the [`ModelBudgetReservation`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ModelBudgetReservation.java)
  handle reconciles the reservation *without ever accumulating* (`reserved = reserved − estimated
  + actual`, floored at 0): a reservation is committed at most once, and `settle(null)` (the price
  catalogue is not yet metered, `S11`) leaves it unchanged — idempotent, never a leak. The
  exception carries `available` / `limit` / `currency` and is thrown *before* the request is sent.
- [`PlannerService`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerService.java)
  now depends on `@Autowired(required = false) ModelBudget budget` instead of the S09-08
  `Supplier<Boolean> budgetAvailable` predicate: `propose` reserves before each attempt and settles
  after the model reports back. The guard is opt-in — a missing bean is "no budget guard" and the
  Planner still runs standalone; a shared production bean makes the reservation the running
  commitment. The S09-08 repair budget check is now this same reserve seam evaluated *before every
  retry*, so a budget spent by the initial call blocks the repair too.

### Why
- The S09-08 repair gate re-checked the budget *before every retry* as a `Supplier<Boolean>`
  predicate; S09-10 makes that a real reserve-with-the-funds guard, so an over-budget plan fails
  loud at call time and never burns the model on a call that is doomed to fail. The refusal is
  permanent and never retried (invariant 12), and no budget figure or id lands in a metric label or
  a log (invariant 9) — monetary amounts travel only in the exception.

### Verification
- Two DB-free suites (23 tests, JUnit XML checked, all green):
  `ModelBudgetTest` (10 — reserve uses the conservative estimate when the price is unknown, a
  reserve that fits within the limit succeeds, a conservative estimate that overdraws a small limit
  blocks and commits nothing before the refusal, a follow-up reserve blocks once the budget is
  fully committed, `settle(null)` leaves the reservation committed, `settle(actual)` reconciles the
  reserved amount, `settle(null)` is idempotent, the constructor rejects a non-positive limit and a
  null currency, a negative conservative estimate, a negative reserve estimate);
  `PlannerServiceTest` (13 — the budget seam is now a `ModelBudget`, the initial call is blocked
  when the budget is exhausted (`calls == 0`), a retry is blocked when the budget was spent by the
  initial call (`calls == 1`), a healthy budget does not interfere with the S09-08 bounded retry
  (`calls == 2`); the obsolete S09-08 budget test is replaced by these three).
- `./gradlew :apps:agentflow-app:check` green — 946 tests, 0 failures, 0 errors; the architecture
  boundary / no-cycles gates ran (`tests>0, skipped=0` in the JUnit XML) and stay green. The
  `ai → usage` compile edge is still forbidden (PackageDependencyGraphTest green), which is why the
  durable reservation / metering / at-least-once settlement lives in `usage` (`S12`) and this task
  owns only the pre-call guard.

### Docs
- `CHANGELOG.md`: S09-10 entry under `Unreleased / Added` (newest-first).
- `docs/domain-glossary.md`: "Model budget guard" row under the AI orchestration section; "Cost
  budget" row updated for the two-layer ownership (pre-call `ai` guard vs durable `usage`
  reservation / settlement).

### Follow-up
- Cost-accurate settlement (the metered `actual` from the price catalogue, `S11`) and the durable,
  at-least-once, deduplicated-by-record-id reservation / reconciliation are `S12` in `usage`; this
  in-memory guard is its pre-call mirror. The exception's `ErrorClass` mapping is added when the
  planner is integrated into the step / HTTP path — `workflowexecution` reaches `ai` only through
  the runtime `StepHandler` SPI (never a compile edge), so the owning `ai` module maps its own
  domain exception.


## S09-09

### What changed
- Provider resilience config: timeout, rate limit (concurrency cap) and bounded retries for every
  model call. [`ProviderResiliencePolicy`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ProviderResiliencePolicy.java)
  (`ai.application`) is the `ai` module's own bounded-retry descriptor — `maxAttempts`,
  `baseDelay`, `multiplier`, `maxDelay`, full jitter — a pure value object with
  canonical-constructor validation (`maxAttempts >= 1`, non-negative delays, `multiplier >= 1.0`,
  `maxDelay >= baseDelay`), value equality, and injected time/randomness seams
  (`delayMillisForAttempt(int, Supplier)`, `nextAttemptTime(Instant, int, Supplier)`,
  invariant 10). It mirrors the `workflowexecution` / `toolexecution` `RetryPolicy` shape, but the
  architecture gate forbids importing those modules' copies, so `ai` owns its own.
- [`ProviderFailureReason`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ProviderFailureReason.java)
  is the neutral, SDK-free failure classification: `RATE_LIMIT` / `SERVER_ERROR` / `TIMEOUT` /
  `CONNECTION` are retryable; `CLIENT_ERROR` / `UNKNOWN` are permanent (an unknown fault fails
  loudly instead of retrying blindly). The SDK-specific mapping lives only in
  [`ProviderExceptionClassifier`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/ProviderExceptionClassifier.java)
  (`ai.provider`, a stateless `@Component`): it walks the whole cause chain and matches the
  specific `RateLimitException` / `InternalServerException` before the generic
  `OpenAIServiceException` status-code branch (429 → `RATE_LIMIT`, 5xx → `SERVER_ERROR`, other 4xx
  → `CLIENT_ERROR`), maps `OpenAIIoException` → `CONNECTION`, `TimeoutException` /
  `SocketTimeoutException` and the wrapper's own `ProviderTimeoutException` → `TIMEOUT`.
- [`ResilientChatModel`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/ResilientChatModel.java)
  wraps any `ChatModelPort` with three independent bounds: (1) bounded exponential backoff + full
  jitter, only while the policy allows another attempt *and* the classified fault is retryable;
  (2) a per-attempt overall timeout enforced on a daemon executor
  (`future.get(timeout)` + `cancel(true)`), where an abort becomes `ProviderTimeoutException`
  (classified `TIMEOUT`, so it is retried within the same finite bound); (3) a `Semaphore`
  concurrency cap that rejects overload immediately with a retryable
  `ProviderConcurrencyLimitedException` backpressure signal. Jitter, sleeper and call executor are
  constructor-injected, so every outcome is deterministic in tests; the production factory
  (`of(...)`) supplies the shared `SplittableRandom` jitter source, `Thread::sleep` and a cached
  daemon pool shut down by `@PreDestroy`.
- [`OpenAiChatModelConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/OpenAiChatModelConfiguration.java)
  wires it all: the connect/read timeouts (`agentflow.ai.timeout.connect-seconds` / `read-seconds`,
  defaults 10 / 120) are applied to the underlying OpenAI SDK client (the single place the SDK
  client is constructed), and the `@Primary` `ChatModelPort` bean is now the resilient wrapper
  around the `OpenAiChatModelAdapter` — the domain sees only the port, never a timeout, an SDK
  type or the raw adapter. All bounds live in `AgentFlowProperties.Ai`
  (`agentflow.ai.retry.max-attempts / base-delay-seconds / multiplier / max-delay-seconds /
  jitter`, `agentflow.ai.timeout.overall-seconds`, `agentflow.ai.max-parallel-calls`), Jakarta-
  validated, never hard-coded.

### Why
- The S09-09 acceptance: a model call must make a *finite* number of attempts and finish within a
  *bounded* duration. An unbounded retry on a provider failure (or a hung call holding a worker)
  breaks that, and an infinite model-call loop is exactly what invariant 7 (untrusted planner)
  forbids. Only transient faults deserve a retry — a 4xx client error is a permanent contract
  failure, and retrying an unknown fault hides real bugs behind a retry storm.

### Verification
- Four DB-free suites (39 tests, JUnit XML checked, all green):
  `ProviderResiliencePolicyTest` (12 — exponential growth, the cap and its saturation, constant
  multiplier, reproducible jitter, `null` jitter = full delay, the `[0, 1)` jitter range, the
  retry-allowed bound, the next-attempt instant, `maxTotalBackoffMillis`, constructor validation,
  value equality); `ProviderExceptionClassifierTest` (16 — every SDK fault class → reason,
  specific-before-generic matching, cause-chain walk into wrappers); `ResilientChatModelTest`
  (8 — the acceptance: a call fails after exactly `maxAttempts` never infinite, the recorded
  inter-attempt waits stay within `maxTotalBackoffMillis()`, a hanging attempt is aborted by the
  overall timeout across finite retries, the concurrency cap throttles immediately, a permanent
  fault is not retried, constructor validation); `OpenAiChatModelConfigurationTest` (3 — the port
  bean is the resilient wrapper delegating to the OpenAI adapter, opt-in gate unchanged).
- `./gradlew :apps:agentflow-app:check` green — 934 tests across the suites, 0 failures, 0 errors;
  the architecture boundary / no-cycles / provider-port gates ran (`tests>0, skipped=0` in the
  JUnit XML) and stay green.

### Docs
- `CHANGELOG.md`: S09-09 entry under `Unreleased / Added` (newest-first).
- `docs/architecture/module-api.md`: the `ai` module's public surface now lists
  `ProviderResiliencePolicy` / `ProviderFailureReason`.

### Follow-up
- S09-10 (pre-call budget reservation) builds on this seam: the resilience layer bounds *time*,
  the budget guard will bound *cost* — a call is blocked before it is sent, not after.

## S09-08

### What changed
- Bounded repair of the S09-06 malformed-plan signal. The four ways a model attempt can fail are distinguished by [`PlannerFailure`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerFailure.java) — `MALFORMED_JSON`, `SCHEMA_MISMATCH`, `PROVIDER_REFUSAL`, `TRANSPORT_FAILURE` — with `isRepairable()` marking the three a retry can fix (`MALFORMED_JSON` / `SCHEMA_MISMATCH` / `TRANSPORT_FAILURE`) and never the refusal.
- [`PlannerRepairPolicy`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerRepairPolicy.java) is the bounded descriptor (`of(int)` with canonical-constructor validation rejecting `maxRetries < 0`; `ofDefaults() == 1` — the production single-second-chance). Pure value object with value equality, no Spring.
- The S09-06 signal [`PlannerPlanParseException`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanParseException.java) now carries the classification (`PlannerFailure classification()`, new three-argument constructor `(message, classification, cause)`), so the caller can tell a provider formatting fault apart from a structural violation or a refusal.
- [`PlannerPlanParser`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanParser.java): a `JacksonException` (unparseable body) becomes `MALFORMED_JSON`; an `IllegalArgumentException` from the structural mapper becomes `SCHEMA_MISMATCH`; both carry the classification.
- [`PlannerService.propose`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerService.java): runs the model at least once, and — only for a repairable failure, only while the budget `Supplier<Boolean>` permits, and at most `repairPolicy.maxRetries()` times — calls it again, then lets the last allowed retry's failure be terminal (never a silent `null` plan, never an infinite model-call loop). The budget is evaluated *before every retry* (a budget spent by the initial call blocks it). The `ai` module boundary is unchanged — `ai` still compiles only against `ai`.

### Why
- Invariant 7 (the model is an untrusted planner) forbids letting a model's own output drive control flow: a malformed or refused plan must not spin forever, and a refusal must not be "repaired" by burning the budget on another attempt that will refuse again.
- `PROVIDER_REFUSAL` is deliberately non-repairable: a model that has decided not to produce a plan is not fixed by another call — retrying only consumes tokens — so the repair loop short-circuits on it and surfaces the refusal clearly.

### Verification
- Three DB-free unit suites (17 tests, JUnit XML checked, all green): `PlannerFailureTest` (the four classifications and their repair decisions), `PlannerRepairPolicyTest` (the policy contract and its validation), and `PlannerServiceTest` (11 — repair once after a malformed / schema-mismatch / transport failure, give up after one retry on persistent malformed output, never retry a refusal, never retry without budget).
- `:apps:agentflow-app:check` green — 898 tests across 119 suites, 0 failures, 0 errors. The S09-06 constructor (`@Autowired(required = false) ChatModelPort`) plus the new `@Autowired` at the constructor level of `PlannerService` is what lets the Spring context (`OpenApiContractCheckTest`, which loads the full context) instantiate the bean with the optional budget `Supplier<Boolean>` missing.

### Docs
- `CHANGELOG.md`: S09-08 entry under `Unreleased / Added`.

### Follow-up
- S09-07 (validation gate) authorizes a plan before any step runs; S09-08 only *re-asks the model for a plan* — it does not execute anything, so it stays within invariant 7's "propose" side.

## S09-05 — The structured plan schema: the Planner's response is a typed, schema-constrained plan, not a free-text blob.

### What changed
Before `S09-05` the domain could call the model (ports `S09-01`, adapter `S09-02`) but there was no
typed shape for the model's *response*: the next task (`S09-06`) will ask the model for a structured
plan through `ChatOptions.outputSchemaJson`, and an untyped blob is exactly where an untrusted planner
can smuggle a capability or a command (invariant 7 / `ADR-006`). `S09-05` defines that schema as three
pure, immutable domain value types in the new package `com.example.agentflow.ai.domain.plan`:

- **`PlanStep`** — one step of a plan. Its component set is exactly `id / type / toolId / dependsOn /
  arguments`. There is nowhere else for anything to live, so there is nowhere for a chain-of-thought,
  reasoning, free-text instructions or an executable payload to hide. `type` and `toolId` are plain
  `String`s, never the `workflowdefinition` node type or a `toolexecution` descriptor reference — the
  registries live in modules the `ai` module is *forbidden* to compile-depend on (the boundary gate),
  so the value type cannot reach them and the membership check is the validation gate's (`S09-07`)
  job, never a model assertion. `dependsOn` and `arguments` are optional: a `null` defaults to an
  empty, *unmodifiable* container (defensively copied, so the caller's mutable input never leaks in),
  while `id` and `type` are mandatory (blank fails read-fast). `arguments` is `Map<String, Object>` —
  structured *data*, never code.
- **`PlannerPlan`** — `List<PlanStep> steps`; its component set is exactly `steps`. The canonical
  constructor enforces structural well-formedness the validation gate need not re-check: unique step
  ids, no dangling `dependsOn`, no self-dependency. Acyclicity, node/tool membership, the `PlanLimits`
  bounds, the budget and the tenant scope are the gate's further, context-dependent checks (it owns the
  cross-module registry and permissions the value type lacks).
- **`PlanLimits`** — the step bound (`maxSteps`) and serialized-output bound (`maxOutputSize`). Both
  are *system-defined*, never model-set, and a non-positive component normalizes back to the
  conservative `DEFAULT` fallback (`agentflow.planner.*` is meant to override in a later task).

All three are pure (no Spring / I/O, read neither time nor randomness, invariant 10) and compile
against `ai` alone, so the module boundary is unchanged.

### Why
The value type is deliberately *not* a full validator: it checks only what is true of the type
regardless of the running system (shape, uniqueness, well-formed wiring, non-positive bounds). Everything
that needs the closed node vocabulary, the registered tools, the configured budget, or the caller's
tenant — the real security checks — is left to the `ValidationGate` (`S09-07`), which is the only place
with the cross-module registry and the verified security context. Splitting it this way keeps the value
type testable without a registry or a security context, and keeps every authorization decision on the
backend (invariant 7: the model is never an authorization authority).

### Verification
Three DB-free unit suites (17 tests): `PlanStepTest` (6), `PlannerPlanTest` (8), `PlanLimitsTest` (3)
— run with
`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.domain.plan.*'`, all green:
shape + component-set invariants, `null`/blank rejection, unmodifiable containers, defensive copy,
duplicate/dangling/self-dependency rejection, forward-reference acceptance, and bound normalization;
`spotlessJavaCheck` green (google-java-format); the module-boundary gate `ArchitectureBoundaryTest`
(really `com.example.agentflow.ArchitectureBoundaryTest`, `@AnalyzeClasses`) green against the new code —
`ai` compiles only against `ai`, `toolexecution`, `workflowexecution`, and the new classes introduce no
cross-module edge. The full fast gate `./gradlew :apps:agentflow-app:check` is green (845 tests, 0
failures).

### Docs
`CHANGELOG.md` (`Unreleased → Added`) records the schema; the task type vocabulary is the closed set the
gate enforces (S09-07).

### Follow-up
`S09-06`: add `PlannerService`, which consumes `ChatModelPort` for structured output and maps the model
response into `PlannerPlan` (the model-to-DTO boundary type `PlannerPlanDto` in `module-api.md` lives
here); `S09-07`: the `ValidationGate` (node/tool membership, DAG acyclicity, `PlanLimits`, budget,
tenant); `S09-08`: bounded repair of a malformed plan.

## S09-07 — The validation gate: the model *proposes*, the backend *authorizes before executing* — an unknown tool, a cycle and an over-limit plan never reach the executor.

### What changed

`S09-06` asked the model for a typed plan and returned it *without executing* it. `S09-07` is the
server-side half of `ADR-006` made live: [`AiPlanExecutionService`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/AiPlanExecutionService.java)
(`ai.application`) runs a [`ValidationGate`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ValidationGate.java)
*before* a single step is allowed to reach the executor, and only a plan the gate passes is handed to
the optional [`PlanExecutor`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlanExecutor.java)
port.

The gate re-checks, purely and DB-free, the boundary plan (`PlannerPlanDto`) the Planner produced:

- **shape** — the plan is non-null and declares at least one step;
- **field contract** — every step carries a non-null, non-blank `id` and `type`;
- **node membership** — each step's `type` is one of the closed node vocabulary (carried in `ValidationContext`);
- **tool membership** — a `TOOL_CALL` step references a tool in the allowlist and carries a `toolId`; a non-tool step carries no `toolId` (a non-tool step naming a tool would smuggle a capability);
- **DAG** — the `dependsOn` graph is acyclic, with no self-loop and no dangling reference;
- **limits** — the step count is within `PlanLimits.maxSteps()` and the serialized output within `PlanLimits.maxOutputSize()`.

The gate is a plain, deterministic value (`new ValidationGate()`), exactly as `PlannerService`
constructs its `PlannerPlanParser`. It reads the external facts it cannot derive from the plan alone —
the tenant, the allowed node types, the allowed tool names and the `PlanLimits` — through
[`ValidationContext`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ValidationContext.java),
a value the caller (the layer with the verified security context and the cross-module registry reach)
assembles and passes in. Keeping the gate free of those edges leaves the `ai` module boundary
unchanged (`S02-08`): the gate validates, it does not compile-depend on the registries it validates
against. A rejected plan is one loud [`PlanValidationException`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlanValidationException.java)
carrying every failing check (`PlanValidationException#violations()`); the fail-safe
[`PlanValidationResult`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlanValidationResult.java)
carries `valid` (true only when `violations` is empty) plus the ordered violations.

`AiPlanExecutionService` is opt-in around the executor too: the `PlanExecutor` port is injected with
`@Autowired(required = false)` (the same pattern `PlannerService` uses for its provider port), so a
missing executor is a clear, call-time `IllegalStateException`, not a boot failure, and the gate
guarantee holds regardless of who implements the port. Any module (e.g. `toolexecution`) implements
`PlanExecutor`, reaching `ai` through its public `application` API, so the validate-before-execute
seam introduces no cross-module compile edge.

### Why

`ADR-006`: the model is an untrusted planner, never an authorization authority. `S09-06` only
*proposed*; `S09-07` *authorizes* the proposal server-side before any step runs. The Planner only
ever sees the allowlist it was given; the gate re-checks that the plan references nothing outside it,
so a hostile or prompt-injected response can never smuggle a tool, a node type or a capability the
backend never approved (invariant 7). The check is split so the value type stays testable without a
registry or a security context (`S09-05`), and every authorization decision stays on the backend.

### Verification

Four DB-free unit suites (31 tests, JUnit XML checked, all green — `tests=N skipped=0 failures=0 errors=0`):

- **`ValidationGateTest`** (`ai.application`, 18 tests) pins the acceptance —
  `unknown_tool_call_step_is_rejected`, `cycle_in_the_dependency_graph_is_rejected`,
  `plan_over_the_step_limit_is_rejected`, `plan_over_the_output_size_is_rejected`, plus the field
  contract (null id, blank type, unknown node, tool-less tool call, non-tool step with a toolId,
  dangling dependency), the tenant-scoped allowlist (a registered-but-disallowed tool fails), the
  empty/null-plan rejection and the passing paths.
- **`AiPlanExecutionServiceTest`** (`ai.application`, 5 tests) proves the seam: a validated plan
  reaches the executor exactly once; an unknown tool, a dependency cycle and an over-limit plan are
  rejected *before* the (spy) executor sees them; a missing executor is a loud failure even for a
  valid plan.
- **`ValidationContextTest`** (`ai.application`, 5 tests) pins the context contract (null tenant /
  null limits rejected, allowlists snapshotted unmodifiable, unset allowlist defaults to empty).
- **`PlanValidationResultTest`** (`ai.application`, 3 tests) pins the fail-safe result shape
  (valid ⇒ empty violations; invalid ⇒ unmodifiable snapshot).

Run with
`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.application.AiPlanExecutionServiceTest' --tests 'com.example.agentflow.ai.application.ValidationGateTest' --tests 'com.example.agentflow.ai.application.ValidationContextTest' --tests 'com.example.agentflow.ai.application.PlanValidationResultTest'`
(all 31 pass, 0 failures); `:apps:agentflow-app:check` green (compilation, unit, the `ai`-only
architecture boundary, spotless formatting, OpenAPI contract).

### Docs

- `docs/devlog/sprint-09-ai-planner.md` — this record.
- `docs/architecture/module-api.md` — the `ai` section names `AiPlanExecutionService` (service),
  `ValidationGate` (gate), `PlanExecutor` (port) and the `PlanValidationResult` /
  `PlanValidationException` / `ValidationContext` permitted types.
- `docs/domain-glossary.md` — `Validation gate`, `PlanExecutor`, `ValidationContext`.
- `docs/adr/ADR-006` — the "skeleton, not yet implemented" consequence is replaced by the gate being
  in force by code.
- `CHANGELOG.md` — added the S09-07 bullet under `Unreleased → Added` (newest-first).
- This file.

### Follow-up

`S09-08`: bounded repair of a malformed plan. `S09-09`: provider timeout / rate-limit / retries.
`S09-10`: pre-call budget reservation (the gate validates the step/limits the planner carries; the
budget reservation is a separate, pre-call guard). `S10`: embeddings / `EmbeddingPort` and the
second provider (`ADR-014`).

## S09-06 — The Planner service: the model proposes a typed plan; the Planner asks for it through the port and returns it without ever executing it.

### What changed

`S09-05` defined the schema; `S09-06` adds the code that *asks the model* for a plan and hands it to
the backend. [`PlannerService`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerService.java)
(`com.example.agentflow.ai.application`) renders the fixed policy from the bundled
`resources/ai/planner.txt` and appends the caller's task plus the allowed-tool list as a single,
untrusted `USER` turn, then performs **one** structured-output call through the neutral
[`ChatModelPort`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ChatModelPort.java)
and maps the response into a [`PlannerPlan`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/domain/plan/PlannerPlan.java)
— never a step it runs. The plan crosses the module boundary as
[`PlannerPlanDto`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanDto.java)
(typed, allowed node types only, no provider SDK type); the attempt metadata (model id, the
`PromptVersion` used, the token `ChatUsage`) rides in
[`PlannerPlanResult`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanResult.java),
so the attempt is traceable without the plan ever being executed. The model returning no content, or
content that is not a valid plan (bad JSON, a structurally invalid graph, an unknown node), is one
clear signal — [`PlannerPlanParseException`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerPlanParseException.java)
— never a silent `null` plan or a guessed one.

The Planner depends on the prompt catalogue through a narrow
[`PromptCataloguePort`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PromptCataloguePort.java)
defined in `ai.application` and implemented by `ai.prompt.PromptCatalogue` — so it never imports
`ai.prompt`, and the `S02-09` acyclicity gate (enforced by the architecture tests) stays green:
`ai.prompt` still depends only on `ai.application`, never the reverse. It depends on the model
through `ChatModelPort`, which is opt-in (`ADR-014`); a missing provider is a loud, call-time
failure, not a boot failure.

### Why

`ADR-006`: the model is an untrusted planner, never an authorization authority. The Planner only ever
*proposes*; the `ValidationGate` (`S09-07`) re-validates every node/tool/argument/budget/step-limit
and re-authorization server-side before any step runs. Keeping the plan as a proposal — with a
single, loud failure signal for anything the model cannot express as a valid plan — means a hostile
or prompt-injected model response can never smuggle an executable payload or a guessed step past the
gate (invariant 7).

### Verification

Two DB-free unit suites (10 tests): [`PlannerPlanParserTest`](apps/agentflow-app/src/test/java/com/example/agentflow/ai/application/PlannerPlanParserTest.java)
(structured-output parse: success, structural violation, malformed JSON, duplicate id) and
[`PlannerServiceTest`](apps/agentflow-app/src/test/java/com/example/agentflow/ai/application/PlannerServiceTest.java)
(policy+USER split, metadata mapping, missing-provider failure, refusal-as-parse-signal, blank-task
guard). The architecture and acyclicity suites (`AiProviderPortArchitecturalTest`,
`NoCyclicModuleDependenciesTest`, `PackageDependencyGraphTest`, `AiProviderPortContractTest`) stay
green.

### Docs

- `docs/devlog/sprint-09-ai-planner.md` — this record.
- `docs/architecture/module-api.md` — `ai` section gains `PlannerService` (service) and
  `PlannerPlanResult` / `PlannerPlanParseException` (permitted public types).
- `docs/domain-glossary.md` — `PlannerService`, `PlannerPlanDto`/`PlannerPlanResult`,
  `PromptCataloguePort`.
- `CHANGELOG.md` — added the S09-06 bullet under `Unreleased → Added` (newest-first).

### Follow-up

`S09-07`: the `ValidationGate` (node/tool membership, DAG acyclicity, `PlanLimits`, budget, tenant).
`S09-08`: bounded repair of a malformed plan. `S10`: embeddings / `EmbeddingPort` (ingestion and
`LLM_EVALUATE`) and the second provider (`ADR-014`).

## S09-04 — The versioned prompt catalogue: prompt templates are bundled as fixed, hashed policy files, separated from untrusted user content, and every model attempt records the prompt version/hash it planned against.


### What changed
Sprint 9 builds the AI layer on top of the durable workflow engine. Before `S09-04` the domain could
call the model (through the ports, `S09-01`) and the concrete adapter existed (`S09-02`), but there was
no controlled way to ship the model's *policy*: prompts were ad-hoc strings with no version identity and
no enforced split between the policy the backend controls and the untrusted content it forwards. `S09-04`
introduces the **prompt catalogue** as the smallest complete change that closes that gap and satisfies
the acceptance — *every model attempt indicates the prompt version/hash it planned against*.

Three pieces make it up:

- **Bundled, hashed templates.** The policy ships as a plain text resource at
  [`resources/ai/planner.txt`](apps/agentflow-app/src/main/resources/ai/planner.txt). It is a *fixed
  SYSTEM message*: the file content is the source of truth for the prompt hash, and the task plus any
  retrieved document fragments are appended by the backend as a separate, untrusted USER message
  (the prompt-injection surface).
- **`PromptCatalogue`** (`com.example.agentflow.ai.prompt`, DB-free): loads every declared prompt from
  {@code resources/ai/<name>.txt}, derives each {@code hash} as the SHA-256 of the file bytes, and
  renders a prompt with {@code build(name, userContent)} into exactly
  {@code [SYSTEM(policy), USER(userContent)]}. Policy and user content never share one message, so the
  model can never read its own policy as user content. The catalogue never logs, hashes or otherwise
  exposes the injected content, so the untrusted document content stays out of observability (invariant
  8). A missing template throws at construction, so an incomplete {@code ai.prompt} declaration stops
  the app booting (fail fast, like every other required configuration).
- **`PromptVersion`** (`com.example.agentflow.ai.application`, the provider-agnostic port from `S09-01`):
  the immutable contract record carrying {@code name / version / hash} — the observable surface of a
  prompt. It deliberately holds *no* prompt text and *no* user/document content. A model attempt carries
  one of these, so the prompt it planned against is recorded and traceable. The {@code version} is
  assigned in configuration (bump whenever the template changes); the {@code hash} is derived from the
  content, so a content change is always detectable even before the version is bumped.

The catalogue is wired from the {@code ai.prompt} configuration through {@link
com.example.agentflow.ai.prompt.AiPromptProperties AiPromptProperties}
({@code @ConfigurationProperties} + {@code @Validated} + {@code @Valid}) and
[{@link com.example.agentflow.ai.prompt.PromptConfiguration PromptConfiguration}]. Each entry declares a
{@code name} and a human-assigned {@code version}; {@code PromptRef} validates both with {@code @NotBlank},
so a blank {@code version} fails context startup as a {@code ConfigurationPropertiesBindException} — the
project's typed-configuration idiom, the same path every other required property uses (`S01-17`). The
template and declaration are pinned in
[{@code application.yml}](apps/agentflow-app/src/main/resources/application.yml):
{@code ai.prompt.prompts[0]: planner / version "1"}.


### Why
The model is an untrusted planner (`ADR-006`) and the injected task / retrieved-document fragments are
prompt-injection content: the security-critical boundary is that the *policy* the model must obey ships
from a controlled, versioned resource, never merged into — or indistinguishable from — the user input.
Without a catalogue there was no way to (a) record *which* policy a given attempt planned against, which
an audit / regression on a prompt change requires, or (b) make a prompt change detectable: a bare string
edited in code is an unreviewed, unversioned, unhashed drift. Bumping a declared {@code version} on a
content change, and deriving a {@code hash} over the file, turns a prompt edit into a diffable,
traceable, fail-fast-configured change — and the SYSTEM/USER split keeps the policy from being smuggled
back to the model as user content. The {@code PromptVersion} record lives in the `ai.application` port
(S09-01) rather than a provider adapter, so the attempt's prompt identity is recorded without coupling
the domain to a specific provider, and it carries no prompt or content so the per-attempt trace never
leaks them (invariant 8).


### Verification
Unit and architectural, DB-free, no model / API key / Kafka / system-clock dependency:

- **`PromptCatalogueTest`** (`ai.prompt`, 6 tests): loads the bundled `planner` template, asserts the
  derived {@code hash} equals the real SHA-256 of the template file bytes (not fabricated), that
  {@code build} produces exactly two messages with the SYSTEM content equal to the loaded template and the
  USER content equal to the injected string, that blank user content and an unknown prompt are rejected,
  and that an empty catalogue has no content.
- **`AiPromptConfigurationTest`** (`ai.prompt`, 3 tests, {@code ApplicationContextRunner}): the
  {@code ai.prompt.prompts[0]} binding produces a {@code PromptCatalogue} with the declared prompt and a
  64-char hash; a blank {@code version} fails startup through the real {@code @Validated} binding path
  ({@code ConfigurationPropertiesBindException}); an unknown prompt name fails startup with
  "prompt template not found on classpath".
- **`PromptVersionTest`** (`ai.application`, 7 tests): the record exposes exactly
  {@code name / version / hash}, and rejects a null/blank name, a blank version, and a non-hex / wrong-
  length hash.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.prompt.PromptCatalogueTest'
--tests 'com.example.agentflow.ai.prompt.AiPromptConfigurationTest'
--tests 'com.example.agentflow.ai.application.PromptVersionTest'` green — **16 tests, 0 failures (JUnit
XML checked)**; `:apps:agentflow-app:check` green (compilation, unit, architecture gates, spotless
formatting, OpenAPI contract).


### Docs
- [`apps/agentflow-app/src/main/resources/application.yml`](apps/agentflow-app/src/main/resources/application.yml)
  — the {@code ai.prompt.prompts} declaration (the pinned template + version).
- [`apps/agentflow-app/src/main/resources/ai/planner.txt`](apps/agentflow-app/src/main/resources/ai/planner.txt)
  — the bundled, versioned planner policy (the header documents the "bump version on change" rule).
- [`apps/agentflow-app/AGENTS.md`](apps/agentflow-app/AGENTS.md)
  — noted the bundled prompt templates with a version/hash under `ai.prompt`.
- [`docs/security/secrets.md`](docs/security/secrets.md) — the S09-04 prompt-versioning hygiene rule
  (versioned / hashable templates) recorded alongside the provider-secrets handling.
- `CHANGELOG.md` — added the S09-04 bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Define the Planner's response schema (`S09-05`); add the `PlannerService` (`S09-06`), which consumes
`ChatModelPort` and records the `PromptVersion` on the attempt; apply the validation gate after the
Planner (`S09-07`) and the bounded repair policy (`S09-08`).
## S09-01 — The AI provider port: the `ai` domain calls a neutral contract, never a provider DTO (S09-01).


### What changed
Sprint 9 builds the AI layer on top of the durable workflow engine, and the `ai` module is still a
skeleton (`ai/AGENTS.md`, `ai/package-info.java`). S09-01 defines **how the domain calls the model
provider** so that the security-critical layers (planning, authorization, execution, verification —
`ADR-006`) depend on a contract that no specific provider can reach. Two narrow outbound ports are
defined in the module's public `application` boundary:

- **`ChatModelPort`** — text generation, one `chat(ChatRequest)` returning a `ChatResponse`. Planning
  and evaluation are both chat completions, so they share this one port.
- **`EmbeddingPort`** — embeddings, one `embed(EmbeddingRequest)` returning an `EmbeddingResponse`.

The contract types are pure, provider-agnostic records in `com.example.agentflow.ai.application`
that import no Spring and no provider package: `ChatMessage`, `ChatOptions`, `ChatRequest`,
`ChatResponse`, `ChatUsage`, `MessageRole`; and `EmbeddingRequest`, `EmbeddingResponse`,
`EmbeddingUsage`. The schema a Planner asks for as structured output rides on `ChatOptions.outputSchemaJson`
as a neutral JSON **string** that the provider adapter renders into the SDK's structured-output form
*behind the port*, so the contract never imports a provider's schema or message type — structured
output is expressed, never provider-specific.

Required fields are validated up front (fail-fast): `ChatRequest` / `EmbeddingRequest` reject a blank
model and empty inputs; collections are returned unmodifiable; `float[]` embeddings are copied on the
way out, so the holder owns its data. None of this is provider behaviour — it is the invariant-safe
shape of the contract (invariants 1, 8, 10). The concrete provider adapter is the **only** code that
will import a provider SDK, and it stays behind the port (added in `S09-02`). The ports are deliberately
narrow (one method each, matching the two ports in `docs/architecture/module-api.md`) and are **not** a
generic "LLM" abstraction — the port exists because a provider must be behind a boundary, not because a
second provider already exists (YAGNI).


### Why
Before this task the plan assumed the Planner (`S09-06`), the `LLM_EVALUATE` node and the
ingestion / knowledgebase embedding callers (`S10`) would call a model, but there was no boundary to
keep them decoupled from any provider. If the domain imported a provider SDK's request/response DTOs
directly, every security-critical layer would be coupled to one provider: swapping, retrying or
second-sourcing the provider would mean editing the Planner, the validation gate and the embedding
callers at once — exactly the coupling the untrusted-planner stance (`ADR-006`) exists to prevent. The
plan calls it out for `S09-01`: *define the interface for planning / evaluation / embedding calls;
isolate the domain from the SDK of a specific provider; do not build an over-generic abstraction
before the second implementation appears.* One port per concern (chat vs. embedding) matches the two
ports already documented in `module-api.md` and avoids over-engineering a problem that currently has
one implementation.


### Verification
Unit and architectural, DB-free, no model / Kafka / system-clock dependency:

- **`AiProviderPortContractTest`** (`ai.application`, 12 tests) pins the neutral contract: a `ChatMessage`
  exposes its role and content and rejects a null role / blank content; `ChatOptions` accepts a blank
  slate and carries a structured-output schema; `ChatRequest` validates its required fields (null model,
  blank model, null messages, empty messages), holds its messages unmodifiable (the caller's list is
  neither mutated nor leaked), a null `content` is an accepted refusal, and the model label is
  validated; `EmbeddingRequest` validates its required fields and `EmbeddingResponse` copies each
  `float[]` on the way out and validates the model label.
- **`AiProviderPortArchitecturalTest`** (`architecture`, 1 test) asserts that no class in the `ai`
  module's `application` package references a provider SDK package (`org.springframework.ai`,
  `com.openai`, `com.anthropic`, `com.google.ai`, `cohere`) — guarding the contract against a future
  adapter that leaks a provider DTO into it. The guard is scoped to the `application` package on
  purpose: `S09-02` added the sanctioned `provider` adapter (`OpenAiChatModelAdapter`) as the single
  class allowed to import the SDK. A guard `assertThat(classes).isNotEmpty()` prevents the gate from
  passing vacuously.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.application.AiProviderPortContractTest' --tests 'com.example.agentflow.architecture.AiProviderPortArchitecturalTest'` green
(13 tests, 0 failures — JUnit XML checked); `:apps:agentflow-app:check` green (compilation, unit,
architecture gates, spotless formatting, OpenAPI contract).


### Docs
- `docs/adr/ADR-014-provider-port.md` — the decision: two narrow neutral ports, structured output as a
  neutral `String`, fail-fast contract shape, the adapter behind the port (`Context → Decision →
  Alternatives → Consequences → Accepted`).
- `docs/adr/README.md` — indexed `ADR-014` in the register.
- `apps/agentflow-app/src/main/java/com/example/agentflow/ai/AGENTS.md` — noted the defined ports and
  their `application`-package placement.
- `CHANGELOG.md` — added the S09-01 bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Wire the concrete Spring AI adapter (`S09-02`, opt-in, keys from secrets) behind the ports; version the
prompts (`S09-04`) and define the Planner's response schema (`S09-05`); add the `PlannerService`
(`S09-06`), which consumes `ChatModelPort`; cap the Planner's step/time/cost budget.


## S09-03 — Close the provider secrets: the OpenAI API key is injected, opt-in, masked, and guarded by a canary so it never reaches Git, logs, diagnostics, or the image build.


### What changed
`S09-03` closes the three ways the OpenAI provider API key could leak: into Git / build, into the
logs / stack traces / diagnostic endpoints, and into the image build. The key is read from
`agentflow.ai.provider.openai.api-key` (falling back to `OPENAI_API_KEY`) by the opt-in
[`OpenAiChatModelConfiguration`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/OpenAiChatModelConfiguration.java)
(`@ConditionalOnProperty agentflow.ai.provider=openai`) — with no provider profile there is no
`ChatModel` bean and no key on the classpath, so the core app boots exactly as before. It is masked
by [`SecretRedactingValueMasker`](apps/agentflow-app/src/main/java/com/example/agentflow/observability/SecretRedactingValueMasker.java)
(registered as the `<valueMasker>` in [`logback-spring.xml`](apps/agentflow-app/src/main/resources/logback-spring.xml))
so a key embedded in a message is masked before stdout; only `health` and `info` are exposed over the
Actuator ([`application.yml`](apps/agentflow-app/src/main/resources/application.yml)), so `env` /
`configprops` cannot be read back; and unhandled errors go through the `internal`/500 path
(`GlobalExceptionHandler`) without the raw exception message. The image is not built yet (no
Dockerfile in the repository); the Compose / Helm manifests inject secrets through runtime environment
placeholders, never build args.

The acceptance — *a canary API key is not found in Git, logs, or diagnostic endpoints* — is pinned by
a planted canary in exactly one place,
[`src/test/resources/secrets/canary-openai-key.txt`], read at test time (never in a source file):

- `CanaryLeakScanTest` (`ai.security`, DB-free): `git ls-files` over the whole tree, every path except
  the fixture scanned and asserted canary-free; plus the canary is masked by the real
  `SecretRedactingValueMasker` (whole-value and embedded in a larger message).
- `ProviderSecretsActuatorTest` (`ai.security`, `@SpringBootTest`, no provider configured): the canary
  is not echoed by `/health` or `/info`, and `/env` / `/configprops` are closed.


### Why
`S09-02` wired the key through the opt-in provider config, but the security-critical question — *where
does the key actually stop leaking?* — was still open. The model is an untrusted planner (`ADR-006`)
and invariant 8 says a secret never lands in a row, log, metric, trace, Kafka payload or prompt: the
key had to be proven, not just configured. The cheapest durable proof is a canary: plant a unique fake
key; if it ever appears in Git, logs, or the diagnostic surface, the regression fails — which is
exactly what happens when a real key is committed by mistake.


### Verification
`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.security.CanaryLeakScanTest' --tests 'com.example.agentflow.ai.security.ProviderSecretsActuatorTest'`
— green (6 tests: 4 + 2). The scan boots the app on the `test` profile with no provider, so it needs
no model, API key or network. `:apps:agentflow-app:check` green (compilation, unit, architecture
gates, spotless formatting, OpenAPI contract).


### Docs
- `docs/security/secrets.md` — the secrets handling note: where the key lives, local storage, the logs
  / stack-trace / Actuator hygiene, the build-arg rule, the canary guard, and the acceptance.
- `CHANGELOG.md` — added the S09-03 bullet under `Unreleased → Security` (newest-first).
- This file.


### Follow-up
Version the prompts (`S09-04`); define the Planner's response schema (`S09-05`); add the
`PlannerService` (`S09-06`); cap the Planner's step/time/cost budget.

## S09-02 — The concrete AI provider adapter behind the port: a Spring AI (OpenAI) implementation of `ChatModelPort`.


### What changed
`S09-02` wires the first (and only) provider implementation behind `ChatModelPort` (`S09-01`). The
concrete adapter `OpenAiChatModelAdapter` (`ai.provider`) implements the port's `chat(ChatRequest)` and
maps the neutral request/response onto the Spring AI types. It is the only class in the module that
imports `org.springframework.ai` — the provider SDK stays behind the port, never reaching the
`application` contract (invariant 1: the domain depends on a contract, not a provider DTO).

The mapping is faithful to the contract's invariants:

- a `null` completion text (the provider refused or returned an empty completion) is returned as `null`
  content, never an empty string, so the domain treats it as malformed output (`S09-08`);
- the token accounting is derived from the provider `Usage` and is `null` when the provider reports
  none — Spring AI's `EmptyUsage` (zero tokens on every field) is the "reports none" sentinel and maps
  to `null`, so a missing usage is never read as a free completion by cost accounting;
- the structured-output schema and per-call tuning (temperature, max tokens) ride the neutral
  `ChatOptions`; each neutral role is rendered into the provider's role vocabulary (never the reverse);
- the constructor rejects a `null` model (`NullPointerException`).


### Why
Before `S09-02` the `ai` module was a skeleton: the domain could call only through the abstract port,
with no provider behind it, so the Planner (`S09-06`), evaluation (`LLM_EVALUATE`) and — via the
`EmbeddingPort` — ingestion could not run. The concrete adapter is the smallest complete change that
makes the port real, and it is deliberately provider-narrow (only OpenAI / Spring AI today), not an
over-generic "LLM" abstraction: a second provider should appear before the abstraction is generalized
(YAGNI). It stays opt-in (`OpenAiChatModelConfiguration`, `@ConditionalOnProperty`
`agentflow.ai.provider=openai`) so the core app still boots on the default profile with no AI key and
no `ChatModel` bean, and the provider's API key / base URL are read from the environment or a secret,
never hardcoded.


### Verification
Unit and architectural, DB-free, no model / API key / network dependency:

- **`OpenAiChatModelAdapterTest`** (`ai.provider`, 7 tests) drives the real mapping against a fake
  `ChatModel` that captures the built `Prompt` and returns canned Spring AI responses — completion to
  contract, `null` content on refusal, all three roles, tuning + schema on the prompt, `null` usage
  when the provider reports none, model fallback, and the null-model constructor guard.
- **`OpenAiChatModelConfigurationTest`** (`ai.provider`, 3 tests) uses `ApplicationContextRunner` to
  pin the opt-in gate: the `ChatModel` and `ChatModelPort` beans exist only when
  `agentflow.ai.provider=openai`, and are absent otherwise.
- **`AiProviderPortArchitecturalTest`** (`architecture`) was re-scoped: the no-provider-SDK guard now
  applies to the `application` package (the sanctioned `provider` adapter is the single importer), so
  the boundary is still protected against a DTO leak while the real adapter compiles.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.provider.*' --tests 'com.example.agentflow.architecture.AiProviderPortArchitecturalTest'` green; `:apps:agentflow-app:check` green (803 tests, 0 failures; spotless formatting clean).


### Docs
- `CHANGELOG.md` — added the `S09-02` bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Version the prompts (`S09-04`) and define the Planner's response schema (`S09-05`); add the
`PlannerService` (`S09-06`), which consumes `ChatModelPort`; cap the Planner's step/time/cost budget.

