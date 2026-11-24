# Runbook: AgentFlow provider failures

Alert: `AgentFlow provider failures` (`agentflow-provider-failures`, severity `warning`).

Fires when `rate(agentflow_ai_provider_chat_errors_total[5m]) + rate(agentflow_ai_provider_embedding_errors_total[5m]) > 0` for more than 5 minutes (provider wrapper errors — `rate_limit` / `server_error` / `timeout` / `connection` / `client_error` / `unknown` / `concurrency_limited`).

## Symptoms and impact

- AI-backed steps fail or degrade to deterministic fallbacks; token/latency panels stay flat with errors rising.
- The **AgentFlow / AI/RAG/Cost** dashboard provider-error and latency panels rise (chat and embedding legs separately, with the reason split).

## Expected local-dev state

The errors are emitted only by the provider wrappers (`ResilientChatModel` / `ResilientEmbeddingModel`). A local API boot emits no AI series — this rule is deliberately silent there (`noDataState: OK`). The fake-provider scenario (a scripted latency/error spike) is the intended way to exercise this alert in a dev/demo environment — see the S13-16 dashboard notes.

## Diagnostics

1. The error rate: `curl -s 'http://localhost:9090/api/v1/query?query=sum+by+%28reason%29+(rate%28agentflow_ai_provider_chat_errors_total%5B5m%5D%29)'` — the reason vocabulary (`rate_limit` / `server_error` / `timeout` / `connection` / `client_error` / `unknown` / `concurrency_limited`) tells you which side is misbehaving.
2. Provider latency vs errors: the **AgentFlow / AI/RAG/Cost** dashboard — a latency spike with flat errors is degraded, a rising error count is failing.
3. Provider health: check the provider endpoint the deployment points at (e.g. the configured `agentflow.ai.*` provider) — the wrapper's classified reason is the signal, not the raw SDK error.

## Likely causes and safe actions

| Cause | Action |
|---|---|
| Provider-side restriction (rate limit / outage) | Throttle back / respect the `rate_limit` reason; the retry budget is bounded by design — do not bypass it. |
| Provider latency/500 spike | Check the provider health; the wrapper classifies what it sees — a `timeout` or `server_error` burst is the signature. |
| Application-side throttle (`concurrency_limited`) | The throttle is the wrapper's own guard — the application-side fallback path is correct; do not disable the throttle. |

## Recovery criterion

The rate of chat+embedding errors returns to 0 for the `for: 5m` guard; the dashboard error panels fall back to flat.
