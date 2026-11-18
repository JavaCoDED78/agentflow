# Sprint 12 — usage metering, budgets and cost

## Contents

- [S12-01](#s12-01-the-usage-record-schema) — the usage record schema: migration `V3_3_24` tying each measurement to a specific attempt (S12-01)
- [S12-02](#s12-02-the-uniform-usage-recording-path) — the uniform usage recording path: `UsageMeteringService` / `UsageRecordDto`, unknown recorded as unknown, the `domain` placement that keeps the module acyclic
- [S12-03](#s12-03-the-usage-price-catalogue-and-versioning) — the usage price catalogue: `PricingResolver` / `PricingInfo` / `UnitDivisor`, migration `V3_3_25` `price_version` / `pricing_revision`, the priced `fromChatResponse`
- [S12-04](#s12-04-the-durable-per-tenant-cost-budget) — the durable per-tenant cost budget: `tenant_budgets` / `budget_reservations` (`V3_3_26`), idempotent reserve-before-the-call / settle-after, the `usage → workflowexecution` execution-SPI budget gate
- [S12-05](#s12-05-the-per-tenant-parallel-run-quota) — the per-tenant parallel-run quota: `tenant_run_slots` (`V3_3_27`), row-lock-serialized acquire-at-create / release-at-terminal, the race-proof quota gate
- [S12-06](#s12-06-the-idempotent-reconciliation-of-stranded-reservations) — the idempotent reconciliation of stranded reservations: `reconciled_at` + `attempt` (`V3_3_28`), `BudgetService#reconcileUnsettled` settles a worker-crashed reservation at its best-known amount and marks it, so the run's true consumption is recoverable
- [S12-08](#s12-08-the-append-only-audit-trail-enforced-at-the-storage-layer) — the append-only audit trail, enforced at the storage layer: `V3_3_29__audit_events_append_only.sql` (owner `audit`, forward-only, `ADR-008`) a `BEFORE UPDATE OR DELETE` trigger blocks rewrite for every role incl. the table owner; `REVOKE ... FROM PUBLIC` least-privilege hardening; recovery re-appends, never rewrites
- [S12-07](#s12-07-the-audit-action-catalogue-audit-contract) — the audit action catalogue (audit contract): `docs/security/audit-contract.md` enumerates every security-sensitive action + mandatory fields; the `AuditAction` / `AuditResourceType` enums express the canonical catalogue; coverage map (WIRED vs CANON)

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

## S12-04 — the durable per-tenant cost budget

The durable budget the domain glossary "Cost budget" / "Budget" names (glossary §10 "Cost budget",
§9 "Budget", plan Sprint 12, `S12-04`). `usage` owns it — the same owner as `usage_records` — backed
by migration `V3_3_26` (forward-only, `IF NOT EXISTS`, `ADR-008`): two tables.

- `tenant_budgets` — one per-tenant row of limits (`period`, `max_runs`, `max_tokens`, `max_cost`),
  measured over a `period`. One row per tenant. `tenant_id` is a plain, tenant-scoped value, **not**
  a cross-module FK (`tenants` / `workflow_runs` are owned by other modules — a cross-module FK would
  couple the schemas, exactly as `tool_invocations` scopes `run_id`).
- `budget_reservations` — one row per reservation an operation makes against that budget: the
  estimated amount reserved **before** the operation, reconciled to the **actual cost** when the
  operation settles. Every reservation counts toward the tenant's consumption at its reserved
  estimate until it settles, and at its reconciled `actual_cost` afterwards — both count, so the
  budget stays meaningful once everything settled.

**Reserve before the call, settle after — idempotently.** `application.BudgetService` `reserve(...)`
keys the reservation on an idempotency id (`reservation_id`); the repository's
`ON CONFLICT (reservation_id) DO NOTHING` primary key makes a duplicate delivery / crash-retry a
no-op, so it never double-charges (invariant 3). `reserve` locks the tenant's budget row
(`SELECT ... FOR UPDATE`) and checks the reservation against the remaining budget, throwing
`BudgetExhaustedException` when it would exceed it — blocking the operation *before* it starts.
`settle(...)` reconciles the reservation to its actual cost once, guarded by `settled_at IS NULL`
(a second settle is a no-op; at-least-once, never exactly-once). `consumed(...)` sums the accrual
`COALESCE(actual_cost, reserved_amount)` — an unsettled reservation counts at its reserve estimate,
a settled one at its reconciled actual cost. A tenant with no budget configured is **allowed**
(unlimited, never blocked). The refusal signal `BudgetExhaustedException` is the `usage` mirror of
`ai`'s pre-call `InsufficientBudgetException` (S09-10).

The value vocabulary: `domain.BudgetDto` (`tenantId`, `period`, `maxRuns`, `maxTokens`, `maxCost`),
`domain.BudgetPeriod` and `domain.BudgetMetric` (`COST` / `TOKEN`), `domain.BudgetReservation`
(`reservationId`, `tenantId`, `runId`, `metric`, `reservedAmount`) and `domain.BudgetCheckResult`
(`allowed`, `reason`).

**Tenant isolation (invariant 1).** Every read and write is keyed by `(tenantId, …)` with the
predicate inside the query itself — the accrual `consumed` sum, the `FOR UPDATE` lock and the
idempotent inserts all carry the `tenant_id` — so another tenant's budget is indistinguishable from
a missing one.

**The execution-SPI gate.** `workflowexecution` owns the `BudgetGuard` **port** — the same shape as
the `StepHandler` port; `usage` supplies the `@ConditionalOnBean(DataSource.class)` adapter
`UsageBudgetGuard` implementing it. This is the single sanctioned compile edge `usage →
workflowexecution` (module-map edge 9), so `workflowexecution` never compile-depends on `usage`. It
is wired into `workflowexecution.application.RunService#createAndPin`: a run cannot start against an
exhausted budget — the check runs **before** the run row and the outbox event are written, and a
rejected run starts with `409 CONFLICT`. The `@Autowired(required = false)` seam keeps the app
bootable without a database (a `null` guard skips the check, S01-02).

**Durability (invariant 2).** PostgreSQL is the source of truth — `tenant_budgets` and
`budget_reservations` are plain rows; no reservation is ever only in memory. No raw prompt, document
or cost secret is stored — only the reservation id (the idempotency key) and its amount (invariant 8).

**Verification.** Unit: `BudgetServiceTest` (7 DB-free tests — `checkBudget` allows without a budget
and denies once exhausted, `reserve` blocks when it would exceed the remaining budget, a duplicate
reservation is an idempotent no-op, `settle` reconciles / is idempotent, a `null` actual cost keeps
the estimate). Integration on real `pgvector/pgvector:0.8.6-pg18` (`BudgetServiceIT`, 6 tests) — a
budget round-trips through the store, reserve blocks the moment it would exceed the limit, settlement
reconciles idempotently, and another tenant's budget is isolated. `MigrationsTest` (now at `v3.3.26`,
`tests="25"` `failures="0"` `errors="0"` `skipped="0"`, JUnit-XML-verified) asserts the two tables
are owned by `usage` and the schema guards exist. The full `./gradlew check --rerun-tasks` gate —
unit + architecture (`ArchitectureBoundaryTest` `tests="1"`, `NoCyclicModuleDependenciesTest`
`tests="1"` `failures="0"` `errors="0"`, the `usage ↔ workflowexecution` edge 9) + no-cycles +
formatting (`spotless`) + OpenAPI — green; `git diff --check` clean.

**Docs.** [`docs/architecture/module-api.md`](../architecture/module-api.md) (the `usage`
public-application-services row — `BudgetService`; the permitted-types row — `BudgetDto` /
`BudgetPeriod` / `BudgetMetric` / `BudgetReservation` / `BudgetCheckResult` / `BudgetExhaustedException`;
the `persistence.BudgetRepository` internal package); [`docs/architecture/module-map.md`](../architecture/module-map.md)
(the `usage` module row — `tenant_budgets` under `usage` — and edge 9 `usage → workflowexecution`,
no new edge, no ADR); [`docs/domain-glossary.md`](../domain-glossary.md) (the "Cost budget" /
"Budget" rows); `CHANGELOG.md` (this entry).

## S12-05 — the per-tenant parallel-run quota

The quota half of the per-tenant limits Sprint 12 requires (plan `S12-05`; the usage half —
tokens / cost — is the S12-04 budget). `usage` owns it, backed by migration `V3_3_27`
(forward-only, `ADR-008`): `tenant_run_slots` — one row per **non-terminal** run a tenant holds,
acquired at run creation and released at the terminal transition, so the slot lifetime is exactly
the run's non-terminal lifetime. The limit itself is **not** duplicated: it stays the `max_runs`
column of `tenant_budgets` (`V3_3_26`, S12-04) this quota enforces. A tenant with no
`tenant_budgets` row is unlimited (never blocked) but its runs are still tracked, so the count
stays truthful if a limit is configured later. `tenant_id` / `run_id` are plain, tenant-scoped
values, not cross-module FKs; tenant isolation lives on the composite primary key and in every
predicate (invariant 1).

**The race acceptance — counted in PostgreSQL, never in Redis (invariant 2).**
`application.QuotaService#acquireRunSlot` locks the tenant's `tenant_budgets` row
(`SELECT ... FOR UPDATE`, the same serialization `BudgetService#reserve` uses), then counts the
tenant's active slots and inserts the new row while still holding the lock. Two concurrent run
creations therefore cannot both pass the headroom check: the second blocks on the row lock,
re-reads the count after the first commits, and is refused. Redis is used only for auxiliary rate
limiting / caching, never as the durable quota state.

**Acquire at create, release at terminal — idempotently (invariant 3).** The composite
`(tenant_id, run_id)` primary key makes a repeated acquisition an `ON CONFLICT DO NOTHING` no-op
— the same run re-acquiring (a duplicate delivery / an idempotent replay) is allowed, never
counted twice — and a repeated release is a `DELETE` of an already-absent row. Exactly-once is
never promised. The release lives in `workflowexecution.application.RunStateMachineService` (both
`transitionRun` overloads, after the transition and its event, in the same transaction), because
every terminal write funnels through the single transition service (invariant 4) — one seam
covers `SUCCEEDED` / `FAILED` / `CANCELLED` from the worker, the cancel path and the
limit-exceeded path alike. A run that never becomes terminal (e.g. its pinned graph is missing)
keeps its slot: stale-slot recovery is S12-06's reconciliation concern, recorded there.

**The execution-SPI gate — the S12-04 shape.** `workflowexecution` owns the `RunQuotaGuard`
**port** (acquire before the run row is written / release at the terminal state); `usage` supplies
the `@ConditionalOnBean(DataSource.class)` adapter `UsageRunQuotaGuard`. It is the same sanctioned
`usage → workflowexecution` module pair as the S12-04 `BudgetGuard` / `UsageBudgetGuard` (edge 9)
— no new module edge, no ADR. `RunService#createAndPin` acquires after the budget check and before
`workflow_runs` + outbox insert, in the caller's transaction: an over-quota tenant is refused with
`409 CONFLICT` and no run row, no event, no slot. The `@Autowired(required = false)` seam keeps
the app bootable without a database (S01-02).

The value vocabulary: `domain.QuotaCheckResult` (`allowed`, `reason`) — the acquisition outcome,
mirroring `BudgetCheckResult`.

**Verification.** Unit: `QuotaServiceTest` (8 DB-free tests — allow/track without a budget, allow
up to the limit then reject, a zero limit permits no run, duplicate acquisition is idempotent,
release frees the slot, release without a slot is a no-op, tenant isolation, no-database fails
loudly). Integration on real `pgvector/pgvector:0.8.6-pg18` (`QuotaServiceIT`, 4 tests) — the
**race acceptance**: 8 contenders, each owning its own connection and transaction, released at the
same instant against a limit of 3 — exactly 3 acquire and 5 are refused, and the table holds
exactly 3 rows; plus acquire/release round-trip, duplicate-acquisition idempotency and
unlimited-tenant tracking / isolation. `MigrationsTest` (now at `v3.3.27`, `tests="24"`
`failures="0"` `errors="0"` `skipped="0"`, JUnit-XML-verified) pins the new version and the owning
table. The touched `workflowexecution` suites re-run green (`RunServiceTest` 16,
`RunStateMachineServiceTest` 14, `TransitionTableServiceTest` 51; `RunServiceAtomicIT` 5,
`WorkflowCancelEndToEndIT` 3, `WorkflowTransitionIntegrationTest` 10,
`WorkflowTransitionConcurrencyIT` 2, `WorkflowWorkerEndToEndIT` 1,
`WorkflowWorkerCrashRecoveryIT` 1, `WorkflowWorkerLimitsIT` 2 — all `failures="0"`
`errors="0"`, fresh reports this session), proving the widened constructors and the null-guard
skip leave every existing path unchanged.

**Docs.** [`docs/architecture/module-api.md`](../architecture/module-api.md) (the `usage`
public-application-services row — `QuotaService`; the permitted-types row — `QuotaCheckResult`;
the `persistence.RunSlotRepository` internal package; the `workflowexecution` services row — the
`BudgetGuard` / `RunQuotaGuard` ports); the `usage` module [`AGENTS.md`](../../apps/agentflow-app/src/main/java/com/example/agentflow/usage/AGENTS.md)
(data ownership — `tenant_run_slots`); `CHANGELOG.md` (this entry).

## S12-06 — the idempotent reconciliation of stranded reservations

**What changed.** The `usage` module gains the **reconciliation** half of the durable budget
lifecycle (S12-04 `reserve` / S12-04 `settle`). The two tables are unchanged; migration `V3_3_28`
(forward-only, `ADR-008`, owner `usage`) adds two nullable columns to `budget_reservations`:

| Column | Meaning |
|---|---|
| `reconciled_at` (`timestamptz`) | set when the row was recovered by the reconciliation flow (unsettled → settled-at-best-known). Distinguishes a recovered orphan from one settled by the normal `settle` path, and makes the reconcile idempotent (`settled_at IS NULL AND reconciled_at IS NULL` guard). |
| `attempt` (`bigint`) | the operation attempt the reservation was keyed on. The idempotency key (`reservation_id`) is derived from `(tenant_id, run_id, attempt, metric)`, so a duplicate attempt of the same operation reuses the same key and a repeated `reserve` / `settle` is a no-op — "use attempt ID for idempotency" (invariant 3). `NULL` for rows created before S12-06 (they predate the attempt key and reconcile by `reservation_id`). |

The domain value `BudgetReservation` carries `reconciledAt` and `attempt`; a reservation's idempotency
key is derived deterministically from `(tenant, run, attempt, metric)` so a crash-retried reserve with
the same attempt lands on the same row. `BudgetRepository` gains `findUnsettled(tenantId, before)` —
unsettled rows created before `before`, ordered by `(created_at, reservation_id)` for a deterministic
scan — and `reconcileUnsettled(tenantId, before)`, a single guarded `UPDATE` that settles each stranded
row at its **best-known** amount (`actual_cost = reserved_amount`, `settled_at = now()`,
`reconciled_at = now()`) where `tenant_id = ? AND settled_at IS NULL AND created_at < ?`.
`BudgetService.reconcileUnsettled` is `@Transactional` and logs one `agentflow.usage.budget.reconciled`
counter per recovered row (no tenant / id label, invariant 9).

**Why.** A reservation is settled only when the operation settles **after** it runs. If the worker
dies mid-operation — or a provider call is ambiguous — the reservation is left floating:
`settled_at IS NULL`, so it keeps counting at its `reserved_amount` estimate forever and the run's
true consumption is unknown. S12-06 reconciles those stranded reservations: it settles them at their
best-known amount and marks them, so the budget reflects a recovered value instead of a stuck estimate,
and the reconciliation itself is idempotent (a duplicate reconcile is a no-op — invariant 3). The flow
is self-contained in `usage` — it needs no `workflowexecution` change and no terminal transition (the
"release at terminal" concern is the normal `settle`; stale-slot recovery for runs that never terminate
is a follow-up), which keeps the change minimal and fully testable.

**Acceptance.**
- **Re-settle does not double-charge.** `settle` is already guarded by `settled_at IS NULL`, and
  `reconcileUnsettled` uses the same guard — a second settle / reconcile of the same row is a no-op
  (verified by the idempotency assertions in `BudgetServiceTest` and `BudgetServiceIT`).
- **A stranded reservation is recovered.** `reconcileUnsettled` settles a row created before the
  boundary at its reserved amount, sets `reconciled_at`, and a second call reconciles nothing.

**Tenant isolation (invariant 1):** the reconcile `UPDATE` is keyed by `tenant_id` with the predicate
inside the query, so another tenant's stranded row is never reconciled for them; the `findUnsettled`
scan filters on `(tenant_id, settled_at, created_at)` too.

**Verification.** Unit: `BudgetServiceTest` (11 DB-free tests, JUnit-XML-verified
`tests="11" failures="0" errors="0" skipped="0"` — reserve blocks on headroom, duplicate reservation
is an idempotent no-op, `settle` reconciles / is idempotent, `checkBudget` allows without a budget and
denies once exhausted, **reconcile settles a stranded reservation at its best-known amount once and is
a no-op on the second pass**, reconcile ignores an in-flight reservation created after the boundary,
reconcile keys the reservation id on `attempt`, tenant isolation, no-database fails loudly).
Integration on real `pgvector/pgvector:0.8.6-pg18` (`BudgetServiceIT`, 9 tests): the real SQL scan and
the `created_at < before` boundary, tenant-isolated reconcile, settle-at-best-known and the
idempotent re-reconcile. `MigrationsTest` (now at `v3.3.28`, `tests="24"`,
`failures="0"` `errors="0"` `skipped="0"`, JUnit-XML-verified) pins the new version and the owning
table, asserting `reconciled_at` / `attempt` are nullable and the schema guards exist. The pre-existing
`budget_reservations` / `tenant_budgets` suites (`BudgetServiceTest` / `BudgetServiceIT` — the S12-04
reserve / settle coverage) re-run green with the two new columns, proving the migration and the new
`attempt` key leave the S12-04 path unchanged. `:apps:agentflow-app:check` green — unit + architecture
(`ArchitectureBoundaryTest` — the `usage` boundary unchanged, no new module edge) + no-cycles +
formatting (`spotless`). No new endpoint, no new module edge, no new FK — `tenant_id` / `run_id` stay
plain, tenant-scoped values (invariant 1).

**Docs.** `CHANGELOG.md` (this entry); the devlog (this section); `docs/architecture/module-api.md`
(the `usage` public-application-services row — `BudgetService` budget-reservation section, now with the
reconciliation method; the permitted-types row — `BudgetReservation` fields `reconciledAt` /
`attempt`); the `usage` module [`AGENTS.md`](../../apps/agentflow-app/src/main/java/com/example/agentflow/usage/AGENTS.md)
(data ownership — `budget_reservations`).

## S12-08 — the append-only audit trail, enforced at the storage layer

`AGENT.md` invariant 3 says an audit record cannot be rewritten. Until `S12-08` that guarantee was
code discipline only: [`AuditService`](../../apps/agentflow-app/src/main/java/com/example/agentflow/audit/application/AuditService.java)
only ever `INSERT`s and exposes no `update` / `delete` path, but the database still let any role
with `UPDATE` / `DELETE` on `audit_events` rewrite a row. `S12-08` moves the guarantee down to the
data layer, so an ordinary application role — even one carrying `UPDATE` / `DELETE`, or even the
table **owner** — cannot rewrite or delete an old `audit_events` row, regardless of privileges.

**The deliverable is a migration, not code.** `V3_3_29__audit_events_append_only.sql` (owner
`audit`, forward-only, `ADR-008`) does two things:

1. **A trigger blocks rewrite.** `audit_events_prevent_rewrite()` is a `BEFORE UPDATE OR DELETE`
   per-row trigger that raises `audit_events is append-only: % operations are not allowed` with the
   offending `TG_OP`. It fires for *every* `UPDATE` / `DELETE` — including rows written by the table
   **owner** — because a `BEFORE` trigger cannot be bypassed by privileges. A trigger *function*
   (not a `GRANT`) is what makes this possible: an owner holds `UPDATE` / `DELETE` by ownership and
   `GRANT` / `REVOKE` cannot remove them from an owner, so only a trigger overrides that.
2. **Least-privilege hardening.** `REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM PUBLIC` makes
   the intent explicit and guards against a future mis-grant; in `application-kubernetes.yml` the
   application connects as a dedicated least-privilege role holding only `INSERT` / `SELECT` on
   `audit_events`, the credential from the Kubernetes Secret, never from configuration.

**Recovery is append, never rewrite.** A reconciliation after a crash or an ambiguous external
outcome appends a corrected `FAILED` row (an INSERT the trigger allows) — a past row is never
`UPDATEd`, so append-only holds after recovery too (invariant 3). `S12-08` adds no retention / purge
path; that is the compliance / ops follow-up `S12-11` … `S12-13`, and the trail simply grows until
then (an old row can still only be *reached*, never changed).

**Verification.** The guard is proven against data, not declared. `MigrationsTest` gained two tests
— `audit_events_append_only_guard_is_declared` (the `audit_events_append_only_trg` trigger is
registered on `audit_events` and its / the trigger-function comments carry `S12-08`, the mandatory
fields `actor_subject` / `outcome` are non-nullable) and `audit_events_cannot_be_rewritten_or_deleted`
(an `UPDATE` and a `DELETE` are rejected with a message containing `append-only`, not a permission
error) — run under Testcontainers on the real PostgreSQL image, JUnit-XML verified (`tests="26"`
`failures="0"` `errors="0"` `skipped="0"`, all green this run). On a fresh PostgreSQL 18 schema
`V3_3_29` applies cleanly through Flyway (`./gradlew :apps:agentflow-app:flywayMigrate --rerun-tasks`
→ "now at version v3.3.29", `BUILD SUCCESSFUL`), and on that live database an INSERT succeeds while an
`UPDATE` / `DELETE` by both a least-privilege role and a role carrying **all** privileges are rejected
by the trigger. `./gradlew :apps:agentflow-app:check` is green (unit + architecture — no new module
edge — + formatting + OpenAPI); the migration adds no enum, so `MigrationsTest`'s version list is
intact apart from the appended `v3.3.29`.

**Docs.** [`docs/security/audit-contract.md`](../security/audit-contract.md) §8
(Storage-level append-only enforcement) updated to describe the trigger and `REVOKE`; `V3_3_29`
added to the contract references.

Follow-up: the `CANON` audit verbs (`START` / `CANCEL` / `UPLOAD` / `READ` / `ALLOW` / `DENY`) are
still only enumerated in the catalogue (`S12-07`) with no `auditService.record(...)` call site —
wiring them is a separate task, outside the append-only guarantee this task delivers.

## S12-07 — the audit action catalogue (audit contract)

The audit trail is one of the platform's durability and accountability guarantees (`AGENT.md`
invariants 3 and 8; plan §8.1). `S12-07` finalizes the **audit action catalogue** — the contract that
names every security- and business-sensitive action, the mandatory fields each record carries, the
record schema, the redaction policy, and — honestly — which actions already leave an `audit_events`
row and which are still pending. The deliverable is the contract
[`docs/security/audit-contract.md`](../security/audit-contract.md); the code-side expression is the
`AuditAction` / `AuditResourceType` / `AuditOutcome` enums (`audit.domain`).

**Why this shape.** The plan fixes `S12-07`'s result as "audit contract" and its acceptance as "every
security-sensitive action leaves an audit entry." A contract — not a sprawling implementation — is the
right first step: it makes the acceptance criterion verifiable against a fixed list instead of a vague
"audit the sensitive stuff", and it records exactly which verbs are left to wire so the follow-up tasks
(`S12-08` append-only policy, and the per-flow wiring) are scoped and reviewable. The catalogue
enumerates six categories — definition mutations, run start/cancel, document upload/read, approval,
tool allow/deny/execute, ops — and, per action, the `AuditAction` verb, the `AuditResourceType`, the
owning module, the audit edge and a `WIRED` / `CANON` coverage flag.

**Coverage (truthful, verified against the code).** Wiring is the set of `auditService.record(...)`
call sites in the current sources:

- `WIRED` today — definition `CREATE` (`WorkflowDefinitionService#createDefinition`,
  `WorkflowVersionService#createVersion`) / `ACTIVATE` (`WorkflowVersionService#activate`); tool
  `INVOKE` for started non-`READ_ONLY` calls (`ToolInvocationHistoryService`, S08-13); approval
  `APPROVE` / `REJECT` / `EXPIRE` (`ApprovalService`, `ADR-018`).
- `CANON` (in the catalogue, no call site yet) — definition `UPDATE`, run `START` / `CANCEL`,
  document `UPLOAD` / `READ`, tool `ALLOW` / `DENY`. The `START` / `CANCEL` / `ALLOW` / `DENY` verbs
  reuse the existing `workflowexecution → audit` (`ADR-018`) / `toolexecution → audit` (`edge 4`)
  edges; `UPLOAD` / `READ` need a new `knowledgebase → audit` edge (an ADR) and are the follow-up.

`READ` auditing is deliberately scoped as an operational decision for that follow-up: a tenant reading
its own document is tenant-isolated, not a privilege boundary, so auditing every read is
high-volume / low-sensitivity — `UPLOAD` (a write boundary) is the security-sensitive half. Ops
actions (`S12-11` … `S12-13`) reuse the verbs of the resource they act on rather than introducing an
ops-specific verb.

**Code change (minimal, canonical vocabulary).** `AuditResourceType` gains `RUN` / `DOCUMENT` — the
enum javadoc already instructed "a run, a document … add their own entry here". `AuditAction` gains
`START` / `CANCEL` (run), `UPLOAD` / `READ` (document) and `ALLOW` / `DENY` (tool authorization), with
`INVOKE` keeping the side-effect call and `EXECUTE` expressed as `INVOKE`. The enums are stored as
TEXT (`V3_3_5`), so **no new migration**; adding enum constants is **not** a module edge, so **no new
ADR** and the architecture gate is unchanged. The `AuditAction` javadoc is rewritten to describe the
full canonical catalogue rather than only the definition-mutation subset.

**Verification.** `:apps:agentflow-app:check` green — unit (`AuditServiceTest` / `AuditRepositoryTest`,
JUnit-XML verified) + architecture (`ArchitectureBoundaryTest` — no new edge) + no-cycles + formatting
(`spotless`). The new enum constants are TEXT values, so `MigrationsTest` is unaffected. No REST
endpoint, no new module edge, no migration, no contract-file change, no secrets or high-cardinality
labels. The protected `docs/AGENTS.md` / `audit/AGENTS.md` cross-references could not be edited (their
approval prompt timed out) — the authoritative index `docs/README.md` points to the contract correctly;
the `docs/AGENTS.md` `usage-and-costs.md` note still lists the audit catalogue under that (planned) file
and is a minor, noted drift.

Follow-up: `S12-08` (append-only write policy) wires the `CANON` verbs.
