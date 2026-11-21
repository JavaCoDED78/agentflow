# Sprint 12 — Redis wiring (S12-16 … S12-21)

Detailed per-task changelog records for the Redis thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

This thread is bounded by [`ADR-021`](../../adr/ADR-021-redis-role.md): Redis is a **helper / caddy component** — a rate-limit store, a rebuildable cache, or short-lived coordination — never the source of truth. `ADR-021` lists every task of the thread (S12-16 … S12-21, S13-22, S14-08, S14-18, S15-20) so each can be checked against the same written boundary.

## Contents

- [S12-16 — the opt-in Redis client: typed config, enabled flag and single connection creator](#s12-16-the-opt-in-redis-client-typed-config-enabled-flag-single-connection-creator)
- [S12-17 — the Redis health indicator and the Testcontainers fixture](#s12-17-the-redis-health-indicator-and-the-testcontainers-fixture)

---

## S12-16 — the opt-in Redis client: typed config, enabled flag and single connection creator

### What changed

**Opt-in at the type level.** [`AgentFlowProperties.Redis`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/AgentFlowProperties.java) is the nested typed config bound from `agentflow.redis.*` (`@ConfigurationProperties(prefix = "agentflow")`, validated as `@Valid` on the nested field, root `@Validated`):

- `enabled` (`boolean`, default `false`) — the client is wired only when set to `true`; when `false` **no connection bean exists** at all.
- `host` (`String`, `null` by default) — the environment-specific connection point; a missing host when enabled fails startup with a clear message, and it is **never a hardcoded default** (invariant: no localhost).
- `port` (`Integer`, default `6379`), `tls` (`boolean`, default `false`).
- `connectTimeoutSeconds` / `commandTimeoutSeconds` (`long`, `@Min(1)`, default `5`) — bounded so a blocked Redis can never hang a virtual thread indefinitely.

The typed config carries **no password field**: the password is never part of the bound object, so it can never reach the `configprops` Actuator, the config `toString`, logs or metrics.

**The sole connection creator.** [`RedisConnectionConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/RedisConnectionConfiguration.java) (`com.example.agentflow.config`) is `@ConditionalOnProperty(prefix = "agentflow.redis", name = "enabled", havingValue = "true")` and provides exactly one `@Bean redisConnectionFactory`. The password is read from the environment (`env.getProperty("spring.redis.password")`, set from `AGENTFLOW_REDIS_PASSWORD` in the profiles) and handed to the `RedisStandaloneConfiguration` directly. Timeouts are applied through `ClientOptions`/`TimeoutOptions` (connect) and the client builder (command); TLS is toggled via `useSsl()`.

**No auto-config second bean.** The base [`application.yml`](../../apps/agentflow-app/src/main/resources/application.yml) excludes the four real DataRedis auto-configs — `DataRedisAutoConfiguration`, `DataRedisReactiveAutoConfiguration`, `DataRedisRepositoriesAutoConfiguration`, `DataRedisReactiveHealthContributorAutoConfiguration` — so `RedisConnectionConfiguration` is the single creator and there is no `redisTemplate`/reactive-`RedisClient`/health-indicator left pointing at `localhost:6379`.

**Profiles.** `application-local.yml` and `application-kubernetes.yml` set `agentflow.redis.enabled = true` with `host`/`port` from `AGENTFLOW_REDIS_HOST`/`AGENTFLOW_REDIS_PORT`. The kubernetes profile's password is `${AGENTFLOW_REDIS_PASSWORD}` with **no default** (fail loud, invariant 8); the local profile's is `${AGENTFLOW_REDIS_PASSWORD:}` (empty by default — no auth on the local Redis). A profile's `spring.autoconfigure.exclude` *replaces* the base list (Spring Boot merges by profile, not by append), so the local profile repeats the four DataRedis exclusions while keeping `DataSourceAutoConfiguration` out (so the DataSource stays re-enabled there).

**In the skeleton, Redis is optional.** The in-memory rate limiter (`common`) stands in for a Redis-backed limiter (S04-17) when Redis is disabled, so the app is wired for Redis from the first sprint without depending on it.

### Why

The skeleton must boot and pass readiness **without** Redis running (S01-02), and Redis is reachable from the API for rate limiting. Without an opt-in flag and a single connection creator, the starter's auto-config would eagerly create beans at `localhost` and fail readiness; a future task would then add Redis-owned state and quietly erode the "PostgreSQL is the source of truth" invariant (`ADR-002` / `ADR-021`). Wiring the opt-in client now, against the written boundary, lets every later Redis task (S12-17 … S12-21) be checked the same way.

### Acceptance

- Disabled by default: no connection bean; the app boots and `/health/readiness` is `UP` with no Redis (`HealthEndpointsTest`).
- The typed config binds and validates; a missing password is never held in it (`RedisPropertiesTest`, `RedisPasswordLeakTest`).
- Opt-in via `enabled`; `host` required when enabled, never a hardcoded `localhost`.
- The kubernetes profile wires the connection and boots when `AGENTFLOW_REDIS_PASSWORD` is supplied (`ConfigurationKubernetesProfileTest`).

### Verification

DB-free suites, JUnit-XML-verified, **fresh this run**:

- `RedisPropertiesTest` — `tests="3"` `failures="0"` `errors="0"` `skipped="0"`: `enabled` binds (default `false`); `connect-timeout-seconds`/`command-timeout-seconds` are `@Min(1)` (a `0` fails binding); `tls` binds.
- `RedisPasswordLeakTest` — `tests="6"` `failures="0"` `errors="0"` `skipped="0"`: a canary password (`src/test/resources/secrets/canary-redis-password.txt`) is never present in the bound `AgentFlowProperties.Redis` or its `toString`, and is never written to the committed `application.yml` / `application-local.yml` / `application-kubernetes.yml`.
- `ConfigurationKubernetesProfileTest` — `tests="1"` `failures="0"` `errors="0"` `skipped="0"`: the kubernetes profile loads with the required `AGENTFLOW_REDIS_PASSWORD` supplied.
- `ConfigurationLocalProfileTest` — `tests="2"` `failures="0"` `errors="0"` `skipped="0"`; `ConfigurationTestProfileTest` — `tests="1"` `failures="0"` `errors="0"` `skipped="0"`; `HealthEndpointsTest` — `tests="5"` `failures="0"` `errors="0"` `skipped="0"` (`/health/readiness` `UP` with no Redis).

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (`ArchitectureBoundaryTest` — `common` is the documented home of the shared primitives, no new edge) + no-cycles + formatting (`spotlessApply`) + OpenAPI contract (`OpenApiContractCheckTest` 1/1, `OpenApiContractTest` 4/4).

**No new dependency, no new module edge, no ADR** — the wiring lives in `com.example.agentflow.config` and imports only Spring / the already-present typed config. The only new files are the two config classes, the profile wiring and the two unit tests.

### Documentation

- [`ADR-021`](../../adr/ADR-021-redis-role.md) — Redis as helper/caddy component; the `S12-16` follow-up entry ("Redis client + `enabled` flag and typed config").
- This file (`docs/devlog/sprint-12-redis.md`).
- [`CHANGELOG.md`](../../CHANGELOG.md) — the S12-16 entry.

### Scope / follow-up

S12-16 wires the opt-in client only; it does **not** store anything in Redis. The follow-ups (all inside the `ADR-021` boundary) are: S12-17 the Redis health indicator (Testcontainers fixture, non-critical group, never readiness/liveness); S12-18 the key builder + TTL policy; S12-19 the distributed fail-open rate limiter; S12-20 quota/ops; S12-21 the `docs/architecture/redis-usage.md` runbook.

## S12-17 — the Redis health indicator and the Testcontainers fixture

### What changed

**An observable Redis dependency.** [`RedisHealthIndicator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/RedisHealthIndicator.java) (`com.example.agentflow.config`) implements `org.springframework.boot.health.contributor.HealthIndicator` — the Boot 4.x health package, which moved out of the actuator artifact in 4.x (the health API now lives under `org.springframework.boot.health`, as noted in `ADR-021`) — and reports `org.springframework.boot.health.contributor.Status`. A single check opens a connection, sends the bare `PING`, and returns `UP` with the `PONG` in the details; any failure closes the connection and reports `DOWN`. The connection is always closed in a `finally`, so both paths release it. Fail-open is **not** applied here — the indicator is an operator observation, not a gate; the Redis *consumers* (the rate limiter, the cache) keep their own fail-open behaviour (S12-19).

**A non-critical group.** `RedisConnectionConfiguration` exposes the indicator as a bean named `redisHealthIndicator` and is `@ConditionalOnMissingBean(name = "redisHealthIndicator")`, so it is the only `redis` contributor (it suppresses Boot's own auto-configured one). The `local` profile's `management.health.group.cache.include = redis` ([`application-local.yml`](../../apps/agentflow-app/src/main/resources/application-local.yml)) puts it under a **non-critical** `cache` group that is deliberately **not** part of `readiness`/`liveness` (the base profile keeps those empty of app indicators, `ADR-021` / scenario 8 §13): a down Redis moves `/health/redis` to `DOWN` but never drops the pod from rotation. The indicator exists only when Redis is enabled — the connection bean exists — so the group reference is never dangling.

**The fixture and the smoke test.** [`RedisSmokeTest`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/config/RedisSmokeTest.java) (the `integrationTest` source set) spins up one `redis:8.10.2` Testcontainers container — the **same pinned image Compose runs** (`infra/compose/docker-compose.yml`, S01-13) — and exercises the exact client the app uses (`LettuceConnectionFactory`, built the same way `RedisConnectionConfiguration` wires it for the local profile: plain TCP, no auth, no TLS). Three things are asserted: a `SET`/`GET` cache value, a `TTL` that counts down within its window, and the bare `PING` the indicator depends on. It needs no external Redis (Testcontainers).

### Why

Scenario 8 (§13): an unavailable Redis must not drop the app from rotation. An observability dependency that also gated readiness/liveness would turn every Redis blip into a restart; grouping it as non-critical keeps Redis a *helper* (`ADR-021`) while still making its reachability visible to the orchestrator and operators.

### Acceptance

- A stoppable Redis moves the indicator to `DOWN` while `readiness` stays `UP` (`RedisHealthIndicatorReadinessIsolationTest` — real `local` profile, connection pointed at an unreachable `127.0.0.1:1`, read through `HealthEndpoint`: `redis` = `DOWN`, `readiness` = `UP`).
- The unit test covers both paths: reachable → `UP` (the `PONG`), unreachable → `DOWN` (`RedisHealthIndicatorTest`).
- The fixture uses the same major-version image as Compose (`redis:8.10.2`).
- `./gradlew :apps:agentflow-app:integrationTest` runs the smoke test with no external Redis (Testcontainers).

### Verification

DB-free suites, JUnit-XML-verified, **fresh this run**:

- `RedisHealthIndicatorTest` — `tests="2"` `failures="0"` `errors="0"` `skipped="0"`: reachable Redis reports `UP` with the `PONG`; an unreachable Redis (a `PING` that throws) reports `DOWN` and closes the connection.
- `RedisHealthIndicatorReadinessIsolationTest` — `tests="1"` `failures="0"` `errors="0"` `skipped="0"`: a down Redis moves `/health/redis` to `DOWN` and leaves the readiness group `UP` (`RedisConnectionConfiguration` wires the real connection from `agentflow.redis.host=127.0.0.1`, `agentflow.redis.port=1`; the `DataSourceAutoConfiguration` is excluded so the local profile's real database is not required).
- `RedisSmokeTest` (integration, Testcontainers) — `tests="3"` `failures="0"` `errors="0"` `skipped="0"`: `SET`/`GET` round-trip, a `TTL` that counts down within its window, the `PING` the health indicator depends on returns `PONG`.

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (`ArchitectureBoundaryTest` — the indicator + fixture live in `com.example.agentflow.config`, no new edge) + no-cycles + formatting (`spotlessApply`) + OpenAPI contract.

**No new dependency, no new module edge, no ADR** — the indicator and the fixture import only Spring / the already-present typed config. The new files are the indicator, the unit test, the integration smoke test and the `cache` group wiring in `application-local.yml`.

### Documentation

- This file (`docs/devlog/sprint-12-redis.md`).
- [`CHANGELOG.md`](../../CHANGELOG.md) — the S12-17 entry.
