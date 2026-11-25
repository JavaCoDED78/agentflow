# Failure matrix — critical dependencies

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Task:** `S14-01`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 14, `S14-01`) ·
> `docs/architecture/durable-execution.md` · `docs/architecture/redis-usage.md` ·
> `docs/observability/metrics.md` · the runbooks in `docs/runbooks/`
> **Purpose:** the single operational record of how each critical dependency fails —
> the failure injection, the expected behaviour, the recovery target, the observable
> signal and the evidence (test / runbook / live check) behind it.

---

## How to read this matrix

- **Component** — the dependency the app degrades or blocks on.
- **Failure injection** — the simplest reproducible way to take it down, in the
  local stack.
- **Expected behaviour** — what the platform does while the dependency is down.
  "Expected" is only ever a documented contract, never an assumption: if a row's
  expected behaviour is not backed by a test or runbook, it is marked as a gap.
- **Recovery target** — the observable that proves the incident is over.
- **Metric / alert** — the machine-readable signal an operator sees. No metric
  label ever carries `tenantId` / `runId` / `documentId` (invariant 9); the
  high-cardinality identity lives in traces and logs.
- **Evidence** — the test, runbook or live check that confirms the claim. Only
  cited where it actually exists.

The sprint goal (`S14-01`) is **one outage scenario per critical dependency**. The
six critical dependencies in the platform are: **PostgreSQL**, **Kafka**, **Redis**,
**object storage**, the **AI provider** and the **worker role** (workflow-worker /
ingestion-worker).

