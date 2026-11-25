# Sprint 12 — Redis wiring (S12-16 … S12-21)

Detailed per-task changelog records for the Redis thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

This thread is bounded by [`ADR-021`](../../adr/ADR-021-redis-role.md): Redis is a **helper / caddy component** — a rate-limit store, a rebuildable cache, or short-lived coordination — never the source of truth. `ADR-021` lists every task of the thread (S12-16 … S12-21, S13-22, S14-08, S14-18, S15-20) so each can be checked against the same written boundary.

## Contents

- [S12-16 — the opt-in Redis client: typed config, enabled flag and single connection creator](#s12-16-the-opt-in-redis-client-typed-config-enabled-flag-single-connection-creator)
- [S12-17 — the Redis health indicator and the Testcontainers fixture](#s12-17-the-redis-health-indicator-and-the-testcontainers-fixture)
- [S12-18 — the redis key builder, value codec and TTL policy](#s12-18-the-redis-key-builder-value-codec-and-ttl-policy)
- [S12-19 — the distributed fail-open rate limiter](#s12-19-the-distributed-fail-open-rate-limiter)
- [S12-20 — the Redis limiter is a helper, the quota is durable](#s12-20-the-redis-limiter-is-a-helper-the-quota-is-durable)
- [S12-21 — documenting the Redis boundary in one reference](#s12-21-documenting-the-redis-boundary-in-one-reference)

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

## S12-18 — the redis key builder, value codec and TTL policy

### What changed

**One builder, one codec, one TTL policy.** `common` gains the small domain-neutral package `com.example.agentflow.common.redis` (the same home and precedent as the S12-09 `SecretRedactor` and the S04-17 rate limiter — a domain-neutral primitive, not business logic). The `ADR-021` acceptance (key format, TTL policy, no-secret) is expressed here, enforced before Redis is even touched.

**`RedisPurpose` (closed enum).** `ratelimit` / `cache`, both tenant-scoped (`isTenantScoped()`). An unknown purpose is rejected by `RedisPurpose.fromWire(String)` (throws `IllegalArgumentException` naming the allowed set), so no later author can quietly store workflow state, an idempotency key or auth material under a new purpose (`ADR-021` — the purpose names the key namespace, so a consumer tells the value's role from the key alone).

**`RedisKeySpec` / `RedisKey` / `RedisKeyBuilder`.** The builder is the single entry point that assembles every key. Key shape: `agentflow:{env}:{purpose}:{tenantId}:…`.
- `{env}` is a trusted, bounded config tag (1–24 chars), written as the second segment.
- `{tenantId}` sits in the namespace (not a post-filter), so cross-tenant keys never overlap and a missing key is indistinguishable from another tenant's (`invariant 1`); a tenant-scoped purpose without a tenantId is rejected.
- Each suffix segment is scanned before it is written: no whitespace / control characters, ≤ `MAX_SEGMENT_LENGTH = 40` characters, and no `SecretRedactor.containsSecret` signature; the assembled key is ≤ `MAX_KEY_LENGTH = 200` characters. A rejected segment is **never echoed** into the error message, so a secret cannot leak into a log or metric (`invariant 8`).
- Every build fails with an unclassified `IllegalArgumentException`, so the refusal is a contract fault, not a runtime crash.

**`RedisValue` / `RedisValueCodec`.** `RedisValue` wraps content in a `0.1` versioned envelope with a defensive copy of the payload bytes (`RedisValue.wrap(byte[])` / `wrap(String)`; the codec exposes `encode(String) → RedisValue` and `decode(RedisValue) → String`). `RedisValueCodec.encode` rejects a value that carries a secret / PII / prompt / document field (`SecretRedactor.containsSecret`) or exceeds `MAX_VALUE_BYTES = 1024` UTF-8 bytes (large payloads go to Garage, `ADR-021`). Decode is lossless UTF-8; the version stamp is preserved for a future format bump.

**`RedisTtl` (mandatory, positive).** `RedisTtl.of(long)` / `of(Duration)` require a positive TTL ≤ `MAX_SECONDS = 300` (zero / negative / over-the-bound all reject). The `RedisKeyValueStore` port takes a `RedisTtl` on `put` — there is no `put` without one, so "write without TTL" is impossible (`ADR-021` / TTL policy). `get` returns `Optional<byte[]>`, so an empty store never masquerades as an empty string.

**`RedisKeyValueStore` (port).** The only Redis entry point: `put(RedisKey, RedisValue, RedisTtl)` + `get(RedisKey)`. The concrete Redis adapter is `S12-19`.

**No new module edge.** Everything lives in `com.example.agentflow.common.redis` and imports only `com.example.agentflow.common` (`SecretRedactor`) and the JDK — the `common` boundary permits `common` itself. No architecture-gate change, no ADR (same precedent as S12-09). The builder / codec / TTL are plain utilities (the `Fingerprint` idiom, not Spring beans, no state), so `S12-19` (rate limiter) and `S14-18` (cache) share one instance per deployment.

### Why

The key format, the tenant-scoping, the no-secret rule and the mandatory TTL are the Redis contract. Expressing them once in a shared boundary — not repeated in the rate limiter, the cache and any later Redis use — prevents them from drifting apart: the tenantId in the namespace is a single guard, not a discipline; the secret signature is the one `SecretRedactor` already owns; the TTL bound is a single value. It is the "expressed once, cannot drift" shape the rest of this thread was built on.

### Acceptance

- Every key is built through `RedisKeyBuilder`; the closed purpose set, tenant-scoping and no-secret rule are enforced (tests below).
- An unknown purpose is rejected; a tenant-scoped purpose without a tenantId is rejected; a segment with whitespace / a secret / too long is rejected; a key over the bound is rejected.
- `encode` / `decode` round-trip; a value over 1024 bytes or carrying a secret is rejected; the envelope is `0.1`.
- The TTL is always positive and ≤ 300 s; there is no `put` without a TTL.
- The canary never leaves its fixture: `RedisKeyCanaryTest` proves the planted key canary is rejected by the builder and never enters a built key.

### Verification

DB-free suites, JUnit-XML-verified, **fresh this run**:

- `RedisKeyBuilderTest` — `tests=12` `failures=0` `errors=0` `skipped=0`: key format `agentflow:local:ratelimit:{tenant}:…`; cross-tenant keys never overlap; unknown purpose rejected; tenant-scoped without tenantId rejected; whitespace / secret / json-api-key / over-bound segments rejected; over-long env rejected; a valid key builds.
- `RedisValueCodecTest` — `tests=9` `failures=0` `errors=0` `skipped=0`: `0.1` envelope; encode/decode round-trip; null content / null value rejected; over-1024-bytes rejected; bearer / json-api-key secret rejected; defensive copy.
- `RedisTtlTest` — `tests=7` `failures=0` `errors=0` `skipped=0`: positive within bound accepted; 0 / negative / over-300 / null rejected.
- `RedisKeyCanaryTest` — `tests=3` `failures=0` `errors=0` `skipped=0`: the canary lives only in the sanctioned fixture, is rejected as a key segment (never enters a key) and never appears in a legitimately built key.

`./gradlew :apps:agentflow-app:check` green — unit + architecture (`ArchitectureBoundaryTest` — the new classes are in `common.redis`, importing only `common`; the ALLOW map is unchanged) + no-cycles + formatting (`spotlessCheck`) + OpenAPI contract. The `RedisPasswordLeakTest` (canary scan over all tracked files) also passes — the new test class reads the fixture and never hardcodes the canary.

### Documentation

- This file (`docs/devlog/sprint-12-redis.md`).
- `CHANGELOG.md` — the S12-18 entry.
- `ADR-021` — the key-format / TTL contract is recorded there as enforced by these tests.

### Scope / follow-up

S12-18 defines the contract only (no Redis wiring). The concrete Redis adapter / fail-open rate limiter is `S12-19`; the run cache is `S14-18`.

## S12-19 — the distributed fail-open rate limiter

### What changed

**A Redis-backed `RateLimiter` adapter.** `common` gains [`RedisRateLimiter`](../../apps/agentflow-app/src/main/java/com/example/agentflow/common/rate_limit/RedisRateLimiter.java) (`com.example.agentflow.common.rate_limit`, the same package as the S04-17 `InMemoryRateLimiter`). It resolves the same `RateLimiter` port, counts each key against a one-minute budget the same way, but the counter lives in Redis so the limit is **global across the deployment** — several workers / instances on one Redis observe the same limit (`ADR-021`: Redis is the shared helper store).

The counter is keyed the tenant-scoped `ratelimit` key the `RedisKeyBuilder` (S12-18) builds — `agentflow:{env}:ratelimit:{tenantId}:{group}:…` — the tenantId in the namespace, not a post-filter (`invariant 1`): a per-window counter, the window index part of the key, so it resets on every 60 s boundary. The budget per group is derived from the `RateLimitPolicy` (exactly like the in-memory adapter). The increment is one atomic Lua script (`INCR` + a first-writer `PEXPIRE` of two windows) over `StringRedisTemplate`, so a crash mid-window never inflates the budget and a write is never TTL-less; the script returns `used` and the adapter reports `remaining = budget − used` as `Optional<Integer>` (empty when the group is unbounded).

**Fail-open, never a blanket 429 (`ADR-021`).** Redis is a helper: an outage must not reject legitimate traffic. On any Redis failure or command timeout — a `RuntimeException` thrown by `StringRedisTemplate.execute` (the whole `Lettuce` failure family is a `RuntimeException`, verified against the 4.1.1 jar) — the adapter does **not** throw or reject: it increments the `agentflow.redis.rate_limit.fallback_open` counter (no tenant / principal / id in the label, `invariant 9`) and delegates the decision to the injected in-memory `RateLimiter`. A permanently down Redis therefore throttles the local JVM like the in-memory adapter, not like a hard 429.

**A plain class, wired by condition.** The adapter is `final` and never a `@Component` — a bean factory (`RateLimitConfiguration`) produces it, so when Redis is disabled the `common.rate_limit` package still compiles and no Redis import leaks into the skeleton. Exactly one adapter is produced: the `redisRateLimiter` bean in [`RateLimitConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/RateLimitConfiguration.java) is `@ConditionalOnProperty(prefix = "agentflow.redis", name = "enabled", havingValue = "true")`, and the `inMemoryRateLimiter` bean in [`RateLimitWebMvcConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/common/rate_limit/RateLimitWebMvcConfiguration.java) is `@ConditionalOnProperty(..., havingValue = "false")` — mutually exclusive. The unchanged `RateLimitInterceptor` (S04-17) consumes the `RateLimiter` bean, so nothing else changes.

### Why

The in-memory limiter throttles per JVM, so a multi-replica deployment could each serve its own full budget — the throttle is only *N×* replicas strong and a single slow replica cannot be throttled. A Redis-backed counter makes the throttle a single global limit, which is the intended shape (one limit across the deployment), while the fail-open keeps the `ADR-021` boundary: Redis is never the reason a legitimate request is rejected.

### Acceptance

- The Redis `RateLimiter` counts one global budget across instances: two limiter instances wired to one Redis serve exactly the budget (60) of 120 calls (`RedisRateLimiterIT#two_limiter_instances_observe_a_single_global_limit`).
- Per-window reset: the counter is per window (window index in the key).
- Fail-open: on a throwing template the adapter returns the in-memory result and increments the `fallback_open` meter (`RedisRateLimiterTest`, `RedisRateLimiterIT#fails_open_to_the_in_memory_limiter_when_redis_is_unreachable`).
- No secret / no high-cardinality label: the `fallback_open` meter carries only the metric name; the principal is hashed (SHA-256, first 12 bytes hex) before it is put in the key.
- The adapter compiles and boots with Redis disabled (the in-memory adapter is produced instead).

### Verification

Unit, DB-free, JUnit-XML-verified, **fresh this run**:

- `RedisRateLimiterTest` — `tests="8"` `failures="0"` `errors="0"` `skipped="0"`: budget accounting (`budget − used`); the key is tenant-scoped and deterministic (`execute` is called with `agentflow:local:ratelimit:{tenant}:…`); the TTL is ≤ the bound; the `fallback_open` meter increments once per Redis failure; a throwing template fails open to the in-memory limiter (returns its `remaining`, never throws).

Integration, real Redis, Testcontainers **fresh this run**:

- `RedisRateLimiterIT` — `tests="2"` `failures="0"` `errors="0"` `skipped="0"`: `two_limiter_instances_observe_a_single_global_limit` (two instances on one `redis:8.10.2` serve exactly 60 of 120) and `fails_open_to_the_in_memory_limiter_when_redis_is_unreachable` (an unreachable port fails open to the in-memory limiter and the `fallback_open` meter counts 2).

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture (`ArchitectureBoundaryTest` — `RedisRateLimiter` is in `common.ratelimit`, importing only `common` / `common.redis`; the ALLOW map is unchanged) + no-cycles + formatting (`spotlessCheck`) + OpenAPI contract. The `RedisPasswordLeakTest` canary scan also passes.

**No new dependency, no new module edge, no ADR** — the adapter imports only `common`, `common.redis`, the JDK and the already-present Spring Data Redis; the wiring is in the already-present `config` / `common.rate_limit` classes. The new files are the adapter, the unit test and the integration test.

### Documentation

- This file (`docs/devlog/sprint-12-redis.md`).
- `CHANGELOG.md` — the S12-19 entry.

## S12-20 — the Redis limiter is a helper, the quota is durable

### What changed

**The durable quota stays on PostgreSQL; Redis never owns it (`ADR-021`).** The per-tenant
parallel-run quota is the sole owner on PostgreSQL: the `QuotaService` (`usage.application`) backed by
the `tenant_budgets.max_runs` column and the `tenant_run_slots` rows (`V3_3_27`, `S12-05`). The Redis
adapter (S12-19) is a **per-tenant rate limiter** on the run-creation endpoint only — it throttles the
*rate* of run creation, it never counts or caps concurrent runs. The two concerns are independent, so
a full Redis cleanup changes nothing durable: the usage / budget state is byte-for-byte unchanged and a
tenant cannot bypass the tenant quota (the PostgreSQL quota still serializes the acquire race).

**Tests.** The `usage.application` package gains
[`QuotaRedisDependencyIT`](../../apps/agentflow-app/src/integrationTest/java/com/example/agentflow/usage/application/QuotaRedisDependencyIT.java)
(real `pgvector/pgvector:0.8.6-pg18` + `redis:8.10.2` Testcontainers, the same pinned images Compose
runs — no H2 stand-in):

- `the_race_caps_at_the_budget_with_the_redis_limiter_present` — the `S12-05` concurrent-acquire race
  caps at `max_runs` exactly as it does without Redis, while the Redis limiter is live for the tenant
  (proven by the limiter returning its live `remaining` before the race).
- `clearing_redis_leaves_usage_state_unchanged_and_keeps_the_quota` — a `FLUSHALL` leaves
  `budgets` / `usage_records` / `slots` counts unchanged, a tenant already at its limit gains no new
  slot (0 of 60), and a fresh tenant's durable quota is still enforced (exactly `1` of 60) with Redis
  wiped.

The OPS-throttle endpoint path is proven DB-free by
[`OpsRateLimitEndToEndTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/common/error/OpsRateLimitEndToEndTest.java)
(the module boundary is respected — see below): the `common.rate_limit.RateLimitInterceptor` produces
the `RateLimitExceededException` for `OPS` (already covered by `RateLimitInterceptorTest`), and the
same-package `GlobalExceptionHandler#handleRateLimit` renders it as `429 api_rate_limited` with a
`correlation_id` and a `Retry-After` header.

**Module boundary.** The handler test lives in `com.example.agentflow.common.error` and builds the
`RateLimitExceededException` directly, so it imports **only** `common.error` / `common` — no
`common.rate_limit`. A naive combined test in `common.error` would add a `common.error →
common.rate_limit` edge; production already has the reverse `common.rate_limit → common.error` (the
interceptor throws the exception), so that edge would close a cycle and fail the no-cycles gate. The
throw half therefore stays in `common.rate_limit` (`RateLimitInterceptorTest`, existing edge) and the
render half in `common.error`; together they prove the full `interceptor → exception → 429` chain
without introducing any new edge.

### Why

Redis is a helper, never the source of truth (`ADR-021` / `ADR-002`): losing Redis must not lose a
run or a tenant quota. The quota is the durable parallel-run ceiling enforced on PostgreSQL
(`tenant_budgets` row lock, `SELECT … FOR UPDATE`, counted in `V3_3_27`); the Redis limiter is a
separate, non-durable throttle on the endpoint *rate*. Keeping the quota off Redis means a Redis
outage degrades the endpoint throttle to the in-memory limiter (S12-19) while the tenant quota is
unaffected, and a Redis cleanup is an operation that changes nothing the system depends on.

### Acceptance

- The concurrent-acquire race caps at the budget with the Redis limiter present (`QuotaRedisDependencyIT`).
- Clearing Redis leaves the usage / budget state unchanged and does not let a tenant bypass the quota (`QuotaRedisDependencyIT`).
- The OPS endpoint throttle renders as `429 api_rate_limited` with a `correlation_id` and `Retry-After` (interceptor → exception → handler), the throw half in `common.rate_limit` and the render half in `common.error` (`OpsRateLimitEndToEndTest`).

### Verification

Unit, DB-free, JUnit-XML-verified, **fresh this run**:

- `OpsRateLimitEndToEndTest` — `tests="1"` `failures="0"` `errors="0"` `skipped="0"`: the OPS throttle
  maps to `429`, `error_class = api_rate_limited`, `retryable = true`, a non-blank `correlation_id`
  and a `Retry-After` of `30`.

Integration, real PostgreSQL + Redis, Testcontainers **fresh this run**:

- `QuotaRedisDependencyIT` — `tests="2"` `failures="0"` `errors="0"` `skipped="0"`: the race caps at
  `max_runs` with the live Redis limiter, and a `FLUSHALL` leaves the durable state unchanged while a
  fresh tenant's quota is still enforced (0 bypass).

`./gradlew :apps:agentflow-app:check --rerun-tasks` green — unit + architecture
(`ArchitectureBoundaryTest` + no-cycles: the new handler test adds no new edge) + formatting
(`spotlessCheck`) + OpenAPI contract.

**No new dependency, no new module edge, no ADR** — the tests import only classes already inside
`common` / `usage` / `common.redis`; `ADR-021` already records that Redis is a helper, never the
source of truth. The new files are the integration test and the handler test.

### Documentation

- This file (`docs/devlog/sprint-12-redis.md`).
- `CHANGELOG.md` — the S12-20 entry.

## S12-21 — documenting the Redis boundary in one reference

### What changed

**A new canonical reference — [`docs/architecture/redis-usage.md`](../architecture/redis-usage.md)** —
covering everything the plan (`S12-21`) requires that the per-task devlog records and the ADRs do not:
the permitted uses and the prohibited ones (ADR-021), the exact key format and TTL policy (single
`RedisKeyBuilder`, `agentflow:{env}:{purpose}:{tenantId}:…`, ≤ 200 chars, every key TTL-bound), the
failure policy per use (fail-open for the rate limiter, fail-open for a rebuildable cache, fail-closed
for coordination), the full configuration reference (`agentflow.redis.*` + the three env vars, per
profile), what the observability profile can observe (`agentflow.redis.rate_limit.fallback_open`, the
`cache` health group), and the smoke/health verification evidence (a live boot: `/actuator/health/cache`
`DOWN` + `readiness` `UP` with Redis stopped, `UP` after recovery).

**A new operational runbook — [`docs/runbooks/redis-outage.md`](../runbooks/redis-outage.md)** — the
symptoms (healthy-path health groups UP/DOWN), the diagnosis (`docker ps`, `redis-cli ping`, the
health endpoints), the safe actions (restart Redis, leave the app running, never `FLUSHALL`, never
silence the limiter), and the recovery criterion, with the same evidence.

**Alignment of the reference docs with the shipped Redis surface:**

- `docs/architecture/system-context.md` now names the API→Redis connection (the rate limiter,
  S12-19) in the internal-infrastructure block — the diagram/description now matches the code.
- `docs/architecture/api-conventions.md`: the stale "When the Redis adapter is added (S12-19) the
  counters are stored in Redis…" prediction became reality (the adapter is shipped, S12-19); the
  ops budget group name is corrected from the wrong `ops:dlq` to the shipped `ops`
  (`RateLimitGroup.OPS`, `agentflow.ratelimit.limits.ops.max-per-minute`); and a `#### Redis
  adapter (S12-19)` subsection documents the key shape, the atomic Lua script, the fail-open
  contract and the link to `redis-usage.md`.
- `.env.example`: the Redis block now documents the three real env vars
  (`AGENTFLOW_REDIS_HOST` / `AGENTFLOW_REDIS_PORT` / `AGENTFLOW_REDIS_PASSWORD`) and the typed
  `agentflow.redis.*` config, replacing the invented `spring.redis.host` / `spring.redis.password`
  entries that did not match the code.
- `docs/runbooks/README.md`, `docs/README.md` (tree + document-type table) and `README.md` (environment
  variables) now link the new reference and the new runbook.

**No code, no config, no migration, no module edge.** This is a documentation-only task; the doc
references are verified to exist on disk (every `../runbooks/…`, `../architecture/…`, `docs/…` and
`apps/…` anchor resolves) and no claim exceeds a verified behaviour.

### Why

The per-task devlog records and the ADRs capture decisions but not the operational surface: an
operator reading the runbook or the config reference today could not tell, from the docs, what Redis is
allowed to hold, what a Redis outage looks like, or what the `cache` health group probes. The plan's
`S12-21` requires exactly that reference, so the two documents are the single source of truth for the
Redis boundary — the ADRs and the per-task devlog records stay and now link to it.

### Acceptance

- `docs/architecture/redis-usage.md` — exists; covers the plan's five required sections (applications,
  key format + TTL, failure policy, config, metrics) — this file.
- `docs/runbooks/redis-outage.md` — exists; reuses the verified evidence (`RedisHealthIndicatorReadinessIsolationTest`, `RedisRateLimiterIT`, `RedisSmokeTest`, live boot).
- `docs/architecture/system-context.md` — the internal-infrastructure block now names the API→Redis connection (rate limiter).
- `docs/architecture/api-conventions.md` — the stale S12-19 prediction is replaced by the shipped reality; the ops group is corrected to `ops`.
- `.env.example` — the Redis block names the three env vars actually read by `RedisConnectionConfiguration` and no invented config.
- `docs/runbooks/README.md`, `docs/README.md`, `README.md` — link the new documents.
- Every cross-reference resolves; no claim exceeds a verified behaviour.

### Verification

- Every referenced file/anchor in the new documents and the updated ones resolves (the runbook
  references `docs/runbooks/README.md`, `docs/architecture/redis-usage.md`, `ADR-021`; the reference
  links `api-conventions.md`, `docs/runbooks/redis-outage.md`, the ADR and the devlog; each is
  read from disk).
- The `.env.example` block was read back: the three env vars are the only Redis variables, matching
  `RedisConnectionConfiguration` (host/port read, password via `spring.redis.password`, never typed).
- The internal-infrastructure sentence was read back and is consistent with the diagram.
- The `api-conventions.md` changes were read back: no stale `ops:dlq`/`When the Redis adapter is
  added` predictions remain.
- `git status` confirms only the documented files changed (plus the unrelated pre-existing
  `apps/agentflow-app/gradle.lockfile` regen, which is out of scope and left as is).

**No build gate applies** — documentation-only; no code, config or migration changes.

### Documentation

- This file (`docs/devlog/sprint-12-redis.md`).
- `CHANGELOG.md` — the S12-21 entry (this change).
- `docs/architecture/redis-usage.md` (new).
- `docs/runbooks/redis-outage.md` (new).
