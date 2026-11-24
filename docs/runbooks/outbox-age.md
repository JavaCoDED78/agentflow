# Runbook: AgentFlow outbox oldest event age

Alert: `AgentFlow outbox oldest event age` (`agentflow-outbox-age`, severity `warning`).

Fires when `agentflow_messaging_outbox_oldest_age_seconds > 300` (oldest due outbox row older than 5 minutes) for more than 5 minutes.

## Symptoms and impact

- Events are delayed in the outbox — a consumer/listener behind the publisher (attention, not pageable loss: the rows are durable in PostgreSQL).
- The **AgentFlow / Workflow & Kafka** dashboard outbox panels (pending events, oldest age, publish attempts/retries) rise.

## Expected local-dev state

The outbox meters are emitted only by the messaging/ingestion roles (`OutboxPublisherService`). A local API boot emits no messaging series — this rule is deliberately silent there (`noDataState: OK`). It pages in any deployment where the messaging role runs.

## Diagnostics

1. The oldest age and the pending count: `curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_outbox_oldest_age_seconds'` and `curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_outbox_pending_events'` — both rising is the signature.
2. The publish-attempt mix: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28outcome%29+(rate%28agentflow_messaging_outbox_publish_attempts_total%5B5m%5D%29)'` — `transient_failure`/`ambiguous`/`permanent_failure` dominating means the publisher is failing, not just behind.
3. Kafka/broker state: `docker ps` (is the messaging profile up?) and the **AgentFlow / Workflow & Kafka** dashboard.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Kafka/broker down | Outbox rows are durable; the publisher will drain once the broker returns — confirm the broker is up and do **not** edit rows by hand; escalate per `docs/runbooks/`. |
| Publisher crashed/wedged | Restart the messaging role; the gauges and the publisher self-heal on success. |
| Outbox rows unpublished permanently | `permanent_failure` rows are terminal by design (see the outbox/inbox contract); inspect via the dashboard trend, not by manual edits. |

## Recovery criterion

`agentflow_messaging_outbox_oldest_age_seconds` falls below 300s for the `for: 5m` guard and the pending count returns to its normal low level.