Two honest gaps are recorded in [Known gaps](#known-gaps) rather than papered over:
the dedicated live PostgreSQL-outage integration test is `S14-07`, and the object
storage client is not wired yet (`ADR-014`) so its outage path is only verified up
to the port boundary.

## Overview

| Component | Injection | Fail-open? | Expected outcome | Metric / alert | Evidence |
|---|---|---|---|---|---|
| PostgreSQL | `docker stop agentflow-postgres` | No — blocks | Requests fail; run+outbox never committed partially | `agentflow-db-saturation`, `agentflow-api-error-rate` | `RunServiceAtomicIT`, `MigrationsTest` (live outage test = S14-07) |
| Kafka | `docker stop agentflow-kafka` / dead port | No — queues | Durable outbox waits; consumer replays; inbox dedups | `agentflow-outbox-age`, `agentflow-kafka-lag` | `ReliabilityScenarioIT`, `KafkaMessageDelivererIT`, `BrokerRestartPublisherCrashIT` |
| Redis | `docker stop agentflow-redis` | Yes — degraded | Limiter falls open to in-memory; health DOWN, readiness UP | `agentflow-redis-unavailable` | `RedisRateLimiterIT`, `RedisHealthIndicatorReadinessIsolationTest` |
| Object storage | `docker stop agentflow-garage` | No — blocks ingestion | Ingestion fails loudly, document requeued | `agentflow-ingestion-failures` | `IngestionIndexingIT` (port-boundary only — no Garage client yet) |
| Provider | fake / failing endpoint | No — bounded | Bounded retries, classified reasons, no runaway calls | `agentflow-provider-failures` | `ResilientChatModelTest`, `ResilientEmbeddingModelTest` |
| Worker role | kill mid-step / restart | Yes — automatic | Lease expires, recovery re-claims within ~35 s | `agentflow-expired-leases` | `WorkflowWorkerCrashRecoveryIT`, `WorkflowRecoverySchedulerIT` |

---

## PostgreSQL — the source of truth

| | |
|---|---|
| **Component** | PostgreSQL 18 (+ pgvector), the only durable store (`ADR-002`) |
| **Failure injection** | `docker stop agentflow-postgres` (local `core` profile) — or a mid-request connection loss / pool exhaustion. Dedicated live-outage scenario: `S14-07` |
| **Expected behaviour** | The app never runs on a stale state: the local profile requires a wired `DataSource` (a missing one is a loud boot failure); a configured-but-dead datasource makes DB-dependent requests fail (connection refused / acquisition timeout) and 5xx climb. The base profile boots with no database (`S01-02` skeleton), which is a skeleton property, not a runtime state. No run is created without its outbox row: `RunServiceAtomicIT` proves the run + the outbox event commit in one transaction, and a failing outbox append rolls the run back. No event is processed without its inbox dedup row |
| **Recovery target** | `docker start agentflow-postgres` → the pool self-heals, writes resume, runs created before the outage are durable and re-readable |
| **Metric / alert** | `agentflow-db-saturation` (`hikaricp_connections_active / hikaricp_connections_max` > 0.8 for 5m, critical) · `agentflow-api-error-rate` (5xx > 0.5 req/s for 5m, critical) · `agentflow-scrape-target-down` if the app itself is down |
| **Evidence** | `RunServiceAtomicIT` (create-run commits run+outbox atomically; outbox failure rolls the run back; idempotent keyed repeat; payload conflict) · `MigrationsTest` (schema applies, version pinned) · `WorkflowTransitionConcurrencyIT` (one winner on a run transition) · `docs/runbooks/db-saturation.md` |

**Not wired yet:** the dedicated PostgreSQL-outage integration test (connection loss mid-create-run / unknown commit, re-issue with the same idempotency key) is `S14-07`. The atomicity guarantees above are proven; only the mid-outage behaviour is unverified.

## Kafka — event transport

| | |
|---|---|
| **Component** | Kafka 4.x (KRaft), the event transport (`ADR-003`, at-least-once) |
| **Failure injection** | `docker stop agentflow-kafka` (local `messaging` profile) — or a producer pointed at a refused dead port |
| **Expected behaviour** | The outbox is durable and never lost: rows wait in PostgreSQL while the broker is down; the publisher classifies an unreachable broker as a **transient** failure (bounded retry — `S06-09` / `S06-07`) and does not hand-edit a row. On recovery the publisher drains; the consumer replays from the earliest retained offset (`auto.offset.reset=earliest`) and the inbox dedups every event by id in the same transaction as the business change — a re-delivered envelope produces **no double business effect**. Malformed/permanent failures go to the DLQ, held for the controlled, audited redrive path (`docs/runbooks/dlq-redrive.md`) |
| **Recovery target** | `agentflow-outbox-age` clears (oldest due row < 300 s for 5m) and `agentflow-kafka-lag` clears (lag < 1000 for 10m) — the outbox drains, the ingester catches up |
| **Metric / alert** | `agentflow-outbox-age` (warning, 5m) · `agentflow-kafka-lag` (warning, 10m) · `agentflow.messaging.outbox.publish.attempts.total{outcome}` (`delivered` / `transient_failure` / `ambiguous` / `permanent_failure`) · `agentflow.messaging.consumer.lag.count` · `agentflow.messaging.consumer.processing.failures.total{outcome}` (`retry` / `dead_letter`) |
| **Evidence** | `ReliabilityScenarioIT` (`run_survives_a_kafka_outage_and_is_processed_after_recovery`, `a_duplicate_envelope_is_consumed_only_once`, `a_crash_between_broker_ack_and_db_update_republishes_without_loss_or_repeat`) · `KafkaMessageDelivererIT` (`classifies_unreachable_broker_as_transient_failure`) · `OutboxPublisherConcurrencyIT` · `BrokerRestartPublisherCrashIT` (`broker_restart_at_the_moment_of_publication_delivers_the_event_after_recovery`, `publisher_crash_after_broker_ack_before_db_update_republishes_as_duplicate`) |

## Redis — helper cache / rate limiter

| | |
|---|---|
| **Component** | Redis 8 (helper only — `ADR-021`; never authoritative, never in readiness/liveness) |
| **Failure injection** | `docker stop agentflow-redis` — or a connection pointed at a refused port (the `local` profile's limiter path); slow responses (`connect-timeout-seconds` / `command-timeout-seconds`, 5 s each) |
| **Expected behaviour** | **Fail open, by design** (`ADR-021`): the `ratelimit` use delegates the decision to the injected in-memory limiter — the request is allowed, never a blanket 429; `agentflow.redis.rate_limit.fallback_open` increments once per failure. The `redis` health contributor returns `DOWN` on any connection failure or non-`PONG` outcome but sits in the non-critical `cache` group — `/actuator/health/cache` is 503 while `/actuator/health/readiness` stays 200, so the pod never leaves rotation. No durable state is lost: PostgreSQL is still the source of truth and the worker never connects to Redis |
| **Recovery target** | `/actuator/health/cache` → `UP` (200) and `fallback_open` stops rising; readiness never flipped |
| **Metric / alert** | `agentflow-redis-unavailable` (warning, 5m — `rate(agentflow_redis_command_errors_total[5m]) > 0`) · `agentflow.redis.command.errors.total` · `agentflow.redis.command.latency.seconds{operation,result}` · `agentflow.redis.rate_limit.decision.total{group,result}` · `agentflow.redis.rate_limit.fallback_open` |
| **Evidence** | `RedisRateLimiterIT` (`fails_open_to_the_in_memory_limiter_when_redis_is_unreachable`, plus the two-limiter single-global-limit case) · `RedisHealthIndicatorReadinessIsolationTest` (through `HealthEndpoint`, `redis` = `DOWN` while `readiness` = `UP`) · `RedisSmokeTest` (round-trip + TTL) · `RedisHealthIndicatorTest` · live local boot (health-group transitions, `docs/architecture/redis-usage.md` §7) |

## Object storage — Garage / S3 (payload bytes)

| | |
|---|---|
| **Component** | Garage (S3-compatible, `ADR-013`) — payload storage for documents; PostgreSQL keeps only the ref + SHA-256 |
| **Failure injection** | `docker stop agentflow-garage` — or the `agentflow.storage.*` endpoint pointed at a dead port |
| **Expected behaviour** | **Honestly: no Garage client is wired yet** (`ADR-014`, opt-in `DocumentPayloadStore`). The port is reached through `IngestionService`; when no store is wired the pass fails loudly and permanently at call time (`IllegalStateException`, never a silent "indexed"). When a store *is* wired, a transient fault propagates to the inbox policy → bounded immediate retry (3 attempts) → `RETRY` disposition → the claim rolls back and the document is requeued (the lifecycle's "release" is the rollback), never falsely marked indexed. A missing object is a permanent fault. Ingestion failures never lose a document — it simply stays unindexed and surfaces via the alert. No per-function outage table exists for this component yet |
| **Recovery target** | The store is healthy again → the controlled re-index path (re-submit through the normal ingestion flow) indexes the document. No manual state fix — a document stays `FAILED`/unindexed and is re-indexed by the normal flow |
| **Metric / alert** | `agentflow-ingestion-failures` (warning, 5m — `rate(agentflow_ingestion_index_failures_total[5m]) > 0`) · `agentflow.ingestion.index.failures.total` · the AI/RAG ingestion-failure panel |
| **Evidence** | `IngestionIndexingIT` (`a_transient_provider_fault_holds_the_event_and_requeues_the_document`, `a_cross_tenant_event_is_a_permanent_fault_and_never_touches_the_document`, `a_corrupt_payload_fails_the_document_with_the_extraction_code`, `an_empty_payload_completes_with_zero_chunks`) · `DocumentPayloadStore` port contract (`ADR-014`, module-api: "no Garage client yet, fails loudly at call time") |

**Known limitation, stated plainly:** with no Garage client wired, the outage path is verified only up to the port boundary — no live `docker stop agentflow-garage` behaviour is asserted. The client is the prerequisite for a live S3-outage scenario; it is tracked by the Sprint 10 ingestion work and not in this task's scope.

## AI provider — the model backend

| | |
|---|---|
| **Component** | The LLM / embedding provider behind `ai.application.*` ports (`ResilientChatModel` / `ResilientEmbeddingModel`, `ADR-014`) |
| **Failure injection** | Point the app at a failing/unreachable provider endpoint (the fake provider scenario — `FakeModelProvider` — or the real provider refusing): timeout, 429, 5xx, connection refused |
| **Expected behaviour** | **Bounded, classified, never runaway.** Every attempt runs under a bounded retry loop with an overall-timeout bound; a permanent fault fails immediately without retry; a transient fault is retried (never infinite); the concurrency cap throttles (`concurrency_limited`) rather than queuing into a storm. Each failure is classified by `ProviderExceptionClassifier` (bounded vocabulary: `rate_limit` / `server_error` / `timeout` / `connection` / `client_error` / `unknown` / `concurrency_limited`). Usage is recorded — tokens and latency only from the provider's own report, never invented — and the ambiguous outcome is marked honestly (usage reconciliation, `S12-06`). An AI step that fails fails the step; the run falls to the retry budget and ends `FAILED` — no silent success |
| **Recovery target** | Provider healthy → the bounded retry path clears the call, the step completes, usage (tokens / cost) is settled |
| **Metric / alert** | `agentflow-provider-failures` (warning, 5m — `rate(chat+embedding errors[5m]) > 0`) · `agentflow.ai.provider.chat.errors.total` / `agentflow.ai.provider.embedding.errors.total` (by `reason`) · the latency timers · chat/embedding/tool panels on the AI/RAG/cost dashboard; token/cost panels |
| **Evidence** | `ResilientChatModelTest` (clean first attempt, transient retried until recovery, bounded attempts never infinite, permanent not retried, total wait bound, hanging attempt bounded by overall timeout, concurrency cap, constructor validation) · `ResilientEmbeddingModelTest` (success pass-through, rate-limit retried, permanent fails immediately, single-attempt aborted by overall timeout) · `ProviderExceptionClassifierTest` · `ResilientProviderObservabilityTest` · `FakeModelProviderTest` |

## Worker role — workflow-worker / ingestion-worker

| | |
|---|---|
| **Component** | The `workflow-worker` role (and the `ingestion-worker` role) — the step loop, lease recovery, the document indexing loop |
| **Failure injection** | `kill -9` the worker mid-step (blocked step handler + destroyed heartbeat scheduler, as in the test harness) · deploy / restart · two replicas racing on one run |
| **Expected behaviour** | The worker **drains and stops heartbeating** on shutdown (a wedged worker is detected by the lease expiring — `lease_until < now()`); `lease_epoch` fencing means only the current owner can record a write. The `WorkflowRecoveryScheduler` re-claims expired-lease runs (`agentflow.workflow.recoveries` rises) — most runs fix themselves in ~35 s (30 s lease + 5 s recovery scan, default config). Two workers on one run record exactly one winner (transition + `workflow_events` in one transaction, optimistic version) · an expired lease is scanned and re-claimed, never hand-edited (no manual state fix) |
| **Recovery target** | The run is re-claimed and resumed (test SLA 60 s); `agentflow.workflow.recoveries` rises and the scan clears (`agentflow-expired-leases` clears — `rate(agentflow_workflowexecution_lease_expired_total[5m])` returns to 0 for 5m) |
| **Metric / alert** | `agentflow-expired-leases` (warning, 5m) · `agentflow_workflowexecution_lease_expired_total` (scan load) · `agentflow.workflow.recoveries` (re-claims) · runs-by-state / retries / lease-recovery panels on the Workflow&Kafka dashboard |
| **Evidence** | `WorkflowWorkerCrashRecoveryIT` (`a_running_run_survives_its_worker_crash_and_is_reclaimed_within_sla` — blocked worker killed mid-step, lease expires, second worker reclaims within 60 s) · `WorkflowRecoverySchedulerIT` (expired lease reclaimed + counter increments; terminal run not reclaimed; concurrency limit) · `WorkflowTransitionConcurrencyIT` (one winner among concurrent transitions) · `WorkflowWorkerLimitsIT` (worker limits) |

---

## Known gaps

| Gap | Why it is not closed | Owner / next task |
|---|---|---|
| PostgreSQL live-outage scenario | The dedicated test of connection loss mid-create-run / unknown commit with idempotency-key re-issue is not written | `S14-07` |
| Object-storage outage behaviour | No Garage client is wired yet (`ADR-014`, opt-in); the outage path is verified only up to the port boundary | the Sprint 10 ingestion work / `ADR-014` client |
| Cross-tenant outage regression | Tenant-boundary checks across runs/documents/chunks/approvals/usage/audit are not in this matrix's scope | `S14-03` |
| Redis outage full table | The per-function outage table (function → behaviour → evidence) is Sprint 14's own follow-up | `S14-08` (this matrix documents the current state only) |

The matrix is **not** the test suite — it is the honest map of what is proven today and what is still planned. Each `S14-*` task closes its row.

## Related

- [`docs/runbooks/`](../runbooks/README.md) — the operator runbooks behind each alert (`redis-outage.md`, `db-saturation.md`, `outbox-age.md`, `kafka-lag.md`, `provider-failures.md`, `expired-leases.md`, `ingestion-failures.md`, `workflow-recovery.md`, `dlq-redrive.md`)
- [`docs/architecture/durable-execution.md`](../architecture/durable-execution.md) — lease, fencing, recovery model
- [`docs/architecture/redis-usage.md`](../architecture/redis-usage.md) — the Redis failure-policy contract
- [`docs/observability/metrics.md`](../observability/metrics.md) — the machine-checked metric catalogue (labels and closed value sets)
- [`docs/observability/standards.md`](../observability/standards.md) — telemetry conventions
