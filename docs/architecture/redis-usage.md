# Redis Usage — AgentFlow

> **Status:** Accepted
> **Owner:** engineering
> **Task:** `S12-21`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 12, `S12-21`) ·
> `ADR-021` (Redis is a helper component) · `docs/devlog/sprint-12-redis.md`
> **Purpose:** the single operational reference for **every** Redis application — what Redis holds,
> how keys are shaped, how TTL is enforced, how each use fails, how it is configured and what is
> observable. It is the document the runbook (`docs/runbooks/redis-outage.md`) diagnoses from.

---

## 1. What Redis is — and is not (ADR-021)

PostgreSQL is the source of truth and Kafka transports events; Redis is a **helper
component** — a store whose loss must never change a result (`ADR-021`, invariant 2). It runs in the
`core` (and `full`) Compose profile on the pinned `redis:8.10.2` image from S01-13 and is reachable
from the API role only. The worker role never connects to Redis: it reads durable state from
PostgreSQL.

**Permitted uses only.** Each permitted use is implemented in exactly one place and holds a
recomputable or non-correctness-bearing value:

| Use | Today | Key purpose (closed enum) | Fail-open behaviour |
|---|---|---|---|
| Rate-limit state (distributed limiter) | **Live — S12-19** | `ratelimit` | Fail open: fall back to the in-memory limiter + metric + log |
| Rebuildable cache | **Planned — S14-18** | `cache` | Fail open: miss falls through to PostgreSQL |
| Short-lived coordination | None today | (none — the enum is closed to `ratelimit` / `cache`) | Fail closed unless a safe in-memory fallback exists |

**Prohibited uses — never.** Workflow state, the run lease / fencing (`ADR-011`), idempotency keys,
the inbox, access rights / allowlists, and usage or cost counters. Those stay in PostgreSQL or
Kafka, and a tenant is derived from the verified context (never from Redis). Redis never holds
authorization material (`ADR-007`).

**Topology (S12-16, S15-20).** The connection is created by one class —
`RedisConnectionConfiguration` (`com.example.agentflow.config`), `@ConditionalOnProperty(
prefix = "agentflow.redis", name = "enabled", havingValue = "true")` — never by Spring Boot
auto-configuration (the four DataRedis auto-configs are excluded from the base profile). The app
boots and passes `/health/readiness` **without** Redis: the base profile leaves
`agentflow.redis.enabled: false`, so no connection bean exists. The `local` and `kubernetes`
profiles set it to `true`. The API role connects; the worker role does not connect to Redis.

## 2. Rate limiting — the live application

The Redis-backed `RateLimiter` (`com.example.agentflow.common.rate_limit.RedisRateLimiter`,
wired in `RateLimitConfiguration`) satisfies the S04-16 `RateLimiter` port exactly like the in-memory
adapter (S04-17), except the counter lives in Redis so the limit is a **single global** number across
all API replicas instead of one per JVM. It throttles the endpoint *rate* only — it never counts
concurrent runs, budgets or any durable usage state.

- Group → key is tenant-scoped and deterministic: `agentflow:{env}:ratelimit:{tenantId}:{group}:
  {windowSeconds}:{windowIndex}:{hashedPrincipal}`. The `group` is the group's `configKey()` —
  `create-run` (`POST /api/v1/runs`), `upload`, `search/debug`, `ops` (`GET /api/v1/ops/dlq`,
  `GET /api/v1/ops/dlq/*`, `POST /api/v1/ops/dlq/*/redrive`). The principal is never the token — it is
  SHA-256 (first 96 bits, hex) of the verified principal, so the key never carries a secret or a
  raw user id.
- **Counter and window.** A fixed 60 s window; the window index is part of the key, so the counter
  resets on every window boundary (not on expiry alone). Each call issues one atomic Lua script
  (`INCR` + first-writer `PEXPIRE` of two windows) over `StringRedisTemplate`, so a crash mid-window
  never inflates the budget and a write is never TTL-less.
- **TTL.** The key is written with a `RedisTtl` of two windows (`2 * 60 s` = 120 s), inside the
  `RedisTtl` bound (positive, ≤ 300 s).
- **Budget.** Derived from the `RateLimitPolicy` of the group (exactly like the in-memory adapter).
  An unlimited group returns `Optional.empty()`, same as the in-memory adapter.
- **Health.** The `redis` contributor (`RedisHealthIndicator`) lives in the non-critical `cache`
  group — never in `readiness` / `liveness` (S12-17).
- **OPS surface.** The operator DLQ surface is throttled under its own `ops` budget group (default
  300/min, `agentflow.ratelimit.limits.ops.max-per-minute`, S12-13) so an operator cannot exhaust
  the business API or the inbox it feeds by hammering redrive.

