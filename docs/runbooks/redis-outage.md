# Redis outage — operational runbook

> **Status:** Accepted
> **Owner:** engineering
> **Task:** `S12-21`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 13, `S13-22`) ·
> Sprint 12, `S12-21` (runbook, fail-open limiter) ·
> `ADR-021` (Redis is a helper component) · `docs/architecture/redis-usage.md`
> **Purpose:** a focused, step-by-step procedure for a Redis outage: what to look for, how to confirm
> the failure, what is safe to do and when you are done. Redis is a helper component — its loss must
> **never** drop the app from rotation or lose run state.

---

## Symptoms

A Redis outage (unreachable host, dead container, network partition) shows up as:

- `/actuator/health` → **`DOWN`** — the aggregate status includes the `cache` group.
- `/actuator/health/cache` → **`DOWN`** (HTTP 503). The `redis` contributor (a bare `PING`) fails.
- `/actuator/health/readiness` → **`UP`** (HTTP 200) — deliberately unaffected. The redis contributor
  is in the non-critical `cache` group and is never part of `readiness` / `liveness` (S12-17), so
  the orchestrator keeps routing traffic.
- The limiter falls open to the in-memory limiter — traffic is still served, but the global limit is
  now per-replica. In the S13-22 meters this is visible as three signals at once:
  `agentflow.redis.command.errors.total` rises (one increment per failed command),
  `agentflow.redis.command.latency.seconds{result="failed"}` gains samples, and
  `agentflow.redis.rate_limit.decision.total{result="fallback"}` rises; the label-free
  `agentflow.redis.rate_limit.fallback_open` counter climbs alongside them.
- The `redis-unavailable` alert (`agentflow-redis-unavailable`, warning) fires when
  `rate(agentflow_redis_command_errors_total[5m]) > 0` for 5m — the expected local signal is that an
  app against a down Redis pages with this warning, not a critical.
- No durable state changes: PostgreSQL is the source of truth, so a run, a quota or a budget is never
  lost. The inbox and the run lease are unaffected (`ADR-021`).

What you must **not** see: readiness/liveness flipping to `DOWN`, runs failing to start, or the API
refusing to serve because Redis is down. If you do, that is a regression — escalate to the
`workflowexecution` / platform owner, not a restart.

## Diagnosis

All commands run from the repo root against the local stack (default local ports). Confirm in order.

1. **Is Redis reachable?**

   ```bash
   docker ps --filter name=agentflow-redis --format '{{.Names}}\t{{.Status}}'
   docker exec agentflow-redis redis-cli ping     # expect PONG
   ```

   A down container or a refused connection is the first thing to fix; a restart of the stack does
   not lose the in-flight state (Redis is a helper, not a store).

2. **Is the app still serving?**

   ```bash
   curl -s http://localhost:8080/actuator/health          # aggregate — expect DOWN while Redis is down
   curl -s http://localhost:8080/actuator/health/cache    # the redis group — expect 503 + DOWN
   curl -s http://localhost:8080/actuator/health/readiness # EXPECTED UP — the app stays in rotation
   ```

3. **Is the limiter falling open?** In Micrometer:

   ```bash
   curl -s http://localhost:9090/api/v1/query?query=agentflow_redis_command_errors_total
   curl -s http://localhost:9090/api/v1/query?query=agentflow_redis_rate_limit_fallback_open
   curl -s http://localhost:9090/api/v1/query?query=agentflow_redis_rate_limit_decision_total
   ```

   `fallback_open` increases by one per Redis failure, `decision.total{result="fallback"}` rises with
   it, the latency timer gains `failed` samples and `command.errors.total` climbs — all bounded
   labels (no tenant / principal / id). Traffic is still served — the limiter degrades to the
   per-replica in-memory limiter, so the effective fleet-wide budget is roughly `N ×` the configured
   budget (N = live replicas). This is the accepted degraded mode (ADR-021), **not** an outage of
   the API. The `redis-unavailable` alert (Prometheus → Grafana, warning) fires on
   `rate(agentflow_redis_command_errors_total[5m]) > 0` for 5m — check it in the Alerting UI (Grafana)
   or `GET /api/v1/provisioning/alert-rules` for the rule's live state.

4. **Is the app up?** If `/health` and `/health/readiness` are both `UP`, the failure is not in
   Redis — check the Redis consumer logs (`agentflow.redis.*` connections, lettuce warnings) and
   escalate. An app that refuses to serve because of Redis is a defect; do not suppress it.

## Safe actions

- Restart Redis (`docker compose --file infra/compose/docker-compose.yml --profile core up -d redis`
  or `docker start agentflow-redis`) — the health indicator re-probes on each HTTP call and recovers
  within seconds of the container being healthy.
- Leave the app running. Restarting the API is not required and is not a fix for the failure.
- Do **not** clear or flush Redis during an outage (no `FLUSHALL` / `FLUSHDB`): the keys are
  recomputable counters, but there is no reason to touch them.
- Do **not** lower the limiter budget to compensate — the fail-open fallback is the designed
  behaviour. Lowering the budget would hide the degraded mode.
- Do **not** treat a down Redis as a run failure, an inbox problem or a quota problem. Those live in
  PostgreSQL.

## Recovery criterion

- `/actuator/health/cache` → `UP` (HTTP 200) after Redis is healthy.
- `/actuator/health` → `UP`.
- The `fallback_open` counter stops rising (the limiter is serving through Redis again) — and with
  it, `command.errors.total` and the `decision.total{result="fallback"}` counter.
- The `redis-unavailable` alert clears (no error samples for 5m).
- The `readiness` probe stays `UP` throughout — it never flips for a Redis failure.

If `/actuator/health/cache` does not return to `UP` after a healthy Redis — the connection is
stale or the container is unhealthy; restart Redis and confirm the health endpoint, then escalate.

## Verification evidence

- `RedisHealthIndicatorReadinessIsolationTest` — real `local` profile, connection pointed at
  `127.0.0.1:1` (refused); through `HealthEndpoint`, `redis` = `DOWN` while `readiness` = `UP`.
- `RedisRateLimiterIT` — real `redis:8.10.2` (Testcontainers): two limiter instances share one global
  limit of 60, and an unreachable port fails open (the `fallback_open` meter counts the failures,
  alongside the `command.errors.total` counter, the failed latency samples and the
  `decision.total{result="fallback"}` counter); a slow / never-responding Redis returns within the
  configured command timeout (1 s in the test — `RedisRateLimiterIT#slow_responses_are_bounded_by_the_command_timeout_and_fail_open`)
  and fails open.
- `RedisSmokeTest` — real `redis:8.10.2` round-trip + TTL countdown + the `PING` the indicator uses.
- Live local boot: `/actuator/health/cache` goes `DOWN` (HTTP 503) with the container stopped and
  recovers to `UP` within seconds of restart, with readiness `UP` throughout.

## Related

- [`docs/architecture/redis-usage.md`](../architecture/redis-usage.md) — the full Redis reference
  (applications, key format, TTL, failure policy, config, metrics).
- [`docs/observability/metrics.md`](../observability/metrics.md) — the machine-checked catalogue.
- [`infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml`](../../infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml) — the `redis-unavailable` rule (S13-22).
- [`ADR-021`](../adr/ADR-021-redis-role.md) — the Redis boundary.
- [`docs/runbooks/README.md`](README.md) — the runbook index.
