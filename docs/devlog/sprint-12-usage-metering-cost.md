# Sprint 12 — usage metering, budgets and cost

## Contents

- [S12-01](#s12-01-the-usage-record-schema) — the usage record schema: migration `V3_3_24` tying each measurement to a specific attempt (S12-01)
- [S12-02](#s12-02-the-uniform-usage-recording-path) — the uniform usage recording path: `UsageMeteringService` / `UsageRecordDto`, unknown recorded as unknown, the `domain` placement that keeps the module acyclic
- [S12-03](#s12-03-the-usage-price-catalogue-and-versioning) — the usage price catalogue: `PricingResolver` / `PricingInfo` / `UnitDivisor`, migration `V3_3_25` `price_version` / `pricing_revision`, the priced `fromChatResponse`

---

## S12-01 — the usage record schema

The durable token/cost metering table (glossary §10, plan Sprint 12, `S12-01`). `usage` owns this
table — the same owner as `tenant_budgets` and the future settlement/quota tasks (`S12-02`…`S12-06`).
One row per measurement of one attempt: a single model call, tool call or embedding that produced
`input_tokens` / `output_tokens`, an estimated cost, the pricing unit and currency. It is the
durable record the later budget / settlement / quota tasks read and reconcile, and the audit of what
a run spent.

The row pins the measurement to a **specific attempt**: `tenant_id`, `run_id`, `step_run_id` and
`attempt` are all present, so a record is never a floating number but always a measurement of one
concrete attempt. The `attempt` is `NOT NULL` — a usage record without an attempt is not a
measurement of anything concrete; it is the column that ties the record to a specific attempt (the
`S12-01` acceptance). The record is metadata only — it **never** stores the raw prompt, the raw
document or a full conversation (invariant 8). It stores the token counts and the cost, never the
content that was measured.

Columns:

- `id` — the usage record id (UUIDv7 primary key).
- `tenant_id` — the owning tenant (FK `tenants.id`); every row is tenant-scoped (invariant 1). Unlike
  `run_id` below, this IS a foreign key: `tenants` is the shared lookup table that scopes every
  business row, and the FK enforces tenant existence at the storage layer (`database-conventions.md`,
  rule 5).
- `run_id` — the run the measurement belongs to (NOT a foreign key — `workflow_runs` is owned by
  `workflowexecution`; a cross-module FK would couple the schemas, exactly as `tool_invocations`
  scopes `run_id` without one). A tenant-scoped read filters on it, never a JOIN.
- `step_run_id` — the step run the measurement belongs to (nullable — the context may carry none
  yet); `TEXT`, not `UUID`, matching the `step_run_id` convention used by `tool_invocations`.
- `attempt` — the per-attempt number (1-based) the measurement was recorded for; `NOT NULL`.
- `usage_type` — what was measured: `MODEL` / `TOOL` / `EMBEDDING`; bounded by `usage_records_usage_type_check`.
- `measurement` — how the recorded values were produced: `ESTIMATED` (computed locally from the price
  catalogue — the pre-call reservation) or `PROVIDER_REPORTED` (actual values from the provider); the
  storage-layer expression of the "distinguish estimated from provider-reported usage" rule. DEFAULT
  `ESTIMATED` so a record written before a provider reply is truthful.
- `provider` — the provider name (`OPENAI`, `ANTHROPIC`, `FAKE`, …); free `TEXT` — providers are
  open-ended, not a bounded vocabulary.
- `model` — nullable `TEXT`, present for `MODEL` usage (e.g. `gpt-4o`); absent for `TOOL` usage.
- `tool_name` / `tool_version` — nullable, present for `TOOL` usage.
- `input_tokens` / `output_tokens` — nullable `BIGINT`; `NULL` means the value was unknown (a provider
  reported no count), NOT zero — an unknown is recorded as unknown, never invented (`S12-02`).
- `estimated_cost` — nullable `NUMERIC`; non-NULL when `measurement = 'ESTIMATED'`.
- `provider_cost` — nullable `NUMERIC`; non-NULL when `measurement = 'PROVIDER_REPORTED'`.
- `unit` — nullable `TEXT`, the pricing unit the cost is expressed in (e.g. `token`, `1k-tokens`).
- `currency` — nullable `TEXT`, the cost currency (e.g. `USD`); money is `NUMERIC`, never `float`
  (invariant 10, `engineering-conventions.md`).
- `provider_request_id` — nullable `TEXT`, for traceability / reconciliation.
- `provider_metadata` — nullable `JSONB`, application-capped.
- `created_at` — immutable creation instant (`timestamptz`, UTC); the row is append-only, so it carries
  no `updated_at` (`database-conventions.md`).

No raw prompt, no document, no full conversation on the row — only the token counts and the cost
(invariant 8). `IF NOT EXISTS` keeps Testcontainers rebuilds and the controlled apply step quiet
(`database-migrations.md`, rule 5). Forward-only (`ADR-008`). One lookup index `usage_records_tenant_run_created_idx`
on `(tenant_id, run_id, created_at)`, the tenant predicate leading (invariant 1).

**Verification.** The acceptance lives in the schema and in data. `MigrationsTest` (24 tests,
JUnit-XML-verified, `tests="24"` `failures="0"` `errors="0"`):

- `usage_records_schema_guards_exist` — asserts the lookup index, the two CHECKs, the NOT NULL
  `attempt` / `tenant_id` / `run_id` / `created_at` columns, the nullable token / cost columns, and
  the table / `attempt` COMMENT attributing the table to `S12-01`.
- `usage_records_link_to_attempt_and_tenant_hold_against_data` — on the live database, a valid
  `MODEL` record for (run, step, attempt 1) lands, a record for the same (run, step) with a different
  attempt lands too (attempt is a real dimension, not a duplicate key), a provider-reported `TOOL`
  record with unknown tokens (NULL) lands, a record with a missing parent tenant is rejected by the
  foreign key, an unknown `usage_type` by the CHECK, and a missing `attempt` by NOT NULL.

`./gradlew :apps:agentflow-app:flywayMigrate` applies the ordered line at `v3.3.24` (25 migrations,
0 failed). The migration file is `apps/agentflow-app/src/main/resources/migrations/V3_3_24__create_usage_records.sql`.

**Docs.** The glossary §10 "Usage record" row (full column set, `recorded_at` reconciled to the
unified audit `created_at`; the two measurement sources explained); this file. Follow-up `S12-02`
(settle/estimate), `S12-03` (price catalogue), `S12-04` (budgets), `S12-06` (settlement) build on
this table.

## S12-02 — the uniform usage recording path

The code that writes the table above (glossary §10, plan Sprint 12, `S12-02`). The `usage` module
records one token/cost measurement per call — uniformly for a model, a tool or an embedding — through
one validated path, so the `usage_records` rows are faithful and the module does not leak a package
cycle.

The module gains:

- `application.UsageMeteringService` — the single recording service (the `usage → ai` edge of
  module-map arrow 8): `fromChatResponse` builds the `MODEL` measurement of one chat-completion call
  from the provider `ChatResponse` through the `ai.application` `ChatResponse` / `ChatUsage` port,
  and a tool path records a `TOOL` invocation. Each call produces exactly one `UsageRecordDto`.
- `domain.UsageRecordDto` — the immutable measurement value, the mirror of the `usage_records` row:
  `id`, `tenantId`, `runId`, `stepRunId`, `attempt`, `usageType` (`MODEL` / `TOOL` / `EMBEDDING`),
  `measurement` (`ESTIMATED` / `PROVIDER_REPORTED`), `provider`, `model`, `toolName`, `toolVersion`,
  `inputTokens`, `outputTokens`, `estimatedCost`, `providerCost`, `unit`, `currency`,
  `providerRequestId`, `providerMetadata`.
- `domain.UsageType` (`MODEL` / `TOOL` / `EMBEDDING`) and `domain.Measurement` (`ESTIMATED` /
  `PROVIDER_REPORTED`) — the two bound enums.

**Unknown is recorded as unknown.** The token counts are boxed `Long` (nullable): a value the
provider did not report is stored as `null`, never coerced to `0` (invariant 8). `fromChatResponse`
therefore leaves both `inputTokens` and `outputTokens` `null` on a refusal (`ChatResponse.usage()`
`== null`), and a pre-catalogue record carries `estimatedCost == null` until the price is known — the
record is truthful with no reported values yet (DEFAULT `ESTIMATED`).

**Contract failures, classified.** The canonical constructor rejects a `null` id / tenant / run, a
stale `attempt` (< 1), a `null` enum, a blank `provider`, or a missing measured identity — a
`MODEL` / `EMBEDDING` record without a `model`, a `TOOL` record without a `toolName` — with an
explicit `IllegalArgumentException`, the same pattern `ToolDescriptor` / `ReportFormatter` use. It is
deliberately *not* `Objects.requireNonNull` (in JDK 25 that throws `NullPointerException`, which would
mis-classify a contract failure as a generic fault so the worker could not route it to the
`FAILURE_CLASS.CONTRACT_FAILURE` path).

**No package cycle.** The shared record lives in `domain` (like `AuditEvent` / `ToolInvocationRecord`),
so both `application` (the metering service) and `persistence` (`UsageRepository` / `UsageRowMapper`)
depend on `domain` — and only on `domain` — without either importing the other. An earlier
`application` placement made `application ↔ persistence` a cycle the acyclicity gate rejected.

**Verification.** Four DB-free suites, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`,
fresh reports this session), 22 tests total: `UsageRecordDtoTest` (13 — the 19-field constructor,
`fromChatResponse` from a real `ChatResponse`, the refusal leaves tokens `null`, every
contract-failure branch, `usageType` / `measurement` round-trip through the enums);
`UsageMeteringServiceTest` (3 — uniform recording via `@InjectMocks`); `UsageRowMapperTest` (2);
`UsageRepositoryTest` (4 — the tenant-scoped select with the verified SQL). The full
`./gradlew :apps:agentflow-app:check` gate — unit + architecture (`ArchitectureBoundaryTest`
`tests="1"`, `NoCyclicModuleDependenciesTest` `tests="1"` `failures="0"` `errors="0"`, the `usage`
package now `application ↔ domain` and `persistence ↔ domain` only) + no-cycles + formatting
(`spotlessApply`) — green; `git diff --check` clean.

**Docs.** `docs/architecture/module-api.md` (the `usage` public-application-services row — uniform
model/tool/embedding recording — and the permitted-types row, the full `UsageRecordDto` field set and
its `domain` placement); `docs/architecture/module-map.md` (arrow 8 `usage → ai`); the `usage` module
`AGENTS.md`.

## S12-03 — the usage price catalogue and versioning

The price catalogue the `S12-02` recording path settles against (glossary, plan Sprint 12, `S12-03`).
A usage record that carries `estimated_cost` was computed from *some* price — this task records *which*
one, so every historical estimate stays explainable through the exact price version that produced it.

The `usage` module gains the resolution layer:

- `application.PricingResolver` — pure, DB-free: given a resolved `PricingInfo` it applies the unit
  divisor (e.g. a `1k-tokens` price is per 1,000 tokens) and returns the resolved cost, mirroring how
  the pre-call budget reservation (`S09-10`) estimated a model call.
- `application.PricingProperties` — the `agentflow.usage.pricing` config group (currency, and the
  price catalogue keyed by `model` → `pricing_revision`); typed and validated, like every other
  `agentflow.*` group.
- `domain.PricingInfo` — the resolved price the record carries: `price_version` (the specific
  `model+version`, e.g. `gpt-4o/2026-01-01`), `pricing_revision` (the catalogue revision tag), `unit`,
  `currency` and the resolved cost.
- `domain.UnitDivisor` — how many tokens one price unit covers (`1` for `token`, `1_000` for
  `1k-tokens`); the closed vocabulary the resolver maps the `PricingInfo.unit` string to.

**The migration.** `usage_records` gains migration `V3_3_25` (owner `usage`, forward-only, `IF NOT
EXISTS` for quiet Testcontainers rebuilds): two nullable `TEXT` columns.

- `price_version` — the specific `model+version` price the estimate was computed against; `NULL` when
  the catalogue does not yet know the model, so an unpriced estimate is recorded as **unknown**, never
  invented (invariant 8, `S12-02`).
- `pricing_revision` — the revision tag of the `usage.pricing` catalogue that produced the estimate;
  `NULL` when no price was resolved.

Both are nullable on purpose: a `NULL` here means "no price was resolved" — a real state (the `S12-02`
unknown path), not a missing value to be rejected. They are written together by the `usage` module, so
a row never stores one without the other. The column `COMMENT`s attribute both to `S12-03`.

**The record.** `UsageRecordDto` gains `priceVersion()` / `pricingRevision()`, plus a **priced**
factory — `fromChatResponse(tenantId, runId, stepRunId, attempt, model, response, pricing)` fills the
two versioning columns from a resolved `PricingInfo`. The unchanged 19-argument positional constructor
(the common `S12-02` recording) leaves them `null` — the unpriced path.

**Unknown is recorded as unknown.** A record with no resolved price stores `null` for both versioning
columns, so the row truthfully records that no price was applied rather than a guessed one.

**Verification.** DB-free unit suites, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`,
fresh reports this session), **33 usage tests total** — `UsageRecordDtoTest` (15, including the two
new priced `fromChatResponse` cases — `estimatedCostFor` maths and the unpriced record leaving
versioning `null`); `PricingResolverTest` (8 — the divisor / latest-price / currency resolution and the
`null`-price unknown path); `UsageRowMapperTest` (3 — the two columns round-trip, `null`-safe);
`UsageRepositoryTest` (4 — the two columns bind last, `null`-safe); `UsageMeteringServiceTest` (3).
The real-PostgreSQL `MigrationsTest` (`integrationTest`) asserts `V3_3_25` is applied and the two
columns are nullable with `S12-03` attribution.

**Docs.** `CHANGELOG.md` (Unreleased); this file.
