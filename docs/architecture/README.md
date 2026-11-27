# Architecture index — AgentFlow

> **Status:** Accepted
> **Owner:** engineering
> **Task:** `S02-15`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (§2 stack, §3 ADRs,
> §4 modules, §6–§11 invariants) · root `AGENTS.md` (dependency rules, invariants,
> Open decisions) · `docs/adr/` (ADR register)
> **Purpose:** the navigation page for the architecture documentation. It points to
> where each architecture topic lives, links the documents that exist today, marks
> the ones that do not yet exist with a `⚠ planned` badge, and records the current
> architectural constraints and the list of deferred decisions. It is a map, not a
> restatement — every link below is a single source of truth.

---

## How to read this index

This index sits under the **documentation index** (`docs/README.md`), which is the
documentation entry point. The **project** entry point is the root
[`README.md`](../README.md).

- This index **links** to the architecture documents; it does **not** copy them.
  There is one owner per topic (see the *One source of truth* rule in
   [`docs/AGENTS.md`](../AGENTS.md)).
- A document that exists today is a clickable link. A document that does not yet
  exist is a plain `⚠ planned` path, never a link — so the index never carries a
  broken link.
- The two C4 diagrams (Level 1 *system context*, Level 2 *module map*) are the
  anchors of this index; the glossary, error taxonomy and ADR register are the
  references they depend on.

---

## Architecture documents (exist today)

| Document | Covers | Link |
|---|---|---|
| **System context** (C4 Level 1) | External actors and systems, the trusted boundary, and the direction of trust for secrets and documents | [`system-context.md`](./system-context.md) |
| **Module map** (C4 Level 2) | Bounded contexts, each module's responsibility, data ownership (one owner per table), business-rule ownership, and the single allowed dependency direction | [`module-map.md`](./module-map.md) |
| **Module public API** | Public application services, published event types, permitted DTOs and internal packages per module (the boundary architecture tests check) | [`module-api.md`](./module-api.md) |
| **Error taxonomy** | Canonical `error_class` set, HTTP mapping (RFC 9457 Problem Details), retry decision, the `401/403/404` policy | [`error-taxonomy.md`](./error-taxonomy.md) |
| **Durable execution** | Run/step state machine, run-level lease + heartbeat, `lease_epoch` fencing, crash-window recovery and the recovery scheduler ([`ADR-011`](../adr/ADR-011-workflow-run-lease.md), `S07-17`) — operational runbook in [`docs/runbooks/workflow-recovery.md`](../runbooks/workflow-recovery.md) | [`durable-execution.md`](./durable-execution.md) |
| **Run state machine** | Run states, transitions, triggers and guards (`S05-01`), reconciled with the lease/fencing model | [`run-state-machine.md`](./run-state-machine.md) |
| **Step state machine** | Step states, transitions, triggers and guards (`S05-02`) | [`step-state-machine.md`](./step-state-machine.md) |
| **Document lifecycle** | `documents.status` state machine — `UPLOADED → QUEUED → PROCESSING → INDEXED`, with `FAILED`, `DELETE_REQUESTED`, `DELETED`; terminal and retry states, per-transition guards, lease/fencing and the one-transition-service rule | [`document-lifecycle.md`](./document-lifecycle.md) |
| **Events catalog** | Topics, producers/consumers, partition key, envelope fields, at-least-once + idempotency, retry/DLQ, outbox/inbox and the safe-contract-change rules | [`events/README.md`](./events/README.md) |
| **Kafka topics** | Topic catalogue, partition keys and the single producer/consumer ownership (`S06-02`) | [`kafka-topics.md`](./kafka-topics.md) |
| **Polling outbox benchmark** | Publish delay and poll load of the transactional-outbox publisher (`ADR-004`), and the numerical gate for a future Debezium CDC migration (`S06-15`) | [`observability/outbox-polling-benchmark.md`](./observability/outbox-polling-benchmark.md) |
| **Engineering conventions** | `UUID`, UTC `timestamptz`, injectable `Clock`, identifier and naming conventions | [`engineering-conventions.md`](./engineering-conventions.md) |
| **API and persistence conventions** | `/api/v1` endpoints, pagination, RFC 9457 Problem Details, optimistic locking, tenant-scoped lookup and how to change the OpenAPI contract | [`api-conventions.md`](./api-conventions.md) |
| **Database migrations** | Migration rules and the single process: forward-only, expand/contract, forward-fix, adding a migration | [`database-migrations.md`](./database-migrations.md) |
| **Database conventions** | Tables, columns, `timestamptz`, unified audit timestamps, constraint/index naming and optimistic versioning | [`database-conventions.md`](./database-conventions.md) |
| **Dependency management** | Version catalog, BOM policy, dependency locking, `check` gate | [`dependency-management.md`](./dependency-management.md) |
| **Dependency insight** | Resolved dependency graph (S01-08, reference artifact) | [`dependency-insight.txt`](./dependency-insight.txt) |
| **Domain glossary** | Canonical names, one concept — one name, concept → owner (§13) | [`../domain-glossary.md`](../domain-glossary.md) |
| **ADR register** | Architecture decision records, statuses, lifecycle | [`../adr/README.md`](../adr/README.md) |
| **Threat model** | Assets, attacker goals, trust boundaries, mitigations (tenant isolation, prompt injection, SSRF, log leaks) | [`../threat-model/README.md`](../threat-model/README.md) |
| **Redis usage** | The single operational reference for every Redis use — key format, TTL, failure mode, config reference, observability (`ADR-021`, `S12-21`) | [`redis-usage.md`](./redis-usage.md) |
| **Object storage** | The provider-neutral `ObjectStoragePort` contract — the five operations, `ObjectReference`/value types, stream ownership, idempotency and error policy (`S02-19`) | [`object-storage.md`](./object-storage.md) |

