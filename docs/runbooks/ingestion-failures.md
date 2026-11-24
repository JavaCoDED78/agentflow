# Runbook: AgentFlow ingestion failures

Alert: `AgentFlow ingestion failures` (`agentflow-ingestion-failures`, severity `warning`).

Fires when `rate(agentflow_ingestion_index_failures_total[5m]) > 0` for more than 5 minutes — indexing passes are failing (transient provider faults, permanent extraction failures, or dimension mismatch at assembly).

## Symptoms and impact

- Documents fail to index — RAG search has less content than expected; the indexed count stays flat.
- The **AgentFlow / AI/RAG/Cost** dashboard ingestion-failure panel rises.

## Expected local-dev state

The counter is emitted only by the ingestion role (`IngestionService`). A local API boot emits no ingestion series — this rule is deliberately silent there (`noDataState: OK`).

## Diagnostics

1. The failure rate: `curl -s 'http://localhost:9090/api/v1/query?query=rate%28agentflow_ingestion_index_failures_total%5B5m%5D%29'`.
2. The ingestion workers: is the ingestion-worker healthy and draining — check the worker logs (`service_name="agentflow-app"`) and its batch-of-documents trend in the dashboard.
3. Failures vs successes: the index-failure panel against the ingested-document trend; a flat signal means the role is not running.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Provider/embedding outage | Transient provider faults are retried internally; a sustained failure burst is a provider event — check provider health and the chat/embedding failure alert (`provider-failures.md`). |
| Permanent extraction failures (bad/corrupt/encrypted files) | Extraction errors are permanent by design; the documents stay unindexed — inspect the failed files and the extraction error codes in the logs; do not delete the failed rows by hand. |
| Dimension mismatch at assembly | A misconfigured/incompatible embedding path is an application defect — check the `agentflow.knowledgebase.*` embedding config, and escalate. |

## Recovery criterion

`rate(agentflow_ingestion_index_failures_total[5m])` returns to 0 for the `for: 5m` guard; the indexed trend resumes.
