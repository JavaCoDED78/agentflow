# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

<!--
  This file is a slim, navigable index: one line per task with a link to the detailed
  record. Verbose per-task detail (What changed / Why / Verification / Tests / Docs)
  was moved out of this file:
    - Recent work (Sprint 6 messaging, Sprint 7 durable execution) -> docs/devlog/
    - Archived releases (≤ 0.9.3, tasks S01-S05) -> docs/changelog/
  See docs/devlog/README.md and docs/changelog/README.md.
-->

## Unreleased

### Added
- **The registered-tool contract (S08-01): `ToolDescriptor` plus its value-objects** — the shape of a *registered tool* is now one typed, immutable `ToolDescriptor` (`toolexecution.domain`): the value that closes the loop between the tool allowlist and the executor. A tool is callable only when it has a risk class the caller may use, the caller holds every required scope, and the call passes the machine-readable input/output JSON Schema — none inferred from the human-readable `description()` (an untrusted planner, ADR-006; the model is never an authorization authority, an empty scope set means "no side effect permitted"). Built from three value objects — `ToolRiskClass` (`READ_ONLY`/`WRITE`/`EXTERNAL_SIDE_EFFECT`, moved to `agentcatalog.domain` where the catalogue owns the allowlist and its risk taxonomy via the module-map edge 3 dependency), `JsonSchema` (an immutable value holding the JSON Schema draft 2020-12 root as a `Map`), and `RetryPolicy` (the tool-layer retry value object with reproducible bounded backoff) — with canonical-constructor validation refusing an invalid descriptor, 32 DB-free unit tests (`ToolRiskClassTest`, `RetryPolicyTest`, `JsonSchemaTest`, `ToolDescriptorTest`); `:apps:agentflow-app:check` green — [detailed](docs/devlog/sprint-08-tool-registry.md)
- **Workflow run recovery runbook (S07-17): an operator-facing runbook for a stuck, crashed or failed run — what to look for (the `agentflow.workflow.recoveries` counter, worker/heartbeat/recovery log lines, run lease columns), how to confirm what happened (tenant-scoped PostgreSQL diagnostics against `workflow_runs`/`workflow_step_runs`/`workflow_step_attempt_history`/`workflow_events`), and what to do (cancel via `POST /api/v1/runs/{runId}/cancel`, retry a transient-failed run, or as a last resort force-reclaim a stale lease or force a run terminal) — plus the automatic path (worker drain stops the heartbeat, the lease expires, the S07-09 recovery scheduler re-claims within ≤60 s) and the two cases that need an operator: a run parked in `WAITING_FOR_APPROVAL`/`RETRY_WAIT`, and a run `FAILED` after the retry policy exhausted** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Worker kill / restart fault-injection test (S07-15): an actively-owned run whose worker dies mid-step survives — once its lease expires a second worker re-claims it (ownership transfer fenced by `lease_epoch`) and the run resumes within 60 s, verified against real PostgreSQL via Testcontainers** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Ambiguous external-outcome policy (S07-16): an external call whose side effect *may* have completed but whose response was lost (a `provider_timeout`, or a worker crashing mid-attempt) is marked, never treated as exactly-once (`ADR-003`, invariant 6); the attempt is visible in `workflow_step_attempt_history` with its safe category, and its retry is defined per the tool's idempotency — idempotent calls may be re-run, non-idempotent ones resolved (not blindly retried)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Persistent per-attempt step execution history (attempt number, start/finish, worker instance, safe error category + duration) with tenant-scoped reading — a failed retry is investigable without viewing the secret payload (S07-14)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Integration suite for the per-attempt step execution history repository (S07-14): exercises the real repository against PostgreSQL via Testcontainers (monotonic attempt numbering, COALESCE fencing, tenant-scoped read, `setNull` worker bind, `TIMESTAMPTZ` round-trip) rather than a mocked `JdbcTemplate`** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Graceful shutdown of the workflow worker (S07-13)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Cooperative cancellation of a run (S07-12)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Classify step failures into a retry decision (`ErrorClass`; only transient faults retry, others terminate) (S07-11)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Retry policy value object with reproducible, bounded backoff (S07-10)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Stale-run recovery loop that re-claims runs whose lease expired (S07-09)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **A first workflow worker: a durable event loop that runs a claimed run to completion (S07-08)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Persist a redacted, reusable per-step checkpoint for recovery (S07-07)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Persist the attempt lifecycle so the crash window is diagnosable (S07-06)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Run-level write fenced by `lease_epoch` (S07-05)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Run-level lease heartbeat (`S07-04`)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Atomic workflow-run claim (`S07-03`)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Workflow-run lease-recovery index (`S07-02`)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Workflow-run lease, heartbeat and recovery model (`S07-01`)** — [detailed](docs/devlog/sprint-07-durable-execution.md)
- **Event catalog and delivery guarantees (`S06-16`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Publish-delay / poll-load benchmark for the transactional-outbox publisher (`S06-15`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Reliability scenarios for the outbound/inbound messaging path (S06-14)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Event envelope contract tests (`S06-13`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Correlation metadata propagation (S06-12)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Consumer-side inbox error policy + DLQ (S06-11)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Consumer-side inbox dedup helper (S06-10)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Kafka producer reliability tuning for the outbound outbox adapter (S06-09)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Mark the outbox `published` only on broker ack, re-publish on an ambiguous outcome (S06-08)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Polling outbox publisher (`OutboxPublisherService`, `S06-07`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Idempotent create-run under `Idempotency-Key` (`S06-06`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Transactional outbox path for creating a run (`RunService.createRun`, `S06-05`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Consumer-side inbox dedup table (`inbox_messages`, `S06-04`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Transactional outbox table (`outbox_events`, `S06-03`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Kafka topic catalogue (`S06-02`)** — [detailed](docs/devlog/sprint-06-messaging.md)
- **Event envelope v1 contract (`S06-01`)** — [detailed](docs/devlog/sprint-06-messaging.md)

### Changed
- **The workflow worker now heartbeats the run-level lease during a long-running step (so the lease cannot expire mid-run and trigger a spurious re-claim); the blocking step runs outside the poll transaction, committing each durable state change on its own (`S07-04`)** — [detailed](docs/devlog/sprint-07-durable-execution.md)

## Archived releases (≤ 0.9.3)

Detailed per-task records for the releases up to and including 0.9.3 (tasks S01-S05)
are archived by category in [`docs/changelog/`](docs/changelog/README.md).

### Added
- **A read endpoint for a run's status (`GET /api/v1/runs/{runId}`)** — [archived](docs/changelog/added.md)
- **Local-skeleton tenant default recorded as a documented tenant-isolation bypass (`ADR-012`)** — [archived](docs/changelog/added.md)
- **Workflow DSL v1 documentation (`S05-17`)** — [archived](docs/changelog/added.md)
- **Run transition concurrency integration suite (`S05-16`)** — [archived](docs/changelog/added.md)
- **Transition-table unit tests for the run/step state machine (`S05-15`)** — [archived](docs/changelog/added.md)
- **Activate a workflow version — immutable after activation (`S05-14`)** — [archived](docs/changelog/added.md)
- **Read-only workflow-graph validation endpoint (`S05-13`)** — [archived](docs/changelog/added.md)

### Changed
- **OpenAPI description reachable on the secured chain** — [archived](docs/changelog/changed.md)
- **Immutable workflow-definition versions (`S05-12`)** — [archived](docs/changelog/changed.md)
- **Single run/step transition service (`S05-11`)** — [archived](docs/changelog/changed.md)
- **Workflow run/step state machine (`S05-10`)** — [archived](docs/changelog/changed.md)
- **Append-only workflow event history (`S05-09`)** — [archived](docs/changelog/changed.md)
- **Execution state tables (`S05-08`)** — [archived](docs/changelog/changed.md)
- **Closed workflow node-type registry (`S05-07`)** — [archived](docs/changelog/changed.md)
- **Workflow graph DAG validation / acyclicity (`S05-06`)** — [archived](docs/changelog/changed.md)
- **Workflow graph edge validation (`S05-05`)** — [archived](docs/changelog/changed.md)
- **Workflow graph parser (`S05-04`)** — [archived](docs/changelog/changed.md)
- **Workflow graph JSON Schema (`S05-03`)** — [archived](docs/changelog/changed.md)
- **Step state machine (`S05-02`)** — [archived](docs/changelog/changed.md)
- **Run state machine (`S05-01`)** — [archived](docs/changelog/changed.md)
- **Authentication/authorization guide (S04-15)** — [archived](docs/changelog/changed.md)
- **Local authentication runbook (S04-14)** — [archived](docs/changelog/changed.md)
- **Cross-tenant isolation at the secured HTTP boundary (S04-13)** — [archived](docs/changelog/changed.md)
- **Audit on workflow definition mutations (S04-12)** — [archived](docs/changelog/changed.md)
- **Restricted actuator endpoints (S04-11)** — [archived](docs/changelog/changed.md)
- **403/404 masking policy (S04-09)** — [archived](docs/changelog/changed.md)
- **Least-privilege endpoint access (S04-08)** — [archived](docs/changelog/changed.md)
- **Tenant-scoped access (S04-07)** — [archived](docs/changelog/changed.md)
- **TenantContext abstraction (S04-06)** — [archived](docs/changelog/changed.md)
- **Trusted tenant claim (S04-05)** — [archived](docs/changelog/changed.md)
- **Permission catalogue (S04-04)** — [archived](docs/changelog/changed.md)
- **Spring Security Resource Server (S04-03)** — [archived](docs/changelog/changed.md)
- **Split user and machine-to-machine clients (S04-02)** — [archived](docs/changelog/changed.md)
- **Keycloak realm: client scope and OIDC mappers (S04-01)** — [archived](docs/changelog/changed.md)
- **API and persistence conventions — developer guide (`S03-17`)** — [archived](docs/changelog/changed.md)
- **OpenAPI contract gate (S03-16)** — [archived](docs/changelog/changed.md)
- **PostgreSQL migration integration suite (S03-15)** — [archived](docs/changelog/changed.md)
- **List / get workflow definition endpoints (S03-14)** — [archived](docs/changelog/changed.md)
- **Create workflow definition endpoint (S03-13)** — [archived](docs/changelog/changed.md)
- **OpenAPI contract (S03-12)** — [archived](docs/changelog/changed.md)
- **Global error handler (`S03-11`)** — [archived](docs/changelog/changed.md)
- **Optimistic locking on workflow definitions (`S03-10`)** — [archived](docs/changelog/changed.md)
- **JSONB graph mapping (`S03-09`)** — [archived](docs/changelog/changed.md)
- **Database conventions (`S03-06`)** — [archived](docs/changelog/changed.md)
- **Workflow definition versions table (`S03-05`)** — [archived](docs/changelog/changed.md)
- **Workflow definitions table (`S03-04`)** — [archived](docs/changelog/changed.md)
- **Tenants table (`S03-03`)** — [archived](docs/changelog/changed.md)
- **Database migration rules (`S03-02`)** — [archived](docs/changelog/changed.md)
- **Architecture index (`S02-15`)** — [archived](docs/changelog/changed.md)
- **ADR register and decision lifecycle (`S02-16`)** — [archived](docs/changelog/changed.md)
- **PR architecture review checklist (`S02-14`)** — [archived](docs/changelog/changed.md)
- **ADR template (`S02-12`)** — [archived](docs/changelog/changed.md)
- **Error taxonomy (`S02-10`)** — [archived](docs/changelog/changed.md)
- **Time and identifier conventions (`S02-11`)** — [archived](docs/changelog/changed.md)
- **Module boundary architecture gate (`S02-08`)** — [archived](docs/changelog/changed.md)
- **No-cycles architecture gate (`S02-09`)** — [archived](docs/changelog/changed.md)
- **Architecture gate wired into `check` (`S02-13`)** — [archived](docs/changelog/changed.md)
- **Package-by-feature module skeleton (`S02-07`)** — [archived](docs/changelog/changed.md)
- **Consistency model ADRs (`S02-06`)** — [archived](docs/changelog/changed.md)
- **Modular monolith first ADR (`S02-05`)** — [archived](docs/changelog/changed.md)
- **Module public API map (`S02-04`)** — [archived](docs/changelog/changed.md)
- **Contribution and changelog rules (`S01-22`)** — [archived](docs/changelog/changed.md)
- **Documentation index and conventions (`S01-21`)** — [archived](docs/changelog/changed.md)
- **Java 25 toolchain (`S01-05`)** — [archived](docs/changelog/changed.md)
- **Version catalog (`S01-06`)** — [archived](docs/changelog/changed.md)
- **Gradle reproducibility (S01-09)** — [archived](docs/changelog/changed.md)
- **Spring AI BOM compatibility (`S01-08`)** — [archived](docs/changelog/changed.md)
- **Java formatter / static check (`S01-10`)** — [archived](docs/changelog/changed.md)
- **Repository secret hygiene (`S01-11`)** — [archived](docs/changelog/changed.md)
- **PostgreSQL Compose service (`S01-12`)** — [archived](docs/changelog/changed.md)
- **Redis Compose service (`S01-13`)** — [archived](docs/changelog/changed.md)
- **Garage object storage service (`S01-15`)** — [archived](docs/changelog/changed.md)
- **Compose profiles (S01-16)** — [archived](docs/changelog/changed.md)
- **Safe health endpoints (`S01-18`)** — [archived](docs/changelog/changed.md)
- **Standardized JSON logging (`S01-19`)** — [archived](docs/changelog/changed.md)
- **Quickstart and smoke test (`S01-20`)** — [archived](docs/changelog/changed.md)
- **System context diagram (`S02-02`)** — [archived](docs/changelog/changed.md)
- **Bounded-context module map (`S02-03`)** — [archived](docs/changelog/changed.md)
- **Tenant-query indexes (`S03-07`)** — [archived](docs/changelog/changed.md)
- **Single persistence approach — Spring Data JDBC (`S03-08`)** — [archived](docs/changelog/changed.md)

### Fixed
- **`bootRun` resolves `runtimeClasspath` (dependency-lock gap closed, S04-14 follow-up)** — [archived](docs/changelog/fixed.md)
- **`jwtDecoder` no longer uses the deprecated Nimbus `RemoteJWKSet` (S04-03)** — [archived](docs/changelog/fixed.md)
- **Typed config binds under the default profile (`S01-17`)** — [archived](docs/changelog/fixed.md)
- **`apps/agentflow-app` test task runs (S01-07)** — [archived](docs/changelog/fixed.md)

### Security
- **Local auth bypass removed (S04-10)** — [archived](docs/changelog/security.md)