## 3. Key format, TTL and value contract (S12-18)

Every key is built by the single `RedisKeyBuilder` (`com.example.agentflow.common.redis`) — one
builder, one format, one TTL policy, no drift between the rate limiter, the cache and any later
Redis use.

| Segment | Example | Rule |
|---|---|---|
| `agentflow` | fixed prefix | constant, `RedisKeyBuilder.KEY_PREFIX` |
| `{env}` | `local` | trusted, bounded (1–24 chars) environment tag |
| `{purpose}` | `ratelimit` | closed `RedisPurpose` set: `ratelimit`, `cache` — anything else is rejected at the boundary |
| `{tenantId}` | `8f0b…` | present for every permitted purpose (both are tenant-scoped) — cross-tenant keys differ inside the namespace |
| `<suffix parts>` | `create-run`, `60`, `<windowIndex>`, `<hash>` | each ≤ 40 chars, no whitespace / control characters, free of secret signatures, key ≤ 200 chars |

The `tenantId` sits in the **namespace**, not a post-filter: another tenant's key is
indistinguishable from a missing one (invariant 1). A tenant-scoped purpose built without a
`tenantId` is rejected at the builder.

**Values.** Values are wrapped in a versioned `0.1` envelope by `RedisValueCodec` — a defensive copy
of the payload bytes with a version tag. A value that carries a secret or exceeds 1024 UTF-8 bytes is
rejected: large payloads go to Garage, never to Redis (`ADR-013`). Keys and values never contain
secrets, PII, model prompts or document text.

**TTL is mandatory.** `RedisTtl` is positive and ≤ `RedisTtl.MAX_SECONDS` (300 s); there is no
`put` without a `RedisTtl` (the `RedisKeyValueStore` port requires one), so "write without TTL" is
impossible.

## 4. Failure policy and the fail-open contract

Redis is a helper. Each use fails in a fixed, documented way before the code lands:

| Use | On Redis unavailable / timeout | Contract |
|---|---|---|
| Rate-limit state | Fail open — the adapter increments the `agentflow.redis.rate_limit.fallback_open` counter (no tenant / principal / id in the label, invariant 9) and delegates the decision to the injected in-memory limiter | The request is allowed (never a blanket 429 because of Redis); the per-replica in-memory limit is **not** the global limit and is documented as such (`ADR-021` — accepted, never presented as globally exact) |
| Rebuildable cache | Fail open — a miss / unavailable Redis falls straight through to PostgreSQL | Recomputable copy only; never a wrong answer |
| Short-lived coordination | Fail closed unless a safe in-memory fallback exists | Loss means "start over", never "commit a wrong result" |

The `redis` **health indicator** deliberately does **not** fail open: it is an operator observation,
not a gate. It returns `DOWN` on any connection failure or non-`PONG` outcome (`RedisHealthIndicator`,
S12-17), and it sits in the non-critical `cache` group — it is never part of `readiness` / `liveness`,
so an unavailable Redis can never drop a pod from rotation.

## 5. Metrics and observability

Everything observable about Redis is deliberately small (invariant 9) — only closed label value sets,
never a tenant / user / run / document id. The S13-22 catalogue entry (machine-checked by the
`TelemetryPrivacyTest` catalogue walk):

| Signal | Where | Notes |
|---|---|---|
| `redis` health group | `/actuator/health/cache` (HTTP) · `RedisHealthIndicator` | `UP` with the `PONG`, `DOWN` on any failure; not in readiness/liveness |
| `agentflow.redis.command.latency.seconds` (Timer) | Micrometer, registered by `RedisRateLimiter` | one sample per Redis command (just before the command through its conclusion), labels `operation` (`rate_limit`) and `result` (`succeeded` / `failed`); `percentiles-histogram` opt-in so p95 is renderable |
| `agentflow.redis.command.errors.total` (Counter) | Micrometer, registered by `RedisRateLimiter` | one increment per failed Redis command, label `operation` (`rate_limit`) |
| `agentflow.redis.rate_limit.decision.total` (Counter) | Micrometer, registered by `RedisRateLimiter` | one increment per throttled-group decision, labels `group` (lowercase enum name: `create_run` / `upload` / `search_debug` / `ops`) and `result` (`allowed` / `limited` / `fallback`) |
| `agentflow.redis.rate_limit.fallback_open` (Counter) | Micrometer, registered by `RedisRateLimiter` | one increment per Redis failure, no label |

The `agentflow.redis.*` signals feed the API/JVM/DB dashboard (panels 13–16) and the
`redis-unavailable` alert (`agentflow-redis-unavailable`, warning: `rate(agentflow_redis_command_errors_total[5m]) > 0` for 5m — see `docs/runbooks/redis-outage.md`).
Do not add tenant / user / run / document identifiers as labels.

