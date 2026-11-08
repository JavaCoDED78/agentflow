# AGENTS.md — AgentFlow

AgentFlow is a platform for building and reliably running AI workflows. Its main deliverable is a small in-house durable workflow engine: it survives worker restarts, tolerates duplicate message delivery, enforces tenant isolation, caps the cost of AI calls, and explains execution through traces, metrics and an audit trail. It is **not** a chat wrapper around an LLM.

The source of truth for architecture and backlog is `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (tasks `S01-01 … S16-19`, ADR-001…ADR-014). This file is a digest of rules for the agent. Nested `AGENTS.md` files (app → module → package) refine it: on conflict the file closest to the code being changed wins, but the "Invariants" section is never weakened anywhere.

## Notation

⚠ marks an **unconfirmed** item — a `planned` entry, an assumption, or a marker not yet backed by code in the repo. One meaning across every `AGENTS.md`: anything carrying ⚠ is not verified; resolve it (code / ADR / README) or remove it before merging. `S##-##` is a plan task id from `AGENTFLOW_ARCHITECTURE_AND_PLAN.md`.

## Required skill: `agentflow-sprint-execution`

When implementing any task from the plan (`S##-##` in `AGENTFLOW_ARCHITECTURE_AND_PLAN.md`), Hermes **must** apply the skill `agentflow-sprint-execution` (`C:/Users/andro/AppData/Local/hermes/skills/software-development/agentflow-sprint-execution/SKILL.md`): read its `SKILL.md` before starting work and follow it throughout the implementation of the task. This applies to every plan task, including P1/P2 and small ones. If the skill is unavailable or its body is lost to context compression (`[SKILL_PRUNED]`), stop and report it — do not implement the task without the skill. The fallback that reads the plan files directly is `agentflow-skill-pruned-fallback` (`C:/Users/andro/AppData/Local/hermes/skills/software-development/agentflow-skill-pruned-fallback/SKILL.md`).

## Stack (fixed by the plan)

