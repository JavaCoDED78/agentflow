# Runbook: AgentFlow Kafka consumer lag

Alert: `AgentFlow Kafka consumer lag` (`agentflow-kafka-lag`, severity `warning`).

Fires when `agentflow_messaging_consumer_lag_count > 1000` (ingester backlog across assigned partitions) for more than 10 minutes.

## Symptoms and impact

- Ingestion is backing up — documents take longer to index; consumers process later than the broker produces.
- The **AgentFlow / Workflow & Kafka** dashboard consumer-lag panel and the ingestion dashboard panels show the backlog.

## Expected local-dev state

The lag gauge is emitted only by the ingester (`DocumentIndexingWorker`). A local API boot emits no messaging series — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

1. The backlog: `curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_consumer_lag_count'`.
2. Failures vs. lag: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28outcome%29+(rate%28agentflow_messaging_consumer_processing_failures_total%5B5m%5D%29)'` — `dead_letter` dominating means processing failures drive the lag, not a broker shortfall.
3. Broker state: is the `messaging` profile (Kafka) healthy — `docker ps` / the ingestion-worker logs in Grafana/Loki; check whether the ingester keeps polling (worker healthyness + the lag gauge moving).

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Ingester down/wedged | Restart the ingestion-worker; the gauge reads the broker position after every poll pass — it will drain as the worker catches up. |
| Broker down | Outbox/inbox are durable and idempotent; let the broker recover and the ingester will catch up — never replay or edit records by hand. |
| Processing errors | Look at the `dead_letter`/`retry` mix and the ingestion run logs; fix the cause (extraction, provider, dimension mismatch) — see `ingestion-failures.md`. |

## Recovery criterion

`agentflow_messaging_consumer_lag_count` falls below 1000 for the `for: 10m` guard; the lag gauge stays at its normal low level as the ingester catches up.
