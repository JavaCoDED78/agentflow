# Sprint 12 — usage metering, budgets and cost

## Contents

- [S12-11](#s12-11-the-operator-dlq-inspection-api) — the operator DLQ inspection API: read-only, `ops:dlq`-gated, paginated list + audited detail view, `READ` on `AuditResourceType#DLQ` (S12-11)
- [S12-10](#s12-10-the-dead-letter-store-records-why-a-message-failed) — the dead-letter store records *why* a message failed (`error_class`) alongside *what to do* with it (`status`) (S12-10)
- [S12-09](#s12-09-centralised-secret-redaction) — centralised secret redaction: `common.SecretRedactor` as the one source of truth (bearer / JWT / `AKIA…` / `key=<secret>` / full prompt) (S12-09)
- [S12-08](#s12-08-the-append-only-audit-trail-enforced-at-the-storage-layer) — the append-only audit trail, enforced at the storage layer by a trigger that blocks rewrite even for the table owner (S12-08)
- [S12-07](#s12-07-the-audit-action-catalogue-audit-contract) — the audit action catalogue (contract): every security-sensitive action, mandatory fields, WIRED vs CANON coverage (S12-07)
- [S12-06](#s12-06-the-idempotent-reconciliation-of-stranded-reservations) — the idempotent reconciliation of stranded budget reservations after a crash (S12-06)
- [S12-05](#s12-05-the-per-tenant-parallel-run-quota) — the per-tenant parallel-run quota, race-proof via row-lock in PostgreSQL (S12-05)
- [S12-04](#s12-04-the-durable-per-tenant-cost-budget) — the durable per-tenant cost budget: reserve before the call, settle after, idempotently (S12-04)
- [S12-03](#s12-03-the-usage-price-catalogue-and-versioning) — the usage price catalogue and versioning: every historical estimate explainable through the exact price version (S12-03)
- [S12-02](#s12-02-the-uniform-usage-recording-path) — the uniform usage recording path; unreported token values recorded as unknown, not invented (S12-02)
- [S12-01](#s12-01-the-usage-record-schema) — the usage record schema: one row per measurement of one attempt (S12-01)

---

## S12-11 — the operator DLQ inspection API

**Task (P1):** let an operator inspect the dead-letter queue read-only — page the store and open the
full record of a held message — gated by the `ops:dlq` permission, with the access recorded in the
audit trail.
**Deliverable:** a read-only REST controller (`DlqInspectionController`) and its application service
(`DlqInspectionService`), backed by a `@ConditionalOnBean(DataSource)` repository (`DlqMessageRepository`),
using the `ops:dlq` permission from the identity module.

The DLQ is a single, tenant-wide store keyed by `(consumer, event)` — it is *not* tenant-scoped, so
an operator inspection is a read across the whole store, but the *who* must still be answered from
trusted context (invariant 1) and recorded (invariant 3). The lookup takes no tenant (there is only
"exists" / "does not exist"; a cross-tenant id is indistinguishable from a missing one), while the
tenant/actor are threaded only into the audit record from the security context.

### What changed

- **Permission and access matrix.** `Permission.OPS_DLQ` (`ops:dlq`) was added to the catalogue
  (`Permission.java:60`), and `AccessMatrix` gained two rules — `GET /api/v1/ops/dlq` and
  `GET /api/v1/ops/dlq/*` — both requiring `ops:dlq` (`AccessMatrix.java:81-82`). `SecurityConfiguration`
  wires the matrix into the filter chain, so a token holding only `workflow:read` is denied (`403`)
  on both endpoints, exactly like the approval endpoints.
- **The inspection service (`DlqInspectionService`).** Two use cases:
  - `listDlqMessages(page, requestedSize)` pages the whole store, newest first
    (`recorded_at DESC, id DESC` — invariant 10, so a page is stable across reads), returns the
    overview metadata only (no failure text), and clamps the page size to `MAX_PAGE_SIZE = 100`
    (invariant 11). It throws `400` for `page < 0` / `size < 1`.
  - `inspectDlqMessage(dlqId, tenantId, actor)` resolves the row by id, records an
    `AuditAction#READ` on `AuditResourceType#DLQ` in the append-only trail (audit-contract §4.6
    reuses `READ` for operator inspection), and returns the full `DlqMessageDetailDto`. The
    `lastError` surfaced here is already redacted at write time (S12-09): a secret, a full prompt or
    a full document never reaches the durable row, so it never reaches this view. It throws `404`
    when no row has that id.
  - The `DlqMessageRepository` bean is registered only when a `DataSource` exists
    (`@ConditionalOnBean(DataSource.class)`), so it is `@Autowired(required = false)` on the service
    (the same no-DB boot pattern as `WorkflowDefinitionService`). When absent, the service fails loud
    with `503` rather than returning a silent, misleading empty page.
- **The DTOs.** `DlqMessagePageDto` (the page + pagination flags), `DlqMessageDto` (one row: no raw
  payload), `DlqMessageDetailDto` (the full record for the audited detail view).
- **No new module edge, no ADR required.** `DlqInspectionService` calls `AuditService` (the audit
  port, already on the `messaging → audit` edge, ADR-018) and `DlqMessageRepository` (its own
  module). It imports only the identity *catalogue* (`Permission`, `AccessMatrix`) — the same
  already-established `messaging → identity` edge — so the architecture boundary and the ALLOW map
  are unchanged and the gate still passes. The `AuditResourceType#DLQ` value is a new catalogue
  entry, not a new edge.

### Verification

Unit suites, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`, fresh this session):
`DlqInspectionServiceTest` (7 — the default page is `page 0 / size 20`; the page clamps to
`MAX_PAGE_SIZE`; `400` for `page < 0` / `size < 1`; overview never carries `lastError`; the detail
records `READ` on `DLQ` and returns the full view; `404` when no row has the id; `503` when the
repository is absent, for both the list and the detail). `DlqInspectionControllerTest` (6 — the
overview `200` with default pagination and no `lastError`; the detail `200` exposes the full record;
`400` on bad pagination; `404` on an unknown id; the service is called with only the configured
tenant and actor; the `503` when no database is configured). `SecurityFilterChainTest` — acceptance
for `S12-11`: a `workflow:read`-only token is denied (`403`) on both `GET /api/v1/ops/dlq` and
`GET /api/v1/ops/dlq/{dlqId}`, while a token holding `ops:dlq` reaches the controller (`200`),
mirroring the approval endpoints.
`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (the unchanged ALLOW
map, `moduleBoundaries` passes) + no-cycles + formatting (`spotlessApply`, javadoc wrapping;
`git diff --check` clean) + OpenAPI contract (the two new endpoints, generated into the contract via
the deterministic `generateOpenApiContract` task).

### Documentation

- `docs/security/permissions.md` — two rows in the endpoint table for `GET /api/v1/ops/dlq` and
  `GET /api/v1/ops/dlq/*` (`ops:dlq`), plus the `ops:dlq` row in the permission table.
- `docs/architecture/module-api.md` — the `DlqInspectionService` methods, the `DlqInspectionController`
  routes and DTOs, the `AuditResourceType#DLQ` audit edge, the `ops:dlq` gating, and the compile edge
  (`DlqInspectionService` → `AuditService`) with `ADR-018` as the rationale.
- `apps/agentflow-app/src/main/java/com/example/agentflow/messaging/AGENTS.md` — the two inspection
  endpoints, the `ops:dlq` permission, and the `READ`/`DLQ` inspection audit.
- `apps/agentflow-app/src/main/java/com/example/agentflow/audit/AGENTS.md` — `DLQ` added to the
  catalogue, with the inspection `READ`.
- `docs/adr/ADR-022-messaging-audit-edge.md` + the ADR register (`docs/adr/README.md:40`) — the
  `messaging → audit` edge for DLQ inspection is documented (the edge already existed; this records
  the new `DLQ` resource that rides it).
- `CHANGELOG.md` (this entry).

### Acceptance

An operator who holds `ops:dlq` can page the dead-letter store read-only and open the full record of
a held message; every such access is recorded `READ` / `DLQ` in the append-only audit trail, so the
operator's investigation is auditable without ever surfacing redacted content, and the store's
availability is never mistaken for emptiness (a missing database reports `503`, an unknown id reports
`404`).

---

## S12-10 — the dead-letter store records *why* a message failed

**Task (P0):** extend the dead-letter record so the operator sees not only *what to do* with a held
message (`status`) but *why* it failed, without unnecessary disclosure.
**Deliverable:** a new `error_class` column on `dlq_messages`.

Until `S12-10` the DLQ recorded the disposition (`RETRY` / `DEAD_LETTER`) but not the failure reason,
so an operator could see "hold this" without knowing whether the failure was permanent or transient —
the very classification that decides whether the message can ever succeed again. `S12-10` adds
`error_class` so the same record carries both.

### What changed

- **One new column, forward-only.** `dlq_messages.error_class TEXT NOT NULL DEFAULT 'TRANSIENT'`
  guarded by a CHECK constraint (`dlq_messages_error_class_check`, `PERMANENT` / `TRANSIENT`), declared
  in `V3_3_30__add_error_class_to_dlq_messages.sql` (owner `messaging`, `ADR-008`). `DlqMessage` gains
  the nested `ErrorClass` enum; `DlqService.record(...)` takes it and forwards it;
  `DlqMessageRowMapper` reads `error_class`, `DlqMessageRepository` persists it. The column is optional
  at the edge: the repository / row mapper degrade to `TRANSIENT` when the column is absent (a row
  written by an earlier migration), so the change lands without a risky all-at-once cutover.
- **The derivation lives inside `messaging` — one-to-one, never inconsistent.**
  `InboxConsumerService` already classifies `PERMANENT → DEAD_LETTER` and `TRANSIENT → RETRY`; `S12-10`
  reuses that same decision to set `error_class`:

  ```java
  DlqMessage.ErrorClass errorClass =
      result == InboxConsumeResult.RETRY
          ? DlqMessage.ErrorClass.TRANSIENT
          : DlqMessage.ErrorClass.PERMANENT;
  ```

  Because one method sets both, the disposition and the classification of the same failure can never
  disagree.
- **No new module edge, no ADR required.** `DlqMessage.ErrorClass` is imported *only* inside
  `messaging.application` / `messaging.persistence`; `ingestion.worker.DocumentIndexingWorker` still
  consumes only the `messaging.application` public API. So the architecture gate is unchanged and still
  passes — the opposite of `S12-09`, where the redaction primitive genuinely pulled `common` into the
  graph and the ALLOW map had to be widened.

### Verification

Unit suites, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`, fresh this session):
`DlqServiceTest` (4 — the classification is persisted with the disposition and redacted `lastError`;
the null-repository path is a no-op), `DlqMessageRowMapperTest` (2 — `error_class` round-trips, a NULL
degrades to `TRANSIENT`), `DlqMessageRepositoryTest` (6 — upsert records the column and a duplicate
`(consumer_name, event_id)` collapses), `InboxConsumerServiceTest` (4 — `PERMANENT → DEAD_LETTER` /
`TRANSIENT → RETRY` both set the matching `error_class`). Integration on real
`pgvector/pgvector:0.8.6-pg18`: `MigrationsTest` (27 — `dlq_error_class_guard_declared` checks the
column type `text`, `NOT NULL`, the CHECK, and that the version list now includes `3.3.30`;
`acceptance_criteria_hold_against_data` still holds) and `DlqMessageRepositoryIT` (4 — `error_class`
round-trips on live PostgreSQL/pgvector).
`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (the unchanged ALLOW
map, `moduleBoundaries` passes) + no-cycles + formatting (`spotlessApply`, import ordering / javadoc
wrapping; `git diff --check` clean).

### Documentation

- `docs/domain-glossary.md` (`error_class`, `dead-letter` rows now carry the classification).
- `docs/architecture/events/README.md` (the DLQ contract section notes the classification recorded alongside the disposition).
- `docs/architecture/kafka-topics.md`.
- `docs/architecture/module-api.md` (`DlqService.record` parameter and the `ErrorClass` type).
- `CHANGELOG.md` (this entry).

### Acceptance

The DLQ record captures both the disposition and the *reason* (permanent / transient) for a held
message, recorded one-to-one, so an investigation reads a single consistent diagnostic rather than a
disposition with no cause.

---

## S12-09 — centralised secret redaction

**Task (P0):** remove bearer tokens, API keys, provider headers, raw document text and full prompts
from logs, events and errors; add canary tests.
**Deliverable:** `common.SecretRedactor` — a shared primitive, not a new module.

Until `S12-09` the redaction *rules* lived in **one** place only — the logback `ValueMasker`
(`SecretRedactingValueMasker`). The audit path (`AuditService`, the `metadata_redacted` column, written
straight to PostgreSQL) and the DLQ path (`DlqService`, the `last_error` column) trusted the caller to
pass already-redacted text: a secret embedded in an error string, or a full prompt the caller cannot
scrub, could reach a durable row or an operator view. `S12-09` moves the rules to one shared primitive
so all three surfaces redact identically and none can drift.

### What changed

- **The primitive.** `common.SecretRedactor` is `final`, DB-free, and `redact(String)` is a static
  method (the `Fingerprint` idiom, not a Spring bean) so it can be used before context is resolved. It
  strips bearer tokens / JWTs (`Bearer <jwt>` → `Bearer ***`, keyword kept), AWS access keys
  (`AKIA…` → `AKIA***`), `key=<secret>` / `key: <secret>` pairs (including the JSON-object form with a
  quoted key; keys `api_key`, `api...d`, `bearer`, `secret`, `password`, `token`, … → `***`) and full
  prompt / document fields (`document=<text>` → `document=***` — untrusted content, invariant 7 /
  `S09-13`). `null` passes through untouched.
- **All three surfaces call it.**
  - **Logs** — `SecretRedactingValueMasker` stops owning the patterns and delegates to
    `SecretRedactor.redact`; it only adapts the shared utility to the logback `ValueMasker` contract.
  - **Audit metadata** — `AuditService` calls `SecretRedactor.redact(metadataRedacted)` in front of the
    `AuditRepository.insert(...)` so a caller-supplied value still carrying a secret is masked before it
    is written.
  - **DLQ description** — `DlqService` calls `SecretRedactor.redact(lastError)` in front of the
    `DlqMessageRepository.record(...)` so the description an operator may read never carries a secret.

### Why

Redaction in `common` pulls `common` into the dependency graph, so `observability`, `audit`,
`messaging` (and the already-importing `workflowexecution`) need `common` in their allowed set; the
architecture gate forbids the edge without an ADR (root `AGENTS.md`). The gate is **not** inert:
widening `ArchitectureBoundaryTest` made `moduleBoundaries` **fail** (`tests="1"` `failures="1"`), and
the map was widened only for those four modules — the map, the gate and `docs/architecture/module-map.md`
(four rows' "Outgoing deps" now list `common`) were updated in this change so they never disagree. The
reach stays narrow: the consumers use only `common.SecretRedactor` (and, for `workflowexecution`,
`common.ErrorClass` / `common.Fingerprint`), never `common`'s infrastructure. Recorded in
[`ADR-020`](../adr/ADR-020-shared-redaction-in-common.md).

### Verification

DB-free suites, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`, fresh this session):
`SecretRedactorTest` (9 — null pass-through; plain text unchanged; `Bearer <jwt>` masked while keeping
the keyword, embedded in a larger message; `api_key=` / `apiKey=` / `password=` values masked;
`AKIA…` → `AKIA***`; full `document=<text>` → `document=***`; multiple secrets in one value all
masked) and the two new boundary assertions `AuditServiceTest` (+1: a secret `metadataRedacted`
reaches the repository already masked to `token=***`) and `DlqServiceTest` (+1: a secret `lastError`
reaches the repository already masked to `Bearer ***`). The regression guard `CanaryLeakScanTest` (4)
and `SecretRedactingValueMaskerTest` (9) are unchanged and still green.
`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (the widened ALLOW map,
`moduleBoundaries` now passes) + no-cycles + formatting (`spotlessApply` was needed for import ordering
/ javadoc wrapping; `git diff --check` clean).

### Documentation

- [`ADR-020`](../adr/ADR-020-shared-redaction-in-common.md) + the ADR register.
- `docs/architecture/module-map.md` (four rows' "Outgoing deps" add `common`).
- `apps/agentflow-app/src/main/java/com/example/agentflow/observability/AGENTS.md` (the masking section now notes the delegation).
- `common/AGENTS.md` already lists "secret redaction" among `common`'s owned primitives — this change is on-ownership, no edit needed.
- `CHANGELOG.md` (this entry).

### Scope

The value-bearing patterns (bearer / AWS key) and the `key=value` / `key: <secret>` form — including
the JSON-object form with a quoted key — match exactly what the `S01-19` masker applied and what the
canary test exercises, so the same utility the log path redacts is the one the audit-metadata and DLQ
writes use.

---

## S12-08 — the append-only audit trail, enforced at the storage layer

**Task (P0):** separate the append API, limit the update/delete privilege in production-like config,
define retention and redaction.
**Deliverable:** a migration, not code — a trigger that forbids rewriting an old record.

Until `S12-08` "an audit record cannot be rewritten" (`AGENT.md` invariant 3) was code discipline only:
[`AuditService`](../../apps/agentflow-app/src/main/java/com/example/agentflow/audit/application/AuditService.java)
only ever `INSERT`s and exposes no `update` / `delete` path, but the database still let any role with
`UPDATE` / `DELETE` on `audit_events` rewrite a row. `S12-08` moves the guarantee down to the data
layer.

### What changed

- **A trigger blocks rewrite.** `audit_events_prevent_rewrite()` is a `BEFORE UPDATE OR DELETE` per-row
  trigger that raises `audit_events is append-only: % operations are not allowed` with the offending
  `TG_OP`. It fires for *every* `UPDATE` / `DELETE` — including rows written by the table **owner** —
  because a `BEFORE` trigger cannot be bypassed by privileges. A trigger *function* (not a `GRANT`) is
  what makes this possible: an owner holds `UPDATE` / `DELETE` by ownership and `GRANT` / `REVOKE`
  cannot remove them from an owner, so only a trigger overrides that.
- **Least-privilege hardening.** `REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM PUBLIC` makes the
  intent explicit and guards against a future mis-grant; in `application-kubernetes.yml` the
  application connects as a dedicated least-privilege role holding only `INSERT` / `SELECT` on
  `audit_events`, the credential from the Kubernetes Secret, never from configuration.
- **Recovery is append, never rewrite.** A reconciliation after a crash or an ambiguous external
  outcome appends a corrected `FAILED` row (an INSERT the trigger allows) — a past row is never
  `UPDATEd`, so append-only holds after recovery too (invariant 3). `S12-08` adds no retention / purge
  path; that is the ops follow-up (`S12-11` … `S12-13`), and the trail simply grows until then.

### Verification

The guard is proven against data, not declared. `MigrationsTest` gained two tests —
`audit_events_append_only_guard_is_declared` (the `audit_events_append_only_trg` trigger is registered
on `audit_events` and its comments carry `S12-08`, the mandatory fields `actor_subject` / `outcome`
are non-nullable) and `audit_events_cannot_be_rewritten_or_deleted` (an `UPDATE` and a `DELETE` are
rejected with a message containing `append-only`, not a permission error) — run under Testcontainers on
the real PostgreSQL image, JUnit-XML verified (`tests="26"` `failures="0"` `errors="0"` `skipped="0"`,
all green this run). On a fresh PostgreSQL 18 schema `V3_3_29` applies cleanly through Flyway
(`./gradlew :apps:agentflow-app:flywayMigrate --rerun-tasks` → "now at version v3.3.29",
`BUILD SUCCESSFUL`), and on that live database an INSERT succeeds while an `UPDATE` / `DELETE` by both a
least-privilege role and a role carrying **all** privileges are rejected by the trigger.
`./gradlew :apps:agentflow-app:check` is green (unit + architecture — no new module edge — + formatting
+ OpenAPI).

### Documentation

- [`docs/security/audit-contract.md`](../security/audit-contract.md) §8 (Storage-level append-only enforcement) updated to describe the trigger and `REVOKE`; `V3_3_29` added to the contract references.
- `CHANGELOG.md` (this entry).

### Risks / follow-up

- The `CANON` audit verbs (`START` / `CANCEL` / `UPLOAD` / `READ` / `ALLOW` / `DENY`) are still only
  enumerated in the catalogue (`S12-07`) with no `auditService.record(...)` call site — wiring them is
  a separate task, outside the append-only guarantee this task delivers.
- No retention / purge path exists yet; the trail grows until the `S12-11` … `S12-13` ops follow-up
  owns it.

---

## S12-07 — the audit action catalogue (audit contract)

**Task (P0):** enumerate definition mutations, run start/cancel, document upload/read, approval, tool
allow/deny/execute and ops actions; define the mandatory actor / tenant / resource / outcome fields.
**Deliverable:** the contract [`docs/security/audit-contract.md`](../security/audit-contract.md); the
code-side expression is the `AuditAction` / `AuditResourceType` / `AuditOutcome` enums (`audit.domain`).

The plan fixes `S12-07`'s result as "audit contract" and its acceptance as "every security-sensitive
action leaves an audit entry." A contract — not a sprawling implementation — is the right first step: it
makes the acceptance criterion verifiable against a fixed list instead of a vague "audit the sensitive
stuff", and it records exactly which verbs are left to wire so the follow-up tasks (`S12-08` append-only
policy, and the per-flow wiring) are scoped and reviewable.

### What changed

- **Coverage (truthful, verified against the code).** Wiring is the set of `auditService.record(...)`
  call sites in the current sources:
  - `WIRED` today — definition `CREATE` (`WorkflowDefinitionService#createDefinition`,
    `WorkflowVersionService#createVersion`) / `ACTIVATE` (`WorkflowVersionService#activate`); tool
    `INVOKE` for started non-`READ_ONLY` calls (`ToolInvocationHistoryService`, S08-13); approval
    `APPROVE` / `REJECT` / `EXPIRE` (`ApprovalService`, `ADR-018`).
  - `CANON` (in the catalogue, no call site yet) — definition `UPDATE`, run `START` / `CANCEL`,
    document `UPLOAD` / `READ`, tool `ALLOW` / `DENY`. The `START` / `CANCEL` / `ALLOW` / `DENY` verbs
    reuse the existing `workflowexecution → audit` (`ADR-018`) / `toolexecution → audit` (`edge 4`)
    edges; `UPLOAD` / `READ` need a new `knowledgebase → audit` edge (an ADR) and are the follow-up.
  - `READ` auditing is deliberately scoped as an operational decision for that follow-up: a tenant
    reading its own document is tenant-isolated, not a privilege boundary, so auditing every read is
    high-volume / low-sensitivity — `UPLOAD` (a write boundary) is the security-sensitive half. Ops
    actions (`S12-11` … `S12-13`) reuse the verbs of the resource they act on rather than introducing an
    ops-specific verb.
- **Code change (minimal, canonical vocabulary).** `AuditResourceType` gains `RUN` / `DOCUMENT` — the
  enum javadoc already instructed "a run, a document … add their own entry here". `AuditAction` gains
  `START` / `CANCEL` (run), `UPLOAD` / `READ` (document) and `ALLOW` / `DENY` (tool authorization), with
  `INVOKE` keeping the side-effect call and `EXECUTE` expressed as `INVOKE`. The enums are stored as
  TEXT (`V3_3_5`), so **no new migration**; adding enum constants is **not** a module edge, so **no new
  ADR** and the architecture gate is unchanged.

### Verification

`:apps:agentflow-app:check` green — unit (`AuditServiceTest` / `AuditRepositoryTest`, JUnit-XML
verified) + architecture (`ArchitectureBoundaryTest` — no new edge) + no-cycles + formatting
(`spotless`). The new enum constants are TEXT values, so `MigrationsTest` is unaffected. No REST
endpoint, no new module edge, no migration, no contract-file change, no secrets or high-cardinality
labels. The protected `docs/AGENTS.md` / `audit/AGENTS.md` cross-references could not be edited (their
approval prompt timed out) — the authoritative index `docs/README.md` points to the contract
correctly; the `docs/AGENTS.md` `usage-and-costs.md` note still lists the audit catalogue under that
(planned) file and is a minor, noted drift.

### Documentation

- [`docs/security/audit-contract.md`](../security/audit-contract.md) — the new contract (the
  deliverable), enumerating every category, the mandatory fields and the `WIRED` / `CANON` coverage
  map.
- `CHANGELOG.md` (this entry).

### Risks / follow-up

- The `CANON` verbs are in the catalogue but have no call site yet — `S12-08` (append-only write
  policy) wires the `START` / `CANCEL` / `ALLOW` / `DENY` verbs; `UPLOAD` / `READ` still need the new
  `knowledgebase → audit` edge (an ADR).
- The `READ` auditing scope decision (operational, not a privilege boundary) is recorded here so the
  follow-up does not re-litigate it.

---

## S12-06 — the idempotent reconciliation of stranded reservations

**Task (P0):** write actual/estimated usage and release unused reservations; find stranded reservations
after a crash; use attempt IDs for idempotency.
**Deliverable:** the **reconciliation** half of the durable budget lifecycle (`S12-04`
`reserve` / `settle`).

The two tables are unchanged; migration `V3_3_28` (forward-only, `ADR-008`, owner `usage`) adds two
nullable columns to `budget_reservations`:

| Column | Meaning |
|---|---|
| `reconciled_at` (`timestamptz`) | set when the row was recovered by the reconciliation flow (unsettled → settled-at-best-known). Distinguishes a recovered orphan from one settled by the normal `settle` path, and makes the reconcile idempotent (`settled_at IS NULL AND reconciled_at IS NULL` guard). |
| `attempt` (`bigint`) | the operation attempt the reservation was keyed on. The idempotency key (`reservation_id`) is derived from `(tenant_id, run_id, attempt, metric)`, so a duplicate attempt of the same operation reuses the same key and a repeated `reserve` / `settle` is a no-op — "use attempt ID for idempotency" (invariant 3). `NULL` for rows created before `S12-06` (they predate the attempt key and reconcile by `reservation_id`). |

### What changed

- **The domain value and the repository.** `BudgetReservation` carries `reconciledAt` and `attempt`; a
  reservation's idempotency key is derived deterministically from `(tenant, run, attempt, metric)` so a
  crash-retried reserve with the same attempt lands on the same row. `BudgetRepository` gains
  `findUnsettled(tenantId, before)` — unsettled rows created before `before`, ordered by
  `(created_at, reservation_id)` for a deterministic scan — and `reconcileUnsettled(tenantId, before)`,
  a single guarded `UPDATE` that settles each stranded row at its **best-known** amount
  (`actual_cost = reserved_amount`, `settled_at = now()`, `reconciled_at = now()`) where
  `tenant_id = ? AND settled_at IS NULL AND created_at < ?`. `BudgetService.reconcileUnsettled` is
  `@Transactional` and logs one `agentflow.usage.budget.reconciled` counter per recovered row (no tenant
  / id label, invariant 9).

### Why

A reservation is settled only when the operation settles **after** it runs. If the worker dies
mid-operation — or a provider call is ambiguous — the reservation is left floating: `settled_at IS
NULL`, so it keeps counting at its `reserved_amount` estimate forever and the run's true consumption is
unknown. `S12-06` reconciles those stranded reservations: it settles them at their best-known amount and
marks them, so the budget reflects a recovered value instead of a stuck estimate, and the
reconciliation itself is idempotent (a duplicate reconcile is a no-op — invariant 3). The flow is
self-contained in `usage` — it needs no `workflowexecution` change and no terminal transition (the
"release at terminal" concern is the normal `settle`; stale-slot recovery for runs that never terminate
is a follow-up), which keeps the change minimal and fully testable.

### Acceptance

- **Re-settle does not double-charge.** `settle` is already guarded by `settled_at IS NULL`, and
  `reconcileUnsettled` uses the same guard — a second settle / reconcile of the same row is a no-op
  (verified by the idempotency assertions in `BudgetServiceTest` and `BudgetServiceIT`).
- **A stranded reservation is recovered.** `reconcileUnsettled` settles a row created before the
  boundary at its reserved amount, sets `reconciled_at`, and a second call reconciles nothing.

**Tenant isolation (invariant 1):** the reconcile `UPDATE` is keyed by `tenant_id` with the predicate
inside the query, so another tenant's stranded row is never reconciled for them; the `findUnsettled`
scan filters on `(tenant_id, settled_at, created_at)` too.

### Verification

Unit: `BudgetServiceTest` (11 DB-free tests, JUnit-XML-verified `tests="11"` `failures="0"`
`errors="0"` `skipped="0"` — reserve blocks on headroom, duplicate reservation is an idempotent no-op,
`settle` reconciles / is idempotent, `checkBudget` allows without a budget and denies once exhausted,
**reconcile settles a stranded reservation at its best-known amount once and is a no-op on the second
pass**, reconcile ignores an in-flight reservation created after the boundary, reconcile keys the
reservation id on `attempt`, tenant isolation, no-database fails loudly). Integration on real
`pgvector/pgvector:0.8.6-pg18` (`BudgetServiceIT`, 9 tests): the real SQL scan and the `created_at <
before` boundary, tenant-isolated reconcile, settle-at-best-known and the idempotent re-reconcile.
`MigrationsTest` (now at `v3.3.28`, `tests="24"`, `failures="0"` `errors="0"` `skipped="0"`,
JUnit-XML-verified) pins the new version and the owning table, asserting `reconciled_at` / `attempt` are
nullable and the schema guards exist. The pre-existing `budget_reservations` / `tenant_budgets` suites
(`BudgetServiceTest` / `BudgetServiceIT` — the `S12-04` reserve / settle coverage) re-run green with the
two new columns, proving the migration and the new `attempt` key leave the `S12-04` path unchanged.
`:apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the `usage`
boundary unchanged, no new module edge) + no-cycles + formatting (`spotless`). No new endpoint, no new
module edge, no new FK — `tenant_id` / `run_id` stay plain, tenant-scoped values (invariant 1).

### Documentation

- `CHANGELOG.md` (this entry).
- `docs/architecture/module-api.md` (the `usage` public-application-services row — `BudgetService`
  budget-reservation section, now with the reconciliation method; the permitted-types row —
  `BudgetReservation` fields `reconciledAt` / `attempt`).
- the `usage` module [`AGENTS.md`](../../apps/agentflow-app/src/main/java/com/example/agentflow/usage/AGENTS.md) (data ownership — `budget_reservations`).

---

## S12-05 — the per-tenant parallel-run quota

**Task (P1):** set limits on parallel runs and usage; count concurrent reservations in PostgreSQL; use
Redis only for auxiliary rate limiting / caching, never as the sole durable usage state.
**Deliverable:** `tenant_run_slots` (`V3_3_27`) — one row per non-terminal run a tenant holds, acquired
at run creation and released at the terminal transition.

The quota is the parallel-run half of the per-tenant limits Sprint 12 requires (plan `S12-05`; the
usage half — tokens / cost — is the `S12-04` budget). `usage` owns it, backed by migration `V3_3_27`
(forward-only, `ADR-008`).

### What changed

- **The table.** `tenant_run_slots` — one row per **non-terminal** run a tenant holds, acquired at run
  creation and released at the terminal transition, so the slot lifetime is exactly the run's
  non-terminal lifetime. The limit itself is **not** duplicated: it stays the `max_runs` column of
  `tenant_budgets` (`V3_3_26`, `S12-04`) this quota enforces. A tenant with no `tenant_budgets` row is
  unlimited (never blocked) but its runs are still tracked, so the count stays truthful if a limit is
  configured later. `tenant_id` / `run_id` are plain, tenant-scoped values, not cross-module FKs;
  tenant isolation lives on the composite primary key and in every predicate (invariant 1).
- **The race acceptance — counted in PostgreSQL, never in Redis (invariant 2).**
  `application.QuotaService#acquireRunSlot` locks the tenant's `tenant_budgets` row
  (`SELECT ... FOR UPDATE`, the same serialization `BudgetService#reserve` uses), then counts the
  tenant's active slots and inserts the new row while still holding the lock. Two concurrent run
  creations therefore cannot both pass the headroom check: the second blocks on the row lock,
  re-reads the count after the first commits, and is refused. Redis is used only for auxiliary rate
  limiting / caching, never as the durable quota state.
- **Acquire at create, release at terminal — idempotently (invariant 3).** The composite
  `(tenant_id, run_id)` primary key makes a repeated acquisition an `ON CONFLICT DO NOTHING` no-op — the
  same run re-acquiring (a duplicate delivery / an idempotent replay) is allowed, never counted twice —
  and a repeated release is a `DELETE` of an already-absent row. The release lives in
  `workflowexecution.application.RunStateMachineService` (both `transitionRun` overloads, after the
  transition and its event, in the same transaction), because every terminal write funnels through the
  single transition service (invariant 4) — one seam covers `SUCCEEDED` / `FAILED` / `CANCELLED` from
  the worker, the cancel path and the limit-exceeded path alike. A run that never becomes terminal (e.g.
  its pinned graph is missing) keeps its slot: stale-slot recovery is `S12-06`'s reconciliation
  concern, recorded there.
- **The execution-SPI gate — the `S12-04` shape.** `workflowexecution` owns the `RunQuotaGuard`
  **port** (acquire before the run row is written / release at the terminal state); `usage` supplies
  the `@ConditionalOnBean(DataSource.class)` adapter `UsageRunQuotaGuard`. It is the same sanctioned
  `usage → workflowexecution` module pair as the `S12-04` `BudgetGuard` / `UsageBudgetGuard` (edge 9)
  — no new module edge, no ADR. `RunService#createAndPin` acquires after the budget check and before
  `workflow_runs` + outbox insert, in the caller's transaction: an over-quota tenant is refused with
  `409 CONFLICT` and no run row, no event, no slot. The `@Autowired(required = false)` seam keeps the
  app bootable without a database (`S01-02`).

### Why

- **The limit is not duplicated.** The quota enforces the `max_runs` column of `tenant_budgets`; it
  does not store its own limit, so the two cannot drift.
- **The durable count lives in PostgreSQL (invariant 2).** Redis is only auxiliary rate limiting /
  caching; losing it never changes the durable quota, because the slot lifetime and the count are
  row-lock-serialized in the database, never in memory.

### Verification

Unit: `QuotaServiceTest` (8 DB-free tests — allow/track without a budget, allow up to the limit then
reject, a zero limit permits no run, duplicate acquisition is idempotent, release frees the slot,
release without a slot is a no-op, tenant isolation, no-database fails loudly). Integration on real
`pgvector/pgvector:0.8.6-pg18` (`QuotaServiceIT`, 4 tests) — the **race acceptance**: 8 contenders,
each owning its own connection and transaction, released at the same instant against a limit of 3 —
exactly 3 acquire and 5 are refused, and the table holds exactly 3 rows; plus acquire/release
round-trip, duplicate-acquisition idempotency and unlimited-tenant tracking / isolation.
`MigrationsTest` (now at `v3.3.27`, `tests="24"` `failures="0"` `errors="0"` `skipped="0"`,
JUnit-XML-verified) pins the new version and the owning table. The touched `workflowexecution` suites
re-run green (`RunServiceTest` 16, `RunStateMachineServiceTest` 14, `TransitionTableServiceTest` 51;
`RunServiceAtomicIT` 5, `WorkflowCancelEndToEndIT` 3, `WorkflowTransitionIntegrationTest` 10,
`WorkflowTransitionConcurrencyIT` 2, `WorkflowWorkerEndToEndIT` 1, `WorkflowWorkerCrashRecoveryIT` 1,
`WorkflowWorkerLimitsIT` 2 — all `failures="0"` `errors="0"`, fresh reports this session), proving the
widened constructors and the null-guard skip leave every existing path unchanged.

### Documentation

- [`docs/architecture/module-api.md`](../architecture/module-api.md) (the `usage` public-application-services row — `QuotaService`; the permitted-types row — `QuotaCheckResult`; the `persistence.RunSlotRepository` internal package; the `workflowexecution` services row — the `BudgetGuard` / `RunQuotaGuard` ports).
- the `usage` module [`AGENTS.md`](../../apps/agentflow-app/src/main/java/com/example/agentflow/usage/AGENTS.md) (data ownership — `tenant_run_slots`).
- `CHANGELOG.md` (this entry).

---

## S12-04 — the durable per-tenant cost budget

**Task (P0):** bound the maximum steps, calls, tokens and estimated cost; check before the expensive
call; reserve and settle idempotently.
**Deliverable:** `tenant_budgets` / `budget_reservations` (`V3_3_26`) — the durable budget the domain
glossary "Cost budget" / "Budget" names (glossary §10 "Cost budget", §9 "Budget", plan `S12-04`).

`usage` owns it — the same owner as `usage_records` — backed by migration `V3_3_26` (forward-only,
`IF NOT EXISTS`, `ADR-008`): two tables.

### What changed

- **The tables.**
  - `tenant_budgets` — one per-tenant row of limits (`period`, `max_runs`, `max_tokens`, `max_cost`),
    measured over a `period`. One row per tenant. `tenant_id` is a plain, tenant-scoped value, **not**
    a cross-module FK (`tenants` / `workflow_runs` are owned by other modules — a cross-module FK would
    couple the schemas, exactly as `tool_invocations` scopes `run_id`).
  - `budget_reservations` — one row per reservation an operation makes against that budget: the
    estimated amount reserved **before** the operation, reconciled to the **actual cost** when the
    operation settles. Every reservation counts toward the tenant's consumption at its reserved
    estimate until it settles, and at its reconciled `actual_cost` afterwards — both count, so the
    budget stays meaningful once everything settled.
- **Reserve before the call, settle after — idempotently.** `application.BudgetService`
  `reserve(...)` keys the reservation on an idempotency id (`reservation_id`); the repository's
  `ON CONFLICT (reservation_id) DO NOTHING` primary key makes a duplicate delivery / crash-retry a
  no-op, so it never double-charges (invariant 3). `reserve` locks the tenant's budget row
  (`SELECT ... FOR UPDATE`) and checks the reservation against the remaining budget, throwing
  `BudgetExhaustedException` when it would exceed it — blocking the operation *before* it starts.
  `settle(...)` reconciles the reservation to its actual cost once, guarded by `settled_at IS NULL`
  (a second settle is a no-op; at-least-once, never exactly-once). `consumed(...)` sums the accrual
  `COALESCE(actual_cost, reserved_amount)` — an unsettled reservation counts at its reserve estimate, a
  settled one at its reconciled actual cost. A tenant with no budget configured is **allowed**
  (unlimited, never blocked). The refusal signal `BudgetExhaustedException` is the `usage` mirror of
  `ai`'s pre-call `InsufficientBudgetException` (`S09-10`).
- **The value vocabulary.** `domain.BudgetDto` (`tenantId`, `period`, `maxRuns`, `maxTokens`,
  `maxCost`), `domain.BudgetPeriod` and `domain.BudgetMetric` (`COST` / `TOKEN`),
  `domain.BudgetReservation` (`reservationId`, `tenantId`, `runId`, `metric`, `reservedAmount`) and
  `domain.BudgetCheckResult` (`allowed`, `reason`).
- **Tenant isolation (invariant 1).** Every read and write is keyed by `(tenantId, …)` with the
  predicate inside the query itself — the accrual `consumed` sum, the `FOR UPDATE` lock and the
  idempotent inserts all carry the `tenant_id` — so another tenant's budget is indistinguishable from
  a missing one.
- **The execution-SPI gate.** `workflowexecution` owns the `BudgetGuard` **port** — the same shape as
  the `StepHandler` port; `usage` supplies the `@ConditionalOnBean(DataSource.class)` adapter
  `UsageBudgetGuard` implementing it. This is the single sanctioned compile edge `usage →
  workflowexecution` (module-map edge 9), so `workflowexecution` never compile-depends on `usage`. It
  is wired into `workflowexecution.application.RunService#createAndPin`: a run cannot start against an
  exhausted budget — the check runs **before** the run row and the outbox event are written, and a
  rejected run starts with `409 CONFLICT`. The `@Autowired(required = false)` seam keeps the app
  bootable without a database (a `null` guard skips the check, `S01-02`).
- **Durability (invariant 2).** PostgreSQL is the source of truth — `tenant_budgets` and
  `budget_reservations` are plain rows; no reservation is ever only in memory. No raw prompt, document
  or cost secret is stored — only the reservation id (the idempotency key) and its amount (invariant 8).

### Verification

Unit: `BudgetServiceTest` (7 DB-free tests — `checkBudget` allows without a budget and denies once
exhausted, `reserve` blocks when it would exceed the remaining budget, a duplicate reservation is an
idempotent no-op, `settle` reconciles / is idempotent, a `null` actual cost keeps the estimate).
Integration on real `pgvector/pgvector:0.8.6-pg18` (`BudgetServiceIT`, 6 tests) — a budget
round-trips through the store, reserve blocks the moment it would exceed the limit, settlement
reconciles idempotently, and another tenant's budget is isolated. `MigrationsTest` (now at `v3.3.26`,
`tests="25"` `failures="0"` `errors="0"` `skipped="0"`, JUnit-XML-verified) asserts the two tables are
owned by `usage` and the schema guards exist. The full `./gradlew check --rerun-tasks` gate — unit +
architecture (`ArchitectureBoundaryTest` `tests="1"`, `NoCyclicModuleDependenciesTest` `tests="1"`
`failures="0"` `errors="0"`, the `usage ↔ workflowexecution` edge 9) + no-cycles + formatting
(`spotless`) + OpenAPI — green; `git diff --check` clean.

### Documentation

- [`docs/architecture/module-api.md`](../architecture/module-api.md) (the `usage` public-application-services row — `BudgetService`; the permitted-types row — `BudgetDto` / `BudgetPeriod` / `BudgetMetric` / `BudgetReservation` / `BudgetCheckResult` / `BudgetExhaustedException`; the `persistence.BudgetRepository` internal package).
- [`docs/architecture/module-map.md`](../architecture/module-map.md) (the `usage` module row — `tenant_budgets` under `usage` — and edge 9 `usage → workflowexecution`, no new edge, no ADR).
- [`docs/domain-glossary.md`](../domain-glossary.md) (the "Cost budget" / "Budget" rows).
- `CHANGELOG.md` (this entry).

---

## S12-03 — the usage price catalogue and versioning

**Task (P1):** store model/price and unit, effective timestamp and pricing revision; document the
accuracy of the estimated bill.
**Deliverable:** the resolution layer in the `usage` module — the price catalogue the `S12-02`
recording path settles against (glossary, plan `S12-03`).

A usage record carrying `estimated_cost` was computed from *some* price — this task records *which*
one, so every historical estimate stays explainable through the exact price version that produced it.

### What changed

- **The resolution layer.** The `usage` module gains:
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
- **The migration.** `usage_records` gains migration `V3_3_25` (owner `usage`, forward-only,
  `IF NOT EXISTS` for quiet Testcontainers rebuilds): two nullable `TEXT` columns.
  - `price_version` — the specific `model+version` price the estimate was computed against; `NULL` when
    the catalogue does not yet know the model, so an unpriced estimate is recorded as **unknown**, never
    invented (invariant 8, `S12-02`).
  - `pricing_revision` — the revision tag of the `usage.pricing` catalogue that produced the estimate;
    `NULL` when no price was resolved.

  Both are nullable on purpose: a `NULL` here means "no price was resolved" — a real state (the
  `S12-02` unknown path), not a missing value to be rejected. They are written together by the `usage`
  module, so a row never stores one without the other. The column `COMMENT`s attribute both to
  `S12-03`.
- **The record.** `UsageRecordDto` gains `priceVersion()` / `pricingRevision()`, plus a **priced**
  factory — `fromChatResponse(tenantId, runId, stepRunId, attempt, model, response, pricing)` fills the
  two versioning columns from a resolved `PricingInfo`. The unchanged 19-argument positional
  constructor (the common `S12-02` recording) leaves them `null` — the unpriced path.
- **Unknown is recorded as unknown.** A record with no resolved price stores `null` for both
  versioning columns, so the row truthfully records that no price was applied rather than a guessed one.

### Verification

DB-free unit suites, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`, fresh reports this
session), **33 usage tests total** — `UsageRecordDtoTest` (15, including the two new priced
`fromChatResponse` cases — `estimatedCostFor` maths and the unpriced record leaving versioning `null`);
`PricingResolverTest` (8 — the divisor / latest-price / currency resolution and the `null`-price unknown
path); `UsageRowMapperTest` (3 — the two columns round-trip, `null`-safe); `UsageRepositoryTest` (4 —
the two columns bind last, `null`-safe); `UsageMeteringServiceTest` (3). The real-PostgreSQL
`MigrationsTest` (`integrationTest`) asserts `V3_3_25` is applied and the two columns are nullable with
`S12-03` attribution.

### Documentation

- `CHANGELOG.md` (Unreleased).
- this file.

---

## S12-02 — the uniform usage recording path

**Task (P0):** record usage uniformly for a model, a tool or an embedding; mark unreported token
values as unknown, not invented; test with the fake providers.
**Deliverable:** the `usage` application service.

The `usage` module records one token/cost measurement per call — uniformly for a model, a tool or an
embedding — through one validated path, so the `usage_records` rows are faithful and the module does
not leak a package cycle. The module gains:

- `application.UsageMeteringService` — the single recording service (the `usage → ai` edge of
  module-map arrow 8): `fromChatResponse` builds the `MODEL` measurement of one chat-completion call
  from the provider `ChatResponse` through the `ai.application` `ChatResponse` / `ChatUsage` port, and
  a tool path records a `TOOL` invocation. Each call produces exactly one `UsageRecordDto`.
- `domain.UsageRecordDto` — the immutable measurement value, the mirror of the `usage_records` row:
  `id`, `tenantId`, `runId`, `stepRunId`, `attempt`, `usageType` (`MODEL` / `TOOL` / `EMBEDDING`),
  `measurement` (`ESTIMATED` / `PROVIDER_REPORTED`), `provider`, `model`, `toolName`, `toolVersion`,
  `inputTokens`, `outputTokens`, `estimatedCost`, `providerCost`, `unit`, `currency`,
  `providerRequestId`, `providerMetadata`.
- `domain.UsageType` (`MODEL` / `TOOL` / `EMBEDDING`) and `domain.Measurement` (`ESTIMATED` /
  `PROVIDER_REPORTED`) — the two bound enums.

### What changed

- **Unknown is recorded as unknown.** The token counts are boxed `Long` (nullable): a value the
  provider did not report is stored as `null`, never coerced to `0` (invariant 8). `fromChatResponse`
  therefore leaves both `inputTokens` and `outputTokens` `null` on a refusal (`ChatResponse.usage()`
  `== null`), and a pre-catalogue record carries `estimatedCost == null` until the price is known — the
  record is truthful with no reported values yet (`DEFAULT ESTIMATED`).
- **Contract failures, classified.** The canonical constructor rejects a `null` id / tenant / run, a
  stale `attempt` (< 1), a `null` enum, a blank `provider`, or a missing measured identity — a `MODEL` /
  `EMBEDDING` record without a `model`, a `TOOL` record without a `toolName` — with an explicit
  `IllegalArgumentException`, the same pattern `ToolDescriptor` / `ReportFormatter` use. It is
  deliberately *not* `Objects.requireNonNull` (in JDK 25 that throws `NullPointerException`, which would
  mis-classify a contract failure as a generic fault so the worker could not route it to the
  `FAILURE_CLASS.CONTRACT_FAILURE` path).
- **No package cycle.** The shared record lives in `domain` (like `AuditEvent` / `ToolInvocationRecord`),
  so both `application` (the metering service) and `persistence` (`UsageRepository` / `UsageRowMapper`)
  depend on `domain` — and only on `domain` — without either importing the other. An earlier
  `application` placement made `application ↔ persistence` a cycle the acyclicity gate rejected.

### Verification

Four DB-free suites, JUnit-XML-verified (`failures="0"` `errors="0"` `skipped="0"`, fresh reports this
session), 22 tests total: `UsageRecordDtoTest` (13 — the 19-field constructor, `fromChatResponse` from
a real `ChatResponse`, the refusal leaves tokens `null`, every contract-failure branch, `usageType` /
`measurement` round-trip through the enums); `UsageMeteringServiceTest` (3 — uniform recording via
`@InjectMocks`); `UsageRowMapperTest` (2); `UsageRepositoryTest` (4 — the tenant-scoped select with the
verified SQL). The full `./gradlew :apps:agentflow-app:check` gate — unit + architecture
(`ArchitectureBoundaryTest` `tests="1"`, `NoCyclicModuleDependenciesTest` `tests="1"` `failures="0"`
`errors="0"`, the `usage` package now `application ↔ domain` and `persistence ↔ domain` only) +
no-cycles + formatting (`spotlessApply`) — green; `git diff --check` clean.

### Documentation

- `docs/architecture/module-api.md` (the `usage` public-application-services row — uniform
  model/tool/embedding recording — and the permitted-types row, the full `UsageRecordDto` field set and
  its `domain` placement).
- `docs/architecture/module-map.md` (arrow 8 `usage → ai`).
- the `usage` module `AGENTS.md`.

---

## S12-01 — the usage record schema

**Task (P0):** persist tenant/run/step/attempt, usage type, model/tool, input/output tokens, estimated
cost, unit/currency and provider metadata; distinguish estimated from provider-reported usage.
**Deliverable:** the migration `V3_3_24` — the durable token/cost metering table (glossary §10, plan
Sprint 12, `S12-01`).

`usage` owns this table — the same owner as `tenant_budgets` and the future settlement / quota tasks
(`S12-02` … `S12-06`). One row per measurement of one attempt: a single model call, tool call or
embedding that produced `input_tokens` / `output_tokens`, an estimated cost, the pricing unit and
currency. It is the durable record the later budget / settlement / quota tasks read and reconcile, and
the audit of what a run spent.

### What changed

- **The row pins the measurement to a specific attempt.** `tenant_id`, `run_id`, `step_run_id` and
  `attempt` are all present, so a record is never a floating number but always a measurement of one
  concrete attempt. The `attempt` is `NOT NULL` — a usage record without an attempt is not a
  measurement of anything concrete; it is the column that ties the record to a specific attempt (the
  `S12-01` acceptance). The record is metadata only — it **never** stores the raw prompt, the raw
  document or a full conversation (invariant 8). It stores the token counts and the cost, never the
  content that was measured.
- **Columns.**
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
  - `usage_type` — what was measured: `MODEL` / `TOOL` / `EMBEDDING`; bounded by
    `usage_records_usage_type_check`.
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

- **No raw prompt, no document, no full conversation on the row** — only the token counts and the cost
  (invariant 8). `IF NOT EXISTS` keeps Testcontainers rebuilds and the controlled apply step quiet
  (`database-migrations.md`, rule 5). Forward-only (`ADR-008`). One lookup index
  `usage_records_tenant_run_created_idx` on `(tenant_id, run_id, created_at)`, the tenant predicate
  leading (invariant 1).

### Why

- **One row per measurement of one attempt (invariant 10).** A usage record is metadata about the cost
  of an execution, never the measured content; the `NOT NULL attempt` is the column that ties each row
  to a concrete attempt, so the budget / settlement / quota tasks (S12-02 … S12-06) reconcile real
  measurements rather than floating numbers.
- **PostgreSQL is the source of truth (invariant 2).** The table is plain rows the later durable tasks
  read and reconcile; Redis is never consulted for cost.
- **Tenant isolation (invariant 1).** Every row carries `tenant_id`; a tenant-scoped read filters on
  `run_id` / `step_run_id` without a cross-module JOIN.

### Verification

The acceptance lives in the schema and in data. `MigrationsTest` (24 tests, JUnit-XML-verified,
`tests="24"` `failures="0"` `errors="0"`):

- `usage_records_schema_guards_exist` — asserts the lookup index, the two CHECKs, the NOT NULL
  `attempt` / `tenant_id` / `run_id` / `created_at` columns, the nullable token / cost columns, and the
  table / `attempt` COMMENT attributing the table to `S12-01`.
- `usage_records_link_to_attempt_and_tenant_hold_against_data` — on the live database, a valid `MODEL`
  record for (run, step, attempt 1) lands, a record for the same (run, step) with a different attempt
  lands too (attempt is a real dimension, not a duplicate key), a provider-reported `TOOL` record with
  unknown tokens (NULL) lands, a record with a missing parent tenant is rejected by the foreign key, an
  unknown `usage_type` by the CHECK, and a missing `attempt` by NOT NULL.

`./gradlew :apps:agentflow-app:flywayMigrate` applies the ordered line at `v3.3.24` (25 migrations, 0
failed). The migration file is `apps/agentflow-app/src/main/resources/migrations/V3_3_24__create_usage_records.sql`.

### Documentation

- `docs/domain-glossary.md` — the "Usage record" row (the full column set; the two measurement
  sources explained).
- this file.

### Risks / follow-up

- The later tasks build on this table: `S12-02` (settle / estimate), `S12-03` (price catalogue),
  `S12-04` (budgets) and `S12-06` (settlement).
- `attempt` is a real dimension, not a duplicate key: the same `(run, step)` may legitimately carry
  several records, one per attempt, so the budget reconciliation counts each concrete attempt.