---

## Architecture diagrams

The two Mermaid diagrams are the navigational anchors of this index. Both live in
Markdown inside their own documents (so they live in the diff) and are kept in
sync with the code — a stale diagram is worse than none.

- **C4 Level 1 — system context** (`system-context.md`): the Client, Keycloak, the
  model provider, object storage and allowlisted external tools around the trusted
  AgentFlow boundary.
- **C4 Level 2 — module map** (`module-map.md`): the thirteen bounded contexts
  behind that boundary, their owners, and the allowed `module -> module` edges
  (the execution `StepHandler` SPI is the only sanctioned feedback path).
- **Execution diagrams** — the run / step state machines (`run-state-machine.md`,
  `step-state-machine.md`), the document lifecycle (`document-lifecycle.md`) and
  the lease/fencing recovery model (`durable-execution.md`) carry their own
  state diagrams; the two C4 diagrams above anchor the index, these carry the
  lifecycle detail.

---

## Deferred / open decisions

The plan fixes the modules and the stack, but a few decisions are still open.
They are recorded here so a reader sees what is decided and what is not. A
decision is promoted out of this list with an ADR once the trade-off is resolved.

| Decision | Status | Where it is resolved / who |
|---|---|---|
| Base package `com.example.agentflow` and the intra-module sub-package layout (`domain` / `application` / `web` / `persistence` / `worker`, by-feature) | **Resolved.** Fixed by `S02-07` and established in code (`apps/agentflow-app/AGENTS.md`) | `apps/agentflow-app/AGENTS.md`, root `AGENTS.md` (settled) |
| Execution SPI — how `workflowexecution` calls `toolexecution` / `ai` / `knowledgebase` without a compile-time cycle | **Resolved.** `workflowexecution` (`domain.StepHandler`) owns the `StepHandler` port; the three implementers depend on the port owner, so the orchestrator never depends on them back | `module-map.md` ("The execution SPI is the only feedback path") — root `AGENTS.md` (settled) |
| Ownership of data/logic not named in plan §4.2 (approvals, idempotency keys, DLQ, tool registry, redaction, global Problem Details) | Data ownership **resolved** by `module-map.md` (one owner per table); the remaining specifics are still open (`approvals`/idempotency → `workflowexecution`, DLQ → `messaging.dlq`, `tool_definitions` → `toolexecution`, secret redaction → `common.redaction`, global Problem Details handler → `common.error`) | `module-map.md`, root `AGENTS.md` (still open) |
| Permission catalogue | The plan's `S04-04` list is used; the example scopes in plan §11.1 (`knowledge:read`, `tenant:admin`) are illustrative, not final | `docs/domain-glossary.md`, plan §11.1 (still open) |
| Persistence approach and migration tool | **Resolved.** Single persistence approach: Spring Data JDBC, documented in [`ADR-009`](../adr/ADR-009-single-persistence-spring-data-jdbc.md) (`S03-08`). Single migration tool: Flyway, documented in [`ADR-008`](../adr/ADR-008-database-migrations.md) (`S03-01`). No JPA + jOOQ side by side, no mixing tools — the fixed-stack invariant | [`ADR-009`](../adr/ADR-009-single-persistence-spring-data-jdbc.md), [`ADR-008`](../adr/ADR-008-database-migrations.md), root `AGENTS.md` |

---

## Current architectural constraints

These are the invariants that hold today. They are enforced by architecture tests
where possible (`S02-08` rejects any edge outside the module-map table;
`S02-09` rejects any cycle in the compile graph), not by code review alone.

- **Modular monolith first** — domain modules live in one app and one image;
  boundaries are checked automatically ([`ADR-001`](../adr/ADR-001-modular-monolith.md)).
- **PostgreSQL is the source of truth** for workflow state; Kafka delivers, Redis
  caches, and losing Redis must not lose a run ([`ADR-002`](../adr/ADR-002-postgresql-source-of-truth.md)).
- **Redis is a helper, never a store of truth** — cache, rebuildable cache and
  rate-limit state only; never workflow state, lease/fencing, idempotency, inbox,
  permissions or usage/cost counters; failure mode documented per use ([`ADR-021`](../adr/ADR-021-redis-role.md)).
- **At-least-once + idempotency** — never promise exactly-once between PostgreSQL
  and Kafka; state and the outbox record write in one transaction ([`ADR-003`](../adr/ADR-003-at-least-once-idempotency.md)).
