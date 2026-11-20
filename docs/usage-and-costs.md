# Usage metering, cost budgets and the dead-letter surface

Operational documentation for the **usage** module (and its two consumers, `ai` for the model-call
budget and `workflowexecution` for the parallel-run quota): how one token/cost measurement is
recorded, how a historical estimate is explained through the price version that produced it, how a
tenant's spend is bounded across the model / tool / embedding calls a run makes, how the reservation
a call reserves is settled against the actual cost (or reconciled when the worker dies), and how an
operator inspects and redrive a held message without a database shell.

This is **not** a billing document: the platform records measurements, not invoices. `usage_records`
is a ledger of what was consumed, not a bill sent to a tenant (glossary §10, `docs/product-scope.md`
§3). The **usage** module owns all of this; it never writes to any other module's table.

> Read this alongside [`docs/domain-glossary.md` §10](domain-glossary.md#section-10-usage-budgets-and-audit)
> (the value vocabulary) and [`docs/devlog/sprint-12-usage-metering-cost.md`](devlog/sprint-12-usage-metering-cost.md)
> (the change history: S12-01…S12-14).

---

## Contents

- [Usage metering (the recording path)](#usage-metering-the-recording-path)
- [The price catalogue and price-catalogue versioning](#the-price-catalogue-and-price-catalogue-versioning)
- [The durable per-tenant cost budget and its reservations](#the-durable-per-tenant-cost-budget-and-its-reservations)
- [The per-tenant parallel-run quota](#the-per-tenant-parallel-run-quota)
- [Reservations: preliminary estimate → settled actual → reconciled orphan](#reservations-preliminary-estimate--settled-actual--reconciled-orphan)
- [Ambiguous provider billing failure](#ambiguous-provider-billing-failure)
- [Guard wiring: the execution-SPI adapter pattern](#guard-wiring-the-execution-spi-adapter-pattern)
- [Observability and the no-billing boundary](#observability-and-the-no-billing-boundary)
- [The dead-letter inspection and operator redrive](#the dead-letter-inspection-and-operator-redrive)
- [Module ownership](#module-ownership)
- [Migrations](#migrations)
- [Verification](#verification)
- [References](#references)

---

## Usage metering (the recording path)

Every measurement — a model chat completion, a tool invocation, an embedding call — is written to the
one table the **usage** module owns, `usage_records`, through exactly **one** application service,
[`UsageMeteringService`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/UsageMeteringService.java):
this is the recording port (S12-02). A caller builds a [`UsageRecordDto`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/UsageRecordDto.java)
and passes it to `record(...)`, which delegates to `UsageRepository.insert(...)`. The recording
service itself does no validation beyond `null`/availability guards: the DTO's canonical constructor
is the validation gate (it rejects a `null` id / tenant / run, a stale `attempt < 1`, a blank
`provider`, or a `MODEL`/`EMBEDDING` record without a `model`, a `TOOL` record without a `toolName`).

**Unknown is recorded as unknown, never invented (invariant 8).** The token counts are boxed `Long`
(nullable) — a provider refusal (`ChatResponse.usage() == null`) leaves both `inputTokens` /
`outputTokens` `null`, stored as SQL `NULL`, never coerced to `0`. The cost is `null` until the price
is known (the pre-catalogue `S12-02` path). An unpriced estimate never fabricates a money value.

**Who records what, and how the measurement is bounded.**

| `UsageType` | Recorded by | What is measured |
|---|---|---|
| `MODEL` | `ai.application` (a chat-completion call) | `inputTokens` / `outputTokens` from the provider `ChatResponse` through `ai.application.ChatResponse` / `ChatUsage` — the `usage → ai` edge (module-map arrow 8) |
| `TOOL` | the tool-history path | one invocation of a tool |
| `EMBEDDING` | the embedding metering path | one embedding call |

`UsageType` (`MODEL` / `TOOL` / `EMBEDDING`) and `Measurement` (`ESTIMATED` / `PROVIDER_REPORTED`)
are the value types that bound these. The `measurement` source pins whether the row carries
`estimated_cost` (`ESTIMATED`) or `provider_cost` (`PROVIDER_REPORTED`); the two coexist so a
pre-provider record is truthful (invariant 7 — the provider is untrusted; a `PROVIDER_REPORTED`
value is still reconciled server-side, never trusted blindly).

**What a row stores (and what it does not).** One row per measurement of one attempt — keyed to a
SPECIFIC attempt: `tenant_id` (FK `tenants`, the shared lookup that scopes every business row,
invariant 1), `run_id` (**NOT** a cross-module FK — `workflow_runs` is owned by `workflowexecution`,
so `run_id` is a UUID the tenant-scoped read filters on, never a JOIN, exactly as `tool_invocations`
scopes `run_id`), nullable `step_run_id` (TEXT, the `tool_invocations` convention) and the NOT NULL
`attempt` (the acceptance: a record without an attempt is not a measurement of anything concrete).
Cost is `NUMERIC`/Decimal in `currency` (invariant 10). The row stores only the token counts and the
cost — **never** the raw prompt, document or conversation (invariant 8).

> Database conventions: see [`docs/architecture/database-conventions.md`](architecture/database-conventions.md).

---

## The price catalogue and price-catalogue versioning

Every historical estimate on `usage_records` can be explained through the exact price version that
produced it (S12-03). The **usage** module adds a resolution layer:

- [`PricingProperties`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/PricingProperties.java)
  — the `@ConfigurationProperties("usage.pricing")` group that owns the catalogue. The catalogue is a
  map keyed by model id; each entry has `model`, `version`, `unit` (one of `token` / `1k-tokens` /
  `1m-tokens` — the only spellings the pattern accepts; an unknown spelling fails configuration
  binding), `price` and `effective` date.
- [`PricingResolver`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/PricingResolver.java)
  — the pure, DB-free resolver. It looks up the latest published price for a model (case-insensitive
  on the id), applies the unit divisor and returns a [`PricingInfo`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/PricingInfo.java)
  (the resolved `price_version` / `pricing_revision` / `unit` / `price` / currency / `effective`),
  plus the `UnitDivisor` that turns the token count into a money figure.
- [`UnitDivisor`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/UnitDivisor.java)
  — how many tokens one price unit covers: `1` for `token`, `1 000` for `1k-tokens`, `1 000 000` for
  `1m-tokens`. A total token count is priced with `unitPrice * tokens / divisor`.

**Unknown is recorded as unknown.** An unknown model — or no catalogue at all — resolves to `null`
from `PricingResolver.resolve(model)`; the caller passes that `null` straight through to the DTO
factory, which stores `null` for `price_version` / `pricing_revision`. The migration `V3_3_25` (owner
`usage`) adds the two nullable TEXT columns precisely so this is honest: an unpriced estimate carries
`NULL`, never an invented price.

> Catalogue versioning is the same `price_version` / `pricing_revision` idiom used for RAG chunk
> versioning and other append-only history — see the versioning section of
> [`docs/domain-glossary.md`](domain-glossary.md) (Anchor Index → "Catalogue versioning").

---

## The durable per-tenant cost budget and its reservations

A tenant's spend is bounded across the model / tool / embedding calls a run makes, so a run can never
consume an unbounded budget (S12-04). The **usage** module owns the durable budget, backed by the
migration `V3_3_26` (forward-only, `ADR-008`), in two tables:

- `tenant_budgets` — one per-tenant row of limits (`period`, `max_runs`, `max_tokens`, `max_cost`),
  keyed by `tenant_id` (the shared lookup).
- `budget_reservations` — one row per reservation an operation makes. Each reservation is keyed by the
  idempotency key `reservation_id` (an `ON CONFLICT DO NOTHING` primary key makes a duplicate delivery
  / crash-retry a no-op, so it never double-charges; invariant 3), with columns `tenant_id` (FK
  `tenants`), `run_id` (UUID, not a FK — the tenant-scoped read filters on it), `metric`
  (`COST` / `TOKEN`, the two dimensions of a run's cost), `reserved_amount` (`NUMERIC`),
  `created_at` (TIMESTAMPTZ), `settled_at` and `reconciled_at` (nullable markers of how the row ended)
  and `attempt`.

**The reservation lifecycle** is the heart of the cost model (see the next section): an operation
**reserves** the estimated amount *before* the call blocks if the reservation would exceed the
remaining budget, **settles** the reservation against the actual cost *after* the call, and a
worker-crashed, unsettled reservation is **reconciled** to its best-known amount.

**The refusal.** [`BudgetExhaustedException`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/BudgetExhaustedException.java)
is the `usage` mirror of `ai`'s pre-call `InsufficientBudgetException` (S09-10): the tenant's spend
is bounded before it runs, not billed afterwards. The value vocabulary is [`BudgetDto`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/BudgetDto.java)
(`tenantId`, `period`, `maxRuns`, `maxTokens`, `maxCost`), [`BudgetPeriod`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/BudgetPeriod.java),
[`BudgetMetric`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/BudgetMetric.java)
(`COST` / `TOKEN`), [`BudgetReservation`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/BudgetReservation.java)
(`reservationId`, `tenantId`, `runId`, `metric`, `reservedAmount`, `settledAt`, `reconciledAt`,
`attempt`) and [`BudgetCheckResult`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/domain/BudgetCheckResult.java)
(`allowed`, `reason`).

---

## The per-tenant parallel-run quota

A tenant can never hold more non-terminal runs than its limit allows, and a race of concurrent run
creations cannot bypass it (S12-05). The **usage** module owns the durable quota — migration `V3_3_27`
(forward-only, `ADR-008`) adds `tenant_run_slots`, one row per non-terminal run, acquired at run
creation and released at the terminal transition, so the slot lifetime is exactly the run's
non-terminal lifetime.

The limit stays the `tenant_budgets.max_runs` column (`V3_3_26`, S12-04) — **not** duplicated; a
tenant with no budget row is unlimited (never blocked) but still tracked. [`QuotaService`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/QuotaService.java)
serializes concurrent acquisitions per tenant **in PostgreSQL** — it takes the tenant's
`tenant_budgets` row lock (`SELECT … FOR UPDATE`, the same serialization `BudgetService#reserve`
uses), counts the active slots and inserts under that lock, so the headroom check can never race.
**Redis is used only for auxiliary rate limiting / caching, never as the durable quota state**
(invariant 2).

Idempotency (invariant 3): the composite `(tenant_id, run_id)` primary key makes a repeated
acquisition an `ON CONFLICT DO NOTHING` no-op and a repeated release a no-op `DELETE` — at-least-once,
never exactly-once. The gate is the S12-04 execution-SPI shape: the `RunQuotaGuard` **port** lives in
`workflowexecution` (`RunService` acquires before the run row + outbox event are written, so an
over-quota tenant is refused with `409` and nothing is created; `RunStateMachineService` releases in
the same transaction as the terminal transition, the single funnel every terminal write passes). The
**usage** side supplies the `UsageRunQuotaGuard` adapter — the same sanctioned `usage →
workflowexecution` edge as the S12-04 `BudgetGuard`, no new module edge, no ADR. The
`@Autowired(required = false)` seam keeps the app bootable without a database (S01-02).

---

## Reservations: preliminary estimate → settled actual → reconciled orphan

This section records how a run's **preliminary** cost (the estimate made before the call) becomes its
**final** cost (what was actually consumed), and how a cost that never settles is still recovered. It
is the reconciliation of "an external call is not exactly-once" (invariant 6) with "cost is recorded
truthfully" (invariants 8/10).

**1. Reserve (preliminary).** `BudgetService#reserve` is `@Transactional`. Before the operation runs,
it reserves the estimated amount: an `INSERT` guarded by `ON CONFLICT (reservation_id) DO NOTHING`,
where `reservation_id` is derived deterministically from `(tenant_id, run_id, attempt, metric)` (the
idempotency key; a duplicate attempt reuses the same key, so a repeated delivery / crash-retry is a
no-op). If reserving would exceed the remaining budget the operation is refused with
`BudgetExhaustedException`. The reserved amount is a **preliminary** estimate only — it is what the
plan predicted, not what happened.

**2. Settle (final).** After the call, the actual cost is known. `BudgetService#settle` is
`@Transactional`: it is one guarded `UPDATE` — `actual_cost = ?, settled_at = now()` — applied only
where `tenant_id = ? AND reservation_id = ? AND settled_at IS NULL` (invariant 4, the conditional
update + optimistic version pattern). A duplicate settle is a no-op (the second call finds an already
`SETTLED` row and changes nothing); an at-least-once consumer can never double-charge.

**3. Reconcile (recovered orphan).** A worker can die between settle and ack, or a redelivered
attempt can leave the reservation stranded `SETTLED` without a final cost. `BudgetService#reconcileUnsettled`
recovers it to its best-known amount — one guarded `UPDATE` per orphan, settling each at its
`reserved_amount`, marking `settled_at = now()` **and** `reconciled_at = now()` so a reconciled row
is distinguishable from one settled by the normal path. `BudgetRepository#findUnsettled(tenantId,
before)` lists orphaned rows created before `before`; `BudgetRepository#reconcileUnsettled` applies
the guarded update. The `attempt` and `reconciled_at` columns (migration `V3_3_28`, S12-06) make the
reconcile idempotent: `settled_at IS NULL AND reconciled_at IS NULL` guards it, and a second reconcile
is a no-op.

**Settle after reconcile is a no-op.** An already-settled row (whether by the normal `settle` path or
by `reconcile`) cannot re-charge or drift the recorded amount — the `settled_at IS NULL` guard on
`settle` is the same guard that makes the reconcile-and-late-settle ordering safe.

| Outcome | Column markers | Idempotency guard |
|---|---|---|
| Reserved, pending | `settled_at IS NULL, reconciled_at IS NULL` | `reservation_id` unique key |
| Settled (normal) | `settled_at IS NOT NULL, reconciled_at IS NULL` | `settled_at IS NULL` on `settle` |
| Reconciled (recovered) | `settled_at IS NOT NULL, reconciled_at IS NOT NULL` | `settled_at IS NULL AND reconciled_at IS NULL` on `reconcile` |

---

## Ambiguous provider billing failure

The hard case: a provider call that **actually executes** yet returns an **ambiguous** outcome — for
example a timeout after the request ran (S12-14). The run must not be charged twice on retry /
redelivery, but its cost must still be recorded once.

The same reservation lifecycle covers it, because it is the same invariant (6) in a harder
configuration:

- **Success settles the actual cost once.** A duplicate settle is a no-op (already-`SETTLED` row).
- **Redelivering the same attempt re-reserves as a no-op.** `BudgetReservation.reservationIdForAttempt`
  derives the same key for the same attempt, so no second reservation row is inserted.
- **A stranded attempt is reconciled at its best-known amount once.** A second reconcile is a no-op
  (the reconciled guard).
- **A late settle after reconciliation is a no-op.** An already-settled row cannot re-charge or drift.

So one attempt is never counted twice on retry / redelivery, and the reservation the run records is
always the single true cost — estimated while the call is in flight, actual once the outcome is
known, best-known if the worker never reported either. **Tenant isolation (invariant 1)** holds
throughout: every reserve / settle / reconcile is keyed by `tenantId` with the predicate inside the
query, so another tenant's reservation is never settled for them.

---

## Guard wiring: the execution-SPI adapter pattern

Both durable `usage` guards (the cost budget and the parallel-run quota) follow the same
execution-SPI pattern the `StepHandler` port uses: the **port lives in `workflowexecution`**, and
`usage` **implements** it across a sanctioned, single `usage → workflowexecution` module edge (module
map arrows 8–9, declared in `PackageDependencyGraphTest`). No new compile edge, no ADR.

- The cost guard: [`UsageBudgetGuard`](../apps/agentflow-app/src/main/java/com/example/agentflow/usage/application/UsageBudgetGuard.java)
  implements `workflowexecution.application.BudgetGuard`. It is `@ConditionalOnBean(DataSource.class)`
  — absent when no database is configured, so the app still boots without one (S01-02); `RunService`
  then receives a `null` guard and the run-start budget check is skipped. `checkBudget(tenantId)` is
  keyed by `tenantId`, passed explicitly (no `ThreadLocal`, invariant 1), and delegated to the durable
  `BudgetService#checkBudget`.
- The quota guard: the `UsageRunQuotaGuard` adapter implements `workflowexecution`'s `RunQuotaGuard`,
  wired the same way.

Every call is keyed by `tenantId`, passed explicitly (no `ThreadLocal`, invariant 1), and delegated to
the durable service that performs the tenant-scoped query — so another tenant's budget / slot is
indistinguishable from a missing one.

---

## Observability and the no-billing boundary

**Usage is a ledger, not a bill.** `usage_records` / `budget_reservations` / `tenant_run_slots` are
internal consumption records; the module publishes no invoice endpoint, no payment, no charge to a
customer. The glossary §10 is explicit: *a usage record is a measurement, not a bill.* `product-scope.md`
§3 keeps billing out of scope. This is the "no billing" boundary (S12-01): meter what was consumed,
never invoice it.

**Metrics are free of secrets and high-cardinality labels (invariant 9).** The observability
conventions — structured logs, trace/correlation IDs, and meters that never carry `tenantId`,
`userId`, `runId`, `documentId` or a prompt in their labels — are the root `AGENTS.md` invariant 9, tightened for secrets in [`docs/security/secrets.md`](security/secrets.md). Usage *data* (which tenant, which
run) lives in traces and the audit trail, never in metric label dimensions. The same convention
governs the audit metadata (redacted, `docs/security/audit-contract.md`) and the DLQ diagnostics
below.

---

## The dead-letter inspection and operator redrive

The `messaging` module's dead-letter (S12-07…S12-12) is a **pointer** to a failed envelope (an
`outbox_events` row), never a re-run of it. Two operator surfaces let a human recover the held message
without a database shell:

**1. Inspection (read-only).** [`DlqInspectionController`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/web/DlqInspectionController.java)
gates both routes on the `ops:dlq` authority (`AccessMatrix.OPS_DLQ`, S12-11) and rate-limits them
under the `RateLimitGroup.OPS` budget (`EndpointGroupResolver`, S12-13). `GET /api/v1/ops/dlq`
(`page` / `pageSize`, capped at a max page size) returns the read-only list
[`DlqMessagePageDto`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqMessagePageDto.java)
(status, `errorClass`, `lastError` summary — no full payload); `GET /api/v1/ops/dlq/{dlqId}` returns
[`DlqMessageDetailDto`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqMessageDetailDto.java)
(the stored payload, correlation metadata, disposition). [`DlqInspectionService`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqInspectionService.java)
performs tenant-scoped `(tenant_id, id)` reads so another tenant's message is a `404`, never a leak
(invariant 1); the detail read records an `AuditEvent` (`AuditResourceType.DLQ`) in the same
`@Transactional` boundary as the read (an inspection is itself an audited security action, invariant 3).
`503` when no database is configured (the `@Autowired(required = false)` repository, S01-02).

**2. Controlled redrive (the business change).** `POST /api/v1/ops/dlq/{dlqId}/redrive` with body
[`DlqRedriveRequest`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqRedriveRequest.java)
(`reason`, mandatory) is handled by [`DlqRedriveService`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqRedriveService.java):
the DLQ only *points* at a failed envelope, so the redrive **re-publishes the original
`outbox_events` row under a fresh envelope id** — a fresh id makes the inbox re-run the business change
once (the inbox dedup is keyed by `(consumer, event_id)`; reusing the original `event_id`, already
claimed and failed, would run nothing). The original re-published envelope id is returned in
[`DlqRedriveResult`](../apps/agentflow-app/src/main/java/com/example/agentflow/messaging/application/DlqRedriveResult.java).

**Idempotent (invariant 4).** A durable linkage row `dlq_redrive_attempts` (migration `V3_3_31`,
owned by `messaging`, `dlq_id` PRIMARY KEY) records one redrive per DLQ record; a second redrive of
the same record is **idempotent** — the service returns the already-recorded redrive (`200`, no second
envelope, no second audit row) rather than re-publishing. **Guarded by the inbox (invariant 2):** a
`PROCESSED` inbox row (a completed business change) or a `PENDING` row (an in-flight delivery) makes
the redrive a `409` — the inbox is the source of truth, so the business change is not run twice.

**Authorization and audit are mandatory (acceptance criterion).** Redrive is `ops:dlq`-gated; every
redrive is audited; a `reason` is required and recorded. **Secrets / personal data are not disclosed
in diagnostics (invariant 8):** the redrive API returns only the re-published envelope id, the stored
status and `error_class`; the inspection detail read does not surface bearer tokens, provider
credentials or raw prompt text, and the DLQ `last_error` is redacted before it is stored (the shared
[`SecretRedactor`](../apps/agentflow-app/src/main/java/com/example/agentflow/common/SecretRedactor.java)
primitive, ADR-020). The full procedure is in
[`docs/runbooks/dlq-redrive.md`](runbooks/dlq-redrive.md).

> The DLQ is an `messaging`-owned concern; the redrive re-publishes through the transactional outbox
> and the inbox is the source of truth — see [`docs/runbooks/dlq-redrive.md`](runbooks/dlq-redrive.md)
> and [`docs/adr/ADR-021-redis-role.md`](adr/ADR-021-redis-role.md) for the "Redis down" case (Redis
> is a helper — caching / rate-limit — never durable state, so losing it never loses a run).

---

## Module ownership

Everything below is owned by the **usage** module (the same owner as `usage_records`); it reads no
other module's table, only its public API.

| Resource | Owner | Notes |
|---|---|---|
| `usage_records` (+ `V3_3_24`, `V3_3_25`) | `usage` | One row per measurement of one attempt. |
| `UsageMeteringService`, `UsageRecordDto`, `UsageType`, `Measurement` | `usage` | The single recording port (S12-01, S12-02). |
| `tenant_budgets` (`V3_3_26`), `budget_reservations` (`V3_3_26`, `V3_3_28`) | `usage` | The durable cost budget (S12-04, S12-06). |
| `UsageBudgetGuard`, `BudgetService`, `BudgetExhaustedException` | `usage` | Implements `workflowexecution.application.BudgetGuard` (edge 9). |
| `tenant_run_slots` (`V3_3_27`) | `usage` | The per-tenant parallel-run quota (S12-05). |
| `QuotaService`, `UsageRunQuotaGuard` | `usage` | Implements `workflowexecution`'s `RunQuotaGuard` (edge 9). |
| `PricingResolver`, `PricingProperties`, `PricingInfo`, `UnitDivisor` | `usage` | The price catalogue and its versioning (S12-03). |

---

## Migrations

All owned by `usage`, all forward-only (`ADR-008`, `docs/architecture/database-migrations.md`):

| Migration | Adds |
|---|---|
| `V3_3_24__usage_records_schema_guards.sql` | `usage_records` — the per-attempt token/cost measurement. |
| `V3_3_25__add_pricing_version_to_usage_records.sql` | `usage_records.price_version` / `pricing_revision` (nullable TEXT). |
| `V3_3_26__create_cost_budget_and_reservations.sql` | `tenant_budgets`, `budget_reservations`. |
| `V3_3_27__create_tenant_run_slots.sql` | `tenant_run_slots`. |
| `V3_3_28__add_reconcile_columns_to_budget_reservations.sql` | `budget_reservations.reconciled_at`, `budget_reservations.attempt`. |

---

## Verification

- **DB-free suites, JUnit-XML-verified:** `UsageRecordDtoTest` (the `MODEL` / `TOOL` / `EMBEDDING`
  factories, `null`-is-unknown, canonical-contract guards), `PricingResolverTest`,
  `BudgetServiceTest` (17 — reserve / settle / checkBudget / reconcile, incl. the six ambiguous-billing
  cases, `tests="17" failures="0"` `errors="0"` `skipped="0"`), `QuotaServiceTest`,
  `UsageBudgetGuardTest`, `UnitDivisorTest`.
- **Integration (real `pgvector/pgvector:0.8.6-pg18`, Testcontainers):** `BudgetServiceIT` (reserve /
  settle / reconcile on the live store — `tests="13"`, fresh run), the `usage` quota and metering
  integration tests.
- **Architecture gate:** `ArchitectureBoundaryTest` 1/1 and `NoCyclicModuleDependenciesTest` 1/1 — the
  two `usage → workflowexecution` / `usage → ai` edges are the only new edges, declared in
  `PackageDependencyGraphTest`.
- **Build gate:** `./gradlew :apps:agentflow-app:check --rerun-tasks` (unit + architecture +
  no-cycles + formatting `spotlessCheck` + OpenAPI contract) is green.

---

## References

- [`docs/domain-glossary.md`](domain-glossary.md) §10 — the value vocabulary; §8 — the RAG chunk
  versioning idiom this mirrors.
- [`docs/devlog/sprint-12-usage-metering-cost.md`](devlog/sprint-12-usage-metering-cost.md) — the
  task-by-task change history (S12-01…S12-14).
- [`docs/architecture/module-map.md`](architecture/module-map.md) — the `usage` module edges (arrows
  8–9) and ownership.
- [`docs/architecture/module-api.md`](architecture/module-api.md) — the `usage` public API surface.
- [`docs/architecture/database-conventions.md`](architecture/database-conventions.md) — the column /
  FK / index conventions all `usage` migrations follow.
- [`docs/security/secrets.md`](security/secrets.md) — the secrets / PII-out-of-telemetry convention (invariant 8) and the metrics / logging / secrets conventions that keep usage data out of metric labels.
- [`docs/runbooks/dlq-redrive.md`](runbooks/dlq-redrive.md) — the operator inspection + redrive
  procedure.
- [`docs/security/audit-contract.md`](security/audit-contract.md) — the audit actions `usage`
  participates in (tool `INVOKE`, definition mutations) and the audit contract.
- [`docs/adr/ADR-003-at-least-once-idempotency.md`](adr/ADR-003-at-least-once-idempotency.md) (at-least-once / exactly-once), [`ADR-008-database-migrations.md`](adr/ADR-008-database-migrations.md) (forward-only migrations), [`ADR-016-ingestion-ai-embedding-edge.md`](adr/ADR-016-ingestion-ai-embedding-edge.md) and [`ADR-018-workflowexecution-audit-edge.md`](adr/ADR-018-workflowexecution-audit-edge.md) (the execution-SPI / audit edges), [`ADR-020-shared-redaction-in-common.md`](adr/ADR-020-shared-redaction-in-common.md) (secret redaction), [`ADR-021-redis-role.md`](adr/ADR-021-redis-role.md) (Redis is a helper, never durable state).