## 6. Configuration reference

All configuration is typed under `agentflow.redis.*` (`AgentFlowProperties.Redis`, `@Valid`),
bound by the base profile and overridden per environment. The typed config carries **no password
field** — the password is read from the environment (`spring.redis.password`) by
`RedisConnectionConfiguration`, so it can never reach the configprops Actuator, the logs or the
connection-factory `toString`.

| Property | Env var | Base (default) | local | kubernetes | Notes |
|---|---|---|---|---|---|
| `agentflow.redis.enabled` | — (profile-set) | `false` | `true` | `true` | The single opt-in flag; no connection bean exists when `false` |
| `agentflow.redis.host` | `AGENTFLOW_REDIS_HOST` | `null` (required when `enabled=true`) | `localhost` | `redis` | Required only when enabled; never a hardcoded localhost |
| `agentflow.redis.port` | `AGENTFLOW_REDIS_PORT` | `6379` | `6379` | `6379` | |
| `agentflow.redis.tls` | — | `false` | `false` | `false` | When `true`, `LettuceConnectionFactory` uses `useSsl()` |
| `agentflow.redis.connect-timeout-seconds` | — | `5` | `5` | `5` | `@Min(1)` — a misconfigured bound fails binding at startup |
| `agentflow.redis.command-timeout-seconds` | — | `5` | `5` | `5` | `@Min(1)` |
| `spring.redis.password` | `AGENTFLOW_REDIS_PASSWORD` | `(empty)` | `(empty)` | **required** (no default — a missing secret fails boot loudly) | Read by `RedisConnectionConfiguration`, never by the typed config |

The base profile also excludes the four DataRedis auto-configs
(`DataRedisAutoConfiguration`, `DataRedisReactiveAutoConfiguration`,
`DataRedisRepositoriesAutoConfiguration`, `DataRedisReactiveHealthContributorAutoConfiguration`) so
there is never a second connection bean and no reactive health contributor fails readiness. The
`local` profile repeats exactly those four — a profile's `spring.autoconfigure.exclude` list
*replaces* the base one, so the local profile re-enables `DataSourceAutoConfiguration` (the
datasource then comes from the environment) while keeping Redis excluded; the `kubernetes` profile
does not touch the exclude list and inherits the base exclusions (Redis excluded;
`RedisConnectionConfiguration` is the sole Redis connection creator) and reads the password from
the environment with no default.

`AGENTFLOW_REDIS_HOST` / `AGENTFLOW_REDIS_PORT` / `AGENTFLOW_REDIS_PASSWORD` are the only Redis
variables an operator supplies — everything else is the typed config, set per profile. The local
`.env.example` documents the three env vars; the operational runbook is
`docs/runbooks/redis-outage.md`.

## 7. Smoke / health verification

Checked live (app booted against the `local` profile with a real Redis):

- Redis up: `/actuator/health` → `UP`, `/actuator/health/cache` → `UP` (HTTP 200), `/actuator/health/
  readiness` → `UP`.
- Redis down (container stopped): `/actuator/health` → `DOWN`, `/actuator/health/cache` → `DOWN`
  (HTTP 503), `/actuator/health/readiness` → `UP` (HTTP 200). The app stays in rotation.
- Redis restored: `/actuator/health/cache` → `UP` again.
- The `redis` contributor through `HealthEndpoint` (the orchestrator probe path, S12-17) is `DOWN`
  while readiness is `UP` — `RedisHealthIndicatorReadinessIsolationTest`.
- The rate-limit fail-open is proven against a real `redis:8.10.2` container —
  `RedisRateLimiterIT` (`two_limiter_instances_observe_a_single_global_limit`, `fails_open_to_the_in_memory_limiter_when_redis_is_unreachable`).
- Slow / hanging responses are bounded by the configured command timeout (S14-08) — `RedisRateLimiterIT`
  (`slow_responses_are_bounded_by_the_command_timeout_and_fail_open`): a reachable Redis that accepts
  the connection but never replies is served by the in-memory failover within the bound, never an
  unbounded pin (1 s in the test; 5 s in production config).

## 8. Related

- [`ADR-021`](../adr/ADR-021-redis-role.md) — the Redis boundary (the decision this document applies).
- [`docs/runbooks/redis-outage.md`](../runbooks/redis-outage.md) — the operational runbook for a Redis outage.
- [`docs/architecture/api-conventions.md`](api-conventions.md) — the rate-limiting contract and the `429` envelope.
- [`docs/domain-glossary.md`](../domain-glossary.md) — "Rate limit" / "Quota".
- [`docs/devlog/sprint-12-redis.md`](../devlog/sprint-12-redis.md) — the per-task changelog records.