- Java 25 LTS, Spring Boot 4.1.x, Spring AI 2.0.x (via the official BOM), Spring Modulith 2.1.x (fallback — ArchUnit).
- Gradle Wrapper + Kotlin DSL + `gradle/libs.versions.toml`, dependency locking. Versions change only through a PR.
- PostgreSQL 18 (+ pgvector, FTS), Kafka 4.x (KRaft), Redis 8 (cache/rate-limit only), Garage (S3), Keycloak 26.x, Tika + PDFBox.
- Micrometer + OpenTelemetry (OTLP) → Collector → Prometheus / Loki / Tempo → Grafana.
- JUnit 6 (BOM-managed by Spring Boot 4.1.1; the Sprint 1 plan's "JUnit 5" is superseded — see `CHANGELOG.md`), AssertJ, Mockito, Testcontainers 2.x, k6.
- Spring MVC + **one** persistence approach (S03-08) + **one** migration tool (Flyway — `ADR-009`). Do not introduce WebFlux, R2DBC, JPA and jOOQ side by side; virtual threads only after measurements.
- Do not override Spring Framework / Security / Kafka client / Jackson versions on top of the BOM without an ADR. Stable releases only. No `latest` in Compose or Kubernetes manifests.

## Repository layout

```text
apps/agentflow-app/        one Spring Boot artifact, several runtime roles (api / workflow-worker / ingestion-worker)
apps/agentflow-mcp-lab/    PLANNED (P1) — isolated MCP demo server; not created yet (apps/ currently holds agentflow-app only). Remove or confirm when implemented.
infra/                     compose, docker, helm, argocd, observability/* (everything as code)
contracts/                 openapi/, events/json-schema/, asyncapi/
docs/                      adr/, architecture/, runbooks/, devlog/, threat-model/, load-tests/, security/ …
scripts/                   smoke-local.sh and other scripts
```

Do not multiply Gradle subprojects: modules are packages inside `agentflow-app`. A separate Gradle module only if it has its own lifecycle/publication.

## Commands

Command names come from `README.md` and `build.gradle.kts` — verify against them before using; any command documented in the README must actually be exercised by CI or a smoke script. The app is one Gradle subproject (`apps:agentflow-app`); run the tasks below from the repo root.

```bash
./gradlew check                      # compile + unit + architecture (Modulith/ArchUnit) + formatting (spotless) + OpenAPI gate — mandatory before a PR
./gradlew integrationTest            # Testcontainers (real PostgreSQL/pgvector, Kafka, Redis, Keycloak)
./gradlew :apps:agentflow-app:bootRun --args='--spring.profiles.active=local'   # run the API locally
./gradlew :apps:agentflow-app:test --tests '*workflowexecution*'   # one module's unit tests
./gradlew flywayMigrate              # apply classpath:migrations to the configured DB
docker compose --file infra/compose/docker-compose.yml --profile core up -d   # base stack (PostgreSQL+pgvector, Redis, Garage)
```

Compose profiles: `core`, `messaging`, `identity`, `ai-local`, `observability`, `full`. Run `docker compose --file infra/compose/docker-compose.yml --profile <profile> up -d`; tear down with `down` (add `-v` to drop volumes). `messaging`/`full` need a one-time `KAFKA_CLUSTER_ID`, provided by the local `infra/compose/.env`.

## Application modules (`com.example.agentflow.<module>`)

| Module | Responsibility |
|---|---|
| `identity` | tenant context, claims → permissions mapping |
| `agentcatalog` | agent profiles, model policy, tool allowlist |
| `workflowdefinition` | versioned DAG graph, validation, activation |
| `workflowexecution` | run/step state machine, lease, checkpoints, retry, cancel, executor |
| `toolexecution` | typed tool registry, checks, timeout, schema-validated results |
| `knowledgebase` | documents, access policy, chunk search, citations |
| `ingestion` | extract → chunk → embed → index |
| `ai` | provider adapter, prompts, Planner, structured output, evaluation |
| `usage` | tokens/cost, budgets, quotas |
| `audit` | append-only audit |
| `messaging` | outbox, inbox, event envelope, Kafka config, DLQ |
| `observability` | meters, trace attributes, correlation, JSON logging |
| `common` | minimal shared kernel (IDs, Clock, error taxonomy, redaction) — **not** business logic |

## Dependency rules

- A module sees only another module's **root package** (public API: application services, public DTOs/IDs, event types). Do not import another module's repositories, entities or internal packages.
- Domain modules do not import web controllers, Kafka listeners or infrastructure classes of other modules.
- No cycles. Feedback goes through an application port or an event. Allowed direction follows the §4.2 table and `docs/architecture/module-map.md`; a new dependency edge requires an ADR.
- Enforced by Spring Modulith / ArchUnit inside `./gradlew check`. Do not "fix" a failing test with exclusions — fix the dependency.
- Every table and business rule has exactly one owning module; other modules reach its data only through its public API.

## Package layout inside a module

Established in code — the module's root package is its public API; sub-packages are internal:

- `domain` — pure model and invariants, no Spring/JDBC/Kafka. `application` — use cases, transactions, outbound ports. `web` — controllers and API DTOs. `persistence` — repositories, mappers, SQL. `worker` — Kafka listeners. Anything else — by feature.
- Controllers and listeners call only application services; they never change status directly. API DTO ≠ persistence object.

## Invariants (do not break, do not weaken)

1. **Tenant isolation.** Tenant comes only from the verified SecurityContext (HTTP) or from the stored run/event re-checked against PostgreSQL (worker). Never from body, query or Kafka headers. A resource is read by the pair `(tenantId, resourceId)`; a UUID is not authorization. The tenant filter is part of the query itself (including vector search before top-k), not a post-filter. Another tenant's resource is indistinguishable from a missing one (single 403/404 policy).
2. **PostgreSQL is the source of truth.** Kafka delivers, Redis caches; losing Redis must not lose a run.
3. **At-least-once + idempotency.** Never promise exactly-once (code, docs, comments). State and outbox write share one transaction; Kafka ack only after durable commit; inbox dedup in the same transaction as the business change.
4. **State changes only through the state machine** and a single transition service: conditional update + optimistic version + `workflow_events` record in the same transaction.
5. **Lease/fencing.** Every worker write checks `lease_epoch`. JVM locks are not protection.
6. **An external call is not exactly-once.** The attempt is persisted before the call; an ambiguous outcome is marked and visible in usage/history.
7. **AI is an untrusted planner.** Model output → typed plan → server-side validation gate → executor. Documents and RAG fragments are untrusted content. Permissions and allowlists are decided by the backend. eval, shell, SQL shell, filesystem shell and unrestricted HTTP fetch are forbidden. Unknown tool or risk class → deny.
8. **Secrets** — not in Git, build args, logs, metrics, traces, Kafka, prompts or Actuator. `.env` stays out of the repository.
9. **Metrics:** no `tenantId`, `userId`, `runId`, `documentId` or prompt in labels. Identifiers live only in traces/logs.
10. **Data:** forward-only migrations (never edit an applied one); `timestamptz`; UUID; money is `NUMERIC`/Decimal, not float; time and randomness via injectable `Clock`/source; large payloads go to Garage, the DB keeps ref + checksum.
11. **API:** `/api/v1` prefix, OpenAPI in CI, RFC 9457 errors, pagination with a max size, `Idempotency-Key` for create-run / upload-finalize / approval, `202` for starting a workflow, tenant never from body.
12. **Errors** are classified by the error taxonomy; retry only for transient ones. Do not add retries/timeouts blindly.
13. **Immutability:** an activated workflow version, an active agent profile version and a published event contract never change — only a new version.

## Tests

- No H2 instead of PostgreSQL; integration tests use Testcontainers on the same images as Compose/CI. CI must not depend on a real model, keys or internet (fake provider).
- Fixed `Clock` in time-dependent tests. Target ≥80% line coverage in domain modules, but direct coverage of state transitions, tenant checks and failure paths matters more.
- Mandatory negative scenarios: cross-tenant access; two workers on one run; duplicate Kafka event; worker death after a model call; prompt injection in a document; a tool reaching a private/metadata IP; empty retrieval without invented citations; Redis down; Kafka down during create-run; cancel mid-step.
- Any reliability/performance claim is backed by a test or a measurement.

## How to work

- A task is `S##-##` with priority P0/P1/P2. Do not start P2 while P0 is unstable; never trade away tenant isolation, recovery or security tests. Do not widen scope (§1.3: no billing, arbitrary code, multi-region, UI panel).
- A PR solves one problem and lists verification commands; the application stays runnable.
- Definition of Done: compiles on Java 25 + formatter; unit + negative test; integration test for DB/Kafka/Redis/security; contracts (OpenAPI / JSON Schema / AsyncAPI) updated; errors classified; metrics/logs free of secrets and high cardinality; no new tenant/security bypass; ADR when a trade-off changes; relevant docs + `README.md` + `CHANGELOG.md` (`Unreleased`, or `N/A` with a reason); `./gradlew check` green; infra change verified from a clean environment.
- Documentation as Code: code, docs and contracts change in one PR. A new module/package/table/metric/permission/event/tool → update its owner (`docs/`, `contracts/`, this set of `AGENTS.md`).
- An ADR is needed for: a new dependency between modules, bypassing the BOM, changing the persistence/migration approach, abandoning an invariant, a new node type or risk class.

## Forbidden

Business logic in `common`; auth bypass and mock principals outside a test-only config; the fake AI provider in production/kubernetes profiles; arbitrary code in a workflow/tool; dynamic tool registration by the LLM or a user; `tenantId` from external input as an authorization source; metric labels carrying IDs; secrets in examples and logs; manual DB edits instead of a runbook; manual dashboard edits in the Grafana UI (provisioning from Git only); `latest` tags.

## Open decisions

Most items that were open when this file was drafted are now settled; those ⚠ markers are removed. The genuinely open items stay here and are closed with an ADR, code, or a README/module-map entry.

### Settled (⚠ removed)

- **Sub-package layout and base package** `com.example.agentflow` — established in code: `domain` (pure model), `application` (use cases, ports), `web` (controllers + DTOs), `persistence` (repositories, SQL), `worker` (Kafka listeners).
- **Persistence approach and migration tool** — Spring Data JDBC with explicit, tenant-filtered SQL (`ADR-009`); forward-only Flyway under `classpath:migrations`.
- **Cross-module calls without cycles** — the `StepHandler` SPI (`workflowexecution.domain.StepHandler`), each handler implemented in its owning module (e.g. `workflowexecution.application.DeterministicStepHandler`, plus the tool/ai/knowledgebase handlers).

### Still open (confirm via ADR / README / module-map)

- **Owners of data/logic not named in §4.2:** approvals and idempotency keys → `workflowexecution`; DLQ → `messaging`; `tool_definitions` → `toolexecution`; redaction → `common`; global Problem Details handler → `common.error`. Assignments follow the plan and `docs/architecture/module-map.md`.
- **Permission catalogue:** the list from S04-04 is used; the example scopes in §11.1 (`knowledge:read`, `tenant:admin`) are illustrative, not exhaustive.
