# Sprint 12 — usage metering, budgets and cost

## Contents

- [S12-01](#s12-01-the-usage-record-schema) — the usage record schema: migration `V3_3_24` tying each measurement to a specific attempt (S12-01)

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
