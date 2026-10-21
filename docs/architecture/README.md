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
| **Engineering conventions** | `UUID`, UTC `timestamptz`, injectable `Clock`, identifier and naming conventions | [`engineering-conventions.md`](./engineering-conventions.md) |
| **Database migrations** | Migration rules and the single process: forward-only, expand/contract, forward-fix, adding a migration | [`database-migrations.md`](./database-migrations.md) |
| **Database conventions** | Tables, columns, `timestamptz`, unified audit timestamps, constraint/index naming and optimistic versioning | [`database-conventions.md`](./database-conventions.md) |
| **Dependency management** | Version catalog, BOM policy, dependency locking, `check` gate | [`dependency-management.md`](./dependency-management.md) |
| **Dependency insight** | Resolved dependency graph (S01-08, reference artifact) | [`dependency-insight.txt`](./dependency-insight.txt) |
| **Domain glossary** | Canonical names, one concept — one name, concept → owner (§13) | [`../domain-glossary.md`](../domain-glossary.md) |
| **ADR register** | Architecture decision records, statuses, lifecycle | [`../adr/README.md`](../adr/README.md) |
| **Threat model** | Assets, attacker goals, trust boundaries, mitigations (tenant isolation, prompt injection, SSRF, log leaks) | [`../threat-model/README.md`](../threat-model/README.md) |

---

## Architecture diagrams

The two Mermaid diagrams are the navigational anchors of this index. Both live in
Markdown inside their own documents (so they live in the diff) and are kept in
sync with the code — a stale diagram is worse than none.

- **C4 Level 1 — system context** (`system-context.md`): the Client, Keycloak, the
  model provider, object storage and allowlisted external tools around the trusted
  AgentFlow boundary.
- **C4 Level 2 — module map** (`module-map.md`): the twelve bounded contexts
  behind that boundary, their owners, and the allowed `module -> module` edges
  (the execution `StepHandler` SPI is the only sanctioned feedback path).

---

## Deferred / open decisions

The plan fixes the modules and the stack, but a few decisions are still open.
They are recorded here so a reader sees what is decided and what is not. A
decision is promoted out of this list with an ADR once the trade-off is resolved.

| Decision | Status | Where it is resolved / who |
|---|---|---|
| Base package `com.example.agentflow` (sub-package layout inside a module) | Base package **fixed** by `S02-07`; the intra-module sub-package layout (`domain` / `application` / `persistence` / `worker` / by-feature) is still open | `apps/agentflow-app/AGENTS.md`, root `AGENTS.md` (Open decision 1) |
| Execution SPI — how `workflowexecution` calls `toolexecution` / `ai` / `knowledgebase` without a compile-time cycle | **Resolved.** `workflowexecutor.executor` owns the `StepHandler` port; the three implementers depend on the port owner, so the orchestrator never depends on them back | `module-map.md` ("The execution SPI is the only feedback path") — root `AGENTS.md` (Open decision 3) |
| Ownership of data/logic not named in plan §4.2 (approvals, idempotency keys, DLQ, tool registry, redaction, global Problem Details) | Data ownership **resolved** by `module-map.md` (one owner per table); the remaining specifics are still open (`approvals`/idempotency → `workflowexecution`, DLQ → `messaging.dlq`, `tool_definitions` → `toolexecution`, secret redaction → `common.redaction`, global Problem Details handler → `common.error`) | `module-map.md`, root `AGENTS.md` (Open decision 2) |
| Permission catalogue | The plan's `S04-04` list is used; the example scopes in plan §11.1 (`knowledge:read`, `tenant:admin`) are illustrative, not final | `docs/domain-glossary.md`, plan §11.1 (Open decision 4) |
| Persistence approach and migration tool | **Not chosen.** One approach (Spring Data JDBC, JPA or jOOQ) and one migration tool (Flyway by default) for the whole monolith — no JPA + jOOQ side by side, no mixing tools | plan `S03-08` / `S03-01`, root `AGENTS.md` (Open decision 5) |

---

## Current architectural constraints

These are the invariants that hold today. They are enforced by architecture tests
where possible (`S02-08` rejects any edge outside the module-map table;
`S02-09` rejects any cycle in the compile graph), not by code review alone.

- **Modular monolith first** — domain modules live in one app and one image;
  boundaries are checked automatically (`ADR-001`).
- **PostgreSQL is the source of truth** for workflow state; Kafka delivers, Redis
  caches, and losing Redis must not lose a run (`ADR-002`).
- **At-least-once + idempotency** — never promise exactly-once between PostgreSQL
  and Kafka; state and the outbox record write in one transaction (`ADR-003`).
- **Transactional outbox** between PostgreSQL and Kafka, polling publisher,
  idempotent publish by `event_id` (`ADR-004`).
- **Tenant isolation** — the tenant comes only from the verified security context
  (HTTP or a stored event re-checked against PostgreSQL); another tenant's resource
  is indistinguishable from a missing one (`404`/`403`) (`ADR-007`, plan §11).
- **AI is an untrusted planner** — the model proposes a typed plan that a
  server-side validation gate checks before execution; it never changes
  permissions, policies or allowlists (`ADR-006`).
- **Errors are classified** by the taxonomy; retry applies only to transient
  classes (plan §12, invariant 12).
- **Build integrity** — one Java 25 toolchain, one formatter (Spotless/Google Java
  Format), version-catalog dependency management and dependency locking; a
  formatting or boundary violation fails `./gradlew check`.

---

## Verification against the build

The module names and external systems in these documents are checked against the
build and repository, not assumed:

- **Modules.** The twelve modules in the module map (`identity`, `agentcatalog`,
  `workflowdefinition`, `workflowexecution`, `toolexecution`, `knowledgebase`,
  `ingestion`, `ai`, `usage`, `audit`, `messaging`, `observability`) plus the
  cross-cutting `common` package are present under
  `apps/agentflow-app/src/main/java/com/example/agentflow/<module>/`, matching the
  base package `com.example.agentflow` (main class
  `com.example.agentflow.AgentflowApplication`, `apps/agentflow-app/build.gradle.kts`).
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

- `durable-execution.md` — run/step state machine, lease + fencing, recovery (`S07-17`).
- `kafka-topics.md` — topics, partition keys (`S06`).
- `events/README.md` — event catalogue (`S06`).
- (none — see the index tables above for the current `⚠ planned` list.)