- **Transactional outbox** between PostgreSQL and Kafka, polling publisher,
  idempotent publish by `event_id` ([`ADR-004`](../adr/ADR-004-transactional-outbox.md)).
- **Versioned workflow DSL with no arbitrary code** — a versioned, typed JSON graph of
  allowed steps (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`,
  `LLM_EVALUATE`, `FINALIZE`); an invalid or unsafe graph is rejected before it is
  serialized or activated ([`ADR-005`](../adr/ADR-005-versioned-workflow-dsl.md)).
- **Tenant isolation** — the tenant comes only from the verified security context
  (HTTP or a stored event re-checked against PostgreSQL); another tenant's resource
  is indistinguishable from a missing one (`404`/`403`) ([`ADR-007`](../adr/ADR-007-tenant-isolation.md), plan §11).
- **AI is an untrusted planner** — the model proposes a typed plan that a
  server-side validation gate checks before execution; it never changes
  permissions, policies or allowlists ([`ADR-006`](../adr/ADR-006-untrusted-planner.md)).
- **Tool risk is classified by default** — every tool carries a risk class
  (`READ_ONLY` / `WRITE` / `EXTERNAL_SIDE_EFFECT`); an unknown or missing class
  is denied by default, and the classification is defined once and owned by the
  catalogue ([`ADR-017`](../adr/ADR-017-tool-risk-classification.md)).
- **Errors are classified** by the taxonomy; retry applies only to transient
  classes (plan §12, invariant 12).
- **Build integrity** — one Java 25 toolchain, one formatter (Spotless/Google Java
  Format), version-catalog dependency management and dependency locking; a
  formatting or boundary violation fails `./gradlew check`.

---

## Verification against the build

The module names and external systems in these documents are checked against the
build and repository, not assumed:

- **Modules.** The thirteen modules in the module map (`identity`, `agentcatalog`,
  `workflowdefinition`, `workflowexecution`, `toolexecution`, `knowledgebase`,
  `ingestion`, `storage`, `ai`, `usage`, `audit`, `messaging`, `observability`) plus the
  cross-cutting `common` package are present under
  `apps/agentflow-app/src/main/java/com/example/agentflow/<module>/`, matching the
  base package `com.example.agentflow` (main class
  `com.example.agentflow.AgentflowApplication`, `apps/agentflow-app/build.gradle.kts`).
  `storage` is the one module whose implementation does not exist yet (the plan,
  the module map, the ownership boundary and the contract are in place — the
  contract is [`object-storage.md`](./object-storage.md), `S02-19`; the adapter and its
  code land with `S07-06B`) — it is absent from `src/` today, which is expected and tracked.
  Two further packages live under the same root but are **not** domain modules: `config`
  (framework wiring — Hikari, rate limiter, Redis, MCP/OpenAPI beans) and `tooling`
  (build tooling — `FlywayMigrate`, `OpenApiContractTool`). There is no `storage`
  module yet — the ownership boundary and the contract are done (`S02-18`/`S02-19`);
  the adapter implementation is pending (`S07-06B`). Today Garage / S3 is an external
  runtime dependency.
- **External systems.** Keycloak (OIDC), the model provider, Garage / S3 object
  storage, PostgreSQL (+ pgvector / FTS), Kafka, Redis and OpenTelemetry are the
  runtime dependencies named in the system context and module map; Spring AI is
  consumed through its BOM (`gradle/libs.versions.toml`, `apps/agentflow-app/`).
  They are described as external/runtime dependencies, not app modules, so the
  architecture tests do not treat them as module edges.

---

## Related

- [`docs/README.md`](../README.md) — the documentation index (this index sits under it).
- [`docs/adr/README.md`](../adr/README.md) — the ADR register (created by `S02-16`).
- [`docs/AGENTS.md`](../AGENTS.md) — documentation conventions and the "one source of
  truth" rule.
- [`AGENTFLOW_ARCHITECTURE_AND_PLAN.md`](../../AGENTFLOW_ARCHITECTURE_AND_PLAN.md)
  — the target backlog (`S02-15`).

**Planned — not yet created (marked `⚠ planned`, never linked):**

- `ADR-024-object-storage.md` — Object Storage consistency, security and lifecycle (tenant-scoped key convention, no public buckets, credential separation, DB↔Garage consistency gap, upload/delete state transitions, retry, compensation, orphan cleanup, checksum verification, Garage-outage policy) — `S02-20`. Until it lands, the garage-local-stack decision stays recorded in [`ADR-013`](../adr/ADR-013-s3-object-storage-garage.md).
- The `storage` module implementation — the `ObjectStoragePort` contract is defined (`S02-19`, [`object-storage.md`](./object-storage.md)); the Garage adapter and its code land with `S07-06B`/`S07-06H`. The ownership boundary is in place (`S02-18`) — the module and its dependency edges (`knowledgebase → storage`, `workflowexecution → storage`) exist in the module map, with no code under `src/main/java/com/example/agentflow/storage/` today.

No other index topic is planned beyond the items above.
