# Runbook — AgentFlow provider failures

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Alert:** `AgentFlow provider failures` (`agentflow-provider-failures`, severity `warning`)
> **Task:** `S13-18`
> **Upstream reference:** the rule in [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) (`agentflow-provider-failures`), the AI/RAG family in [`docs/observability/metrics.md`](../observability/metrics.md), the provider adapters (`ResilientChatModel` / `ResilientEmbeddingModel`) and the telemetry rules in [`docs/observability/standards.md`](../observability/standards.md).

This runbook is for the operator who receives this page. It says what the alert
means, what is affected, how to confirm the signal, what is safe to do, and when
the incident is over. It never suggests re-running a failed call by hand.

Fires when `rate(agentflow_ai_provider_chat_errors_total[5m]) + rate(agentflow_ai_provider_embedding_errors_total[5m]) > 0` — provider wrapper errors — sustained for **5 minutes** (`for: 5m`).

## Symptoms and impact

- AI-backed steps fail or degrade to deterministic fallbacks; the token/latency
  panels stay flat with errors rising.
- The **AgentFlow / AI/RAG/Cost** dashboard provider-error and latency panels rise
  (chat and embedding legs separately, with the reason split).
- Concurrency-limited or rate-limited work degrades honestly — bounded, not
  retried into a storm.

## Expected local-dev state

The errors are emitted only by the provider wrappers (`ResilientChatModel` / `ResilientEmbeddingModel`). A local API boot emits no AI series — this rule is deliberately silent there (`noDataState: OK`). The fake-provider scenario (a scripted latency/error spike) is the intended way to exercise this alert in a dev/demo environment — see the S13-16 dashboard notes.

## Diagnostics

All commands run from the repo root against the local stack (default ports).

1. The error rate — the reason vocabulary (`rate_limit` / `server_error` / `timeout` / `connection` / `client_error` / `unknown` / `concurrency_limited`) tells you which side is misbehaving:

   ```bash
   curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28reason%29+%28rate%28agentflow_ai_provider_chat_errors_total%5B5m%5D%29%29'
   curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28reason%29+%28rate%28agentflow_ai_provider_embedding_errors_total%5B5m%5D%29%29'
   ```

2. Provider latency vs errors: the **AgentFlow / AI/RAG/Cost** dashboard — a latency spike with flat errors is degraded, a rising error count is failing.
3. Provider health: check the provider endpoint the deployment points at (e.g. the configured `agentflow.ai.*` provider) — the wrapper's classified reason is the signal, not the raw SDK error.
4. Usage: check the token/cost panel for the burst — a failure storm usually comes with flat token flow.

## Likely causes and safe mitigations

| Cause | Action |
|---|---|
| Provider-side restriction (rate limit / outage) | Throttle back / respect the `rate_limit` reason; the retry budget is bounded by design — do not bypass it. |
| Provider latency/500 spike | Check the provider health; the wrapper classifies what it sees — a `timeout` or `server_error` burst is the signature. |
| Application-side throttle (`concurrency_limited`) | The throttle is the wrapper's own guard — the application-side fallback path is correct; do not disable the throttle. |

## Re-run — and idempotency

A failed provider call is **ambiguous**: it may have taken effect, so the
executor persists the attempt before the call and marks ambiguous outcomes — it
never hides a possible repeat. AI steps retry boundedly (the idempotency/retry
metadata of the step and the tool registry), and a workflow recovery re-claims
only the work that never committed. Do **not** re-run a provider call by hand, re-publish an event, or force a step
to RE-RUN outside the state machine; let the bounded retry path work, and
escalate when it does not clear.

## Recovery criterion

The rate of chat+embedding errors returns to 0 for the `for: 5m` guard; the
dashboard error panels fall back to flat (the live state is visible via
`GET /api/prometheus/grafana/api/v1/rules`).

## Escalation

Escalate to the platform/on-call owner when a provider burst does not clear,
when the reason mix changes (e.g. `connection` → `server_error`), or when the
failure mode is not in the closed vocabulary — do not re-run calls by hand;
inspect the wrapper's classification and the provider.
