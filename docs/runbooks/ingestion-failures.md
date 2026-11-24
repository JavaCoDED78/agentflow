# Runbook — AgentFlow ingestion failures

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow ingestion failures` (`agentflow-ingestion-failures`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-ingestion-failures`), the ingestion family in [`docs/observability/metrics.md`](../observability/metrics.md), the ingestion worker/service, and the RAG pipeline in [`docs/rag/pipeline.md`](../rag/pipeline.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests a manual document edit or a raw
re-index.

Fires when `rate(agentflow_ingestion_index_failures_total[5m]) > 0` — indexing
passes are failing (transient provider faults, permanent extraction failures, or
dimension mismatch at assembly) — sustained for **5 minutes** (`for: 5m`).

## Symptoms and impact

- Documents fail to index — RAG search has less content than expected; the indexed
  count stays flat.
- The **AgentFlow / AI/RAG/Cost** dashboard ingestion-failure panel rises.
- Retrieval slows or misses content the user expects; no document is lost.

## Expected local-dev state

The counter is emitted only by the ingestion role (`IngestionService`). A local API boot emits no ingestion series — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. The failure rate: `curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_ingestion_index_failures_total%5B5m%5D%29'`.
2. The ingestion workers: is the ingestion-worker healthy and draining — check the worker logs (`service_name="agentflow-app"`) and its batch-of-documents trend in the dashboard.
3. Failures vs successes: the index-failure panel against the ingested-document trend; a flat signal means the role is not running.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Provider/embedding outage | Transient provider faults are retried internally; a sustained failure burst is a provider event — check provider health and the chat/embedding failure alert (`provider-failures.md`). |
| Permanent extraction failures (bad/corrupt/encrypted files) | Extraction errors are permanent by design; the documents stay unindexed — inspect the failed files and the extraction error codes in the logs; do not delete the failed rows by hand. |
| Dimension mismatch at assembly | A misconfigured/incompatible embedding path is an application defect — check the `agentflow.knowledgebase.*` embedding config, and escalate. |

## Re-index — and idempotency

Ingestion is retried idempotently: a document is indexed under its own version —
re-index is a re-run of the pipeline, not a double write that corrupts a search
index, and no document is lost while it is unindexed. If a failed pass is safe to
re-run, the controlled path is re-submitting the document through the normal
ingestion flow — **never** a raw re-index, a manual `INSERT`, or a row delete
(those skip the access-policy check, the event flow and the audit trail).

## Recovery criterion

`rate(agentflow_ingestion_index_failures_total[5m])` returns to 0 for the
`for: 5m` guard; the indexed trend resumes (the live state is visible via
`GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when failures persist after the provider
recovers, when extraction errors are not in the bounded vocabulary (bad input
vs. a real defect), or when the dimension mismatch is configuration drift — do
not re-index by hand.
