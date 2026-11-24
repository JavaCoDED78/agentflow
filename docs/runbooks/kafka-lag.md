# Runbook — AgentFlow Kafka consumer lag

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow Kafka consumer lag` (`agentflow-kafka-lag`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-kafka-lag`), the messaging family in [`docs/observability/metrics.md`](../observability/metrics.md), the outbox/inbox contract in [`docs/architecture/durable-execution.md`](../architecture/durable-execution.md), and [`docs/runbooks/ingestion-failures.md`](ingestion-failures.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests a manual database edit or a raw replay.

Fires when `agentflow_messaging_consumer_lag_count > 1000` — the ingester is more
than 1000 records behind across assigned partitions — sustained for **10 minutes**
(`for: 10m`).

## Symptoms and impact

- Ingestion is backing up — documents take longer to index.
- Consumers process later than the broker produces.
- The **AgentFlow / Workflow & Kafka** dashboard consumer-lag panel and the
  ingestion dashboard panels show the backlog.

## Expected local-dev state

The lag gauge is emitted only by the ingester (`DocumentIndexingWorker`). A local API boot emits no messaging series — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. The backlog: `curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_consumer_lag_count'`.
2. Failures vs. lag: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28outcome%29+%28rate%28agentflow_messaging_consumer_processing_failures_total%5B5m%5D%29%29'` — `dead_letter` dominating means processing failures drive the lag, not a broker shortfall.
3. Broker state: is the `messaging` profile (Kafka) healthy — `docker ps` / the ingestion-worker logs in Grafana/Loki; check whether the ingester keeps polling (worker healthyness + the lag gauge moving).

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Ingester down/wedged | Restart the ingestion-worker; the gauge reads the broker position after every poll pass — it will drain as the worker catches up. |
| Broker down | Outbox/inbox are durable and idempotent; let the broker recover and the ingester will catch up — never replay or edit records by hand. |
| Processing errors | Look at the `dead_letter`/`retry` mix and the ingestion run logs; fix the cause (extraction, provider, dimension mismatch) — see `ingestion-failures.md`. |

## Redrive — and idempotency

Re-processing is idempotent by design: the inbox dedups every event by id in the
same transaction as the business change (no double business effect, ever), so
re-delivery after a crash is safe. The controlled re-delivery path is the DLQ
redrive (`docs/runbooks/dlq-redrive.md`) — a fresh id, a `409` guard against an
already-processed inbox, a recorded `dlq_redrive_attempts` row — never a raw
replay. Kafka partition-level re-assignment by hand is **not** a supported action
in this runbook.

## Recovery criterion

`agentflow_messaging_consumer_lag_count` falls below 1000 for the `for: 10m`
guard, and the lag gauge stays at its normal low level as the ingester catches up
(the live state is visible via `GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when the lag does not drain after a worker
restart, or when `dead_letter` dominates — do not replay by hand; fix the cause.
