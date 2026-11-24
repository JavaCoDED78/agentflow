# Runbook — AgentFlow outbox oldest event age

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow outbox oldest event age` (`agentflow-outbox-age`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-outbox-age`), the messaging family in [`docs/observability/metrics.md`](../observability/metrics.md), the outbox/inbox contract in [`docs/architecture/durable-execution.md`](../architecture/durable-execution.md), and [`docs/runbooks/dlq-redrive.md`](dlq-redrive.md) (the redrive path).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests a manual database edit — the outbox is
durable and the publisher self-heals.

Fires when `agentflow_messaging_outbox_oldest_age_seconds > 300` — the oldest due
outbox row older than 5 minutes — sustained for **5 minutes** (`for: 5m`).

## Symptoms and impact

- Events are delayed in the outbox — the publisher is behind or stuck (attention,
  not pageable loss: the rows are durable in PostgreSQL).
- The **AgentFlow / Workflow & Kafka** dashboard outbox panels (pending events,
  oldest age, publish attempts/retries) rise.
- Consumers see delayed downstream work (ingestion, workflows) — no event is lost.

## Expected local-dev state

The outbox meters are emitted only by the messaging/ingestion roles (`OutboxPublisherService`). A local API boot emits no messaging series — this rule is deliberately silent there (`noDataState: OK`). It pages in any deployment where the messaging role runs.

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. The oldest age and the pending count:

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_outbox_oldest_age_seconds'
   curl -s 'http://localhost:9090/api/v1/query?query=agentflow_messaging_outbox_pending_events'
   ```

   Both rising is the signature.
2. The publish-attempt mix:

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28outcome%29+%28rate%28agentflow_messaging_outbox_publish_attempts_total%5B5m%5D%29%29'
   ```

   `transient_failure`/`ambiguous`/`permanent_failure` dominating means the publisher is failing, not just behind.
3. Kafka/broker state: `docker ps` (is the messaging profile up?) and the **AgentFlow / Workflow & Kafka** dashboard.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Kafka/broker down | Outbox rows are durable; the publisher will drain once the broker returns — confirm the broker is up and do **not** edit rows by hand; escalate per `docs/runbooks/`. |
| Publisher crashed/wedged | Restart the messaging role; the gauges and the publisher self-heal on success. |
| Outbox rows unpublished permanently | `permanent_failure` rows are terminal by design (see the outbox/inbox contract); inspect via the dashboard trend, not by manual edits. |

## Redrive — and idempotency

The outbox is published at-least-once; the inbox is the dedup boundary, so
re-publishing a row is safe and must **not** be done by hand. If the publisher is
permanently broken and a held message needs to run again, the controlled path is
the DLQ redrive (`docs/runbooks/dlq-redrive.md`): a fresh id, a `409` guard against
an already-processed inbox, and a recorded `dlq_redrive_attempts` row — never a
raw re-publish, never a row edit. The outbox rows themselves are never deleted,
edited or rolled back.

## Recovery criterion

`agentflow_messaging_outbox_oldest_age_seconds` falls below 300s for the
`for: 5m` guard and the pending count returns to its normal low level — the live
state is visible via `GET /api/prometheus/grafana/api/v1/rules`.

## Escalation

Escalate to the platform/on-call owner when the age does not clear after the
broker/publisher fix, or when `permanent_failure` dominates — do not hand-publish
or manually resolve rows.
