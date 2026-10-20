# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- **Java 25 toolchain (`S01-05`).** The Gradle build pins the JDK 25 toolchain
  for every subproject, forces UTF-8 source encoding and a fixed compile
  release (`options.release = 25`), so the build never silently uses the
  host/system JDK. `gradle.properties` disables on-demand JDK downloads, so a
  missing or incompatible JDK fails fast and explicitly (offline CI).
- **Version catalog (`S01-06`).** Plugin and dependency versions are centralized in
  `gradle/libs.versions.toml`; modules reference them via the `libs` accessor instead
  of pinning versions inline, so one library cannot drift across build files.
  Spring-managed dependencies go through the Spring Boot and Spring AI BOMs
  (`spring-boot-dependencies:4.1.1`, `spring-ai-bom:2.0.1`).
  `pluginManagement`/`dependencyResolutionManagement` in `settings.gradle.kts` point at
  the Gradle Plugin Portal and Maven Central. Verified with `./gradlew help` and by
  resolving both BOMs (and applying the Boot plugin) against the real catalog.

- **Gradle reproducibility (S01-09).** The build now pins the wrapper checksum
  (`gradle/wrapper/gradle-wrapper.sha256`, checked by the `verifyWrapper` task on
  every invocation) and enables dependency locking (`lockAllConfigurations()`),
  committing `apps/agentflow-app/gradle.lockfile` (the main app dependency lock) plus
  the placeholder locks `settings-gradle.lockfile` and `apps/gradle.lockfile`; plugin
  versions are pinned explicitly in `pluginManagement`, so a build against a different
  graph fails instead of drifting. Update a dependency by
  editing the version catalog and regenerating the locks with
  `./gradlew --write-locks generateLockFiles`, then keep `./gradlew check` green.

- **Spring AI BOM compatibility (`S01-08`).** The Spring AI BOM
  (`spring-ai-bom:2.0.1`) is now consumed by `apps/agentflow-app`
  (`implementation(platform(libs.spring.ai.bom))`). Both BOMs import together with
  no forced overrides: Spring Framework resolves uniformly to `7.0.9` and Jackson
  to `3.1.5` (`tools.jackson`), so the dependency graph is predictable and
  reproducible. Manual overrides of Spring Framework/Jackson/Security remain
  forbidden without an ADR. A runtime test
  (`SpringAiBomCompatibilityTest`) exercises the BOM-managed `ObjectMapper`. See
  `docs/architecture/dependency-management.md` and the saved
  `docs/architecture/dependency-insight.txt`. Known follow-up (not a blocker): no
  `JavaTimeModule` on the runtime classpath, so `LocalDate` does not round-trip —
  a Jackson 3.x ecosystem question, out of scope for this task.

- **Java formatter / static check (`S01-10`).** Java sources are standardized with
  one formatter — Google Java Format, applied through the Spotless Gradle plugin
  (`com.diffplug.spotless:8.10.4`), pinned in the root `plugins {}` block. The
  `spotlessCheck` task is part of `./gradlew check`, so a formatting violation
  fails the build; `./gradlew spotlessApply` reformats every Java source under
  `src/`. Line endings, trailing whitespace and final newlines follow
  `.editorconfig` (LF). Existing skeleton files were reformatted to the standard.

- **Repository secret hygiene (`S01-11`).** Real credentials and local env files
  are not tracked. `.gitignore` excludes `.env`/`.env.*` (only `!.env.example`
  is committed), Java keytool artifacts (`*.keystore`, `*.p12`, `*.jks`) and
  local Compose data volumes / `docker-compose*.override.yml`. `.editorconfig`
  already enforces LF endings, UTF-8, trailing-whitespace trimming and final
  newlines. A `.env.example` template with `CHANGEME` placeholders and localhost
  defaults is committed for local use (real secrets stay in the environment or a
  secret store). A scan of every text blob in the commit history (43 files)
  against AWS / GitHub / Google / Stripe / Slack key patterns, PEM private keys
  key`/`token`/`password` assignments found no secret-like values.

- **PostgreSQL Compose service (`S01-12`).** `infra/compose/docker-compose.yml`
  starts the core profile's PostgreSQL on the pinned image
  `pgvector/pgvector:0.8.6-pg18` (pgvector 0.8.6 on PostgreSQL 18 — the same
  pgvector image Testcontainers uses). It exposes a named volume
  (`agentflow-pgdata`), a healthcheck (`pg_isready`), a `core` profile so the
  minimum stack starts without the other services, and env-driven config with
  local-only defaults (`POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB`/
  `POSTGRES_PORT`) overridable through a git-ignored `.env`. Note: PostgreSQL 18
  requires the data mount at `/var/lib/postgresql` (not
  `/var/lib/postgresql/data`); see the compose file comment. Verified: `docker
  compose config` passes, the service reaches `healthy`, and test data plus the
  `vector` extension survive a container restart through the named volume.

- **Redis Compose service (`S01-13`).** `infra/compose/docker-compose.yml` starts
  Redis 8 (Open Source) on the pinned image `redis:8.10.2` (latest stable `8.10.x`
  patch). Redis is a cache / short-lived coordination store, never a source of
  truth: workflow state stays in PostgreSQL, so losing Redis must not lose a run.
  It runs in the `core` profile alongside PostgreSQL, exposes a healthcheck
  (`redis-cli ping`), and persists local development data through an append-only
  file (AOF, `appendfsync everysec`) on the `agentflow-redis` named volume so it
  survives restarts. No auth is configured on the trusted local network;
  production (Kubernetes) must enable `requirepass` from a Secret, and the
  password is never hardcoded in app code. Verified: `docker compose ... up
  --profile core` brings the service to `healthy`, `redis-cli ping` returns
  `PONG`, and a set key survives a container restart through the AOF; `docker
  compose ... down` tears it down cleanly.

**- Kafka Compose service (S01-14).** `infra/compose/docker-compose.yml` starts a
single combined KRaft Kafka node (broker + controller) on the pinned image
`apache/kafka:4.3.1`, which is KRaft by default — no Zookeeper. It exposes an
`INTERNAL` listener (`0.0.0.0:9092`, inter-broker) and a `CONTROLLER` listener
(`:9093`, required by KRaft), advertises `INTERNAL://kafka:9092` so compose peers
resolve the broker by the `kafka` network alias, and sets the single-node quorum
voter to `1@localhost:9093`. It runs in the `messaging` profile with
`replication-factor`/`min.insync.replicas` at 1, a named volume
(`agentflow-kafka`) and a broker-level healthcheck (`kafka-broker-api-versions
--bootstrap-server localhost:9092`, which does not depend on compose DNS). KRaft
needs a cluster id to format its Raft log on first start; the service fails fast
unless `KAFKA_CLUSTER_ID` is provided (documented in `.env.example` and the
compose README). Verified: `docker compose --profile messaging up -d` brings the
service to `healthy` in ~10s; a producer (`acks=all`) publishes a message and a
consumer on the same network reads it back (`LOG-END-OFFSET=1`,
`Processed a total of 1 messages`).

- **Garage object storage service (`S01-15`).** `infra/compose/docker-compose.yml`
  now starts **Garage** (`dxflrs/garage:v2.4.1`, AGPL-3.0) — a free, open source,
  S3-compatible object store — instead of the upstream MinIO AIStor, whose
  pre-built images require a single-node SUBNET license to unlock S3. It runs in
  the `core` profile and auto-bootstraps a single-node cluster on first start
  (`--single-node` applies the layout, `--default-bucket` creates the `agentflow`
  bucket and a default S3 key from the environment); the layout, bucket and key
  persist across restarts. The S3 API is on `:3900` (configurable via
  `GARAGE_S3_API_PORT`). The RPC secret is injected via `GARAGE_RPC_SECRET` (not
  committed); the app's S3 client (`minio.access-key` / `minio.secret-key` in
  `.env`) must match the Garage root key. Verified: auto-bootstrap, an S3
  round-trip (list/put/get, payloads match by SHA-256) and S3 working again after
  a container restart with the same credentials. The trade-off is recorded in
  `docs/adr/s3-object-storage-garage.md`.

- **Compose profiles (S01-16).** `infra/compose/docker-compose.yml` now groups
  every service into six profiles — `core`, `messaging`, `identity`, `ai-local`,
  `observability`, `full` — so the minimum viable stack (`core`) starts without
  the rest. `full` reuses each service's definition by listing `full` in that
  service's `profiles` array (Compose cannot nest profiles). The observability
  profile runs `grafana/otel-lgtm` (the all-in-one OpenTelemetry Collector /
  Prometheus / Loki / Tempo / Grafana backend) instead of a five-container stack:
  the Loki exporter is absent from `otel/opentelemetry-collector-contrib` and
  `grafana/loki` exposes no OTLP ingest, so the all-in-one image keeps all three
  stores working (Grafana exposes all three as datasources). The image is pinned
  by digest; data persists on the `agentflow-observability` named volume; the
  Grafana admin password is local-only by default. Verified: `docker compose
  --profile X config` passes for all six profiles and the observability profile
  comes up `healthy` with all component endpoints responding.

- **Safe health endpoints (`S01-18`).** Spring Boot Actuator is wired into
  `apps/agentflow-app` (`spring-boot-starter-actuator`, BOM-managed, no pinned
  version) for minimal runtime diagnostics. Only `health` and `info` are exposed
  over HTTP and `liveness`/`readiness` are defined as dedicated health groups for
  the orchestrator; every other endpoint (`env`, `configprops`, `beans`,
  `mappings`, `loggers`, dumps) is outside the exposure allow-list, so it answers
  `404` anonymously. Authentication over management endpoints is added later
  `S04-11`). See `apps/agentflow-app/src/main/resources/application.yml`.

- **Standardized JSON logging (`S01-19`).** The app now emits structured JSON on
  stdout through
  `apps/agentflow-app/src/main/resources/logback-spring.xml`, satisfying the
  AGENTS.md requirement that logs carry `traceId`/`spanId` and `service`/`environment`
  and never leak secrets. Every event is rendered by
  `net.logstash.logback:logstash-logback-encoder:9.0` (the Jackson 3 / `tools.jackson`
  line — the only version compatible with the Boot 4.1.1 / Jackson 3.1.5 graph; the
  previously pinned `8.0` does not run on Jackson 3). The encoder writes the MDC keys
  (`service`/`environment`, and `traceId`/`spanId`/`correlationId` once S13 wires
  tracing), and a custom
  `com.example.agentflow.logging.SecretRedactingValueMasker` (registered through the
  encoder's `<valueMasker>`) redacts secret-like values before serialization —
  `Bearer`/`bearer` tokens, `api_key`/`token`/`password`/`secret` pairs and AWS access
  key IDs — so nothing reaches stdout, Loki, disk or the Actuator. A
  `com.example.agentflow.logging.LoggingContextProperties` bean seeds `service`/`environment`
  into the MDC on the main thread at startup. The `local` profile sets
  `com.example.agentflow` to `DEBUG` (see `application-local.yml`) while the default
  stays at `INFO`. Verified end-to-end: `LoggingJsonFormatTest` exercises the same
  encoder/decorator/masker as production and asserts parseable JSON carrying MDC and
  masked secrets, and a `bootRun` under `local` shows JSON with `service`/`environment`
  and `secret=***` in the output. Follow-up: `traceId`/`spanId` MDC fields are added
  when OTel is wired (S13).

- **Quickstart and smoke test (`S01-20`).** The root `README.md` gains a quickstart —
  prerequisites, starting/stopping the base stack, a ports table, environment
  variables and troubleshooting — so a developer without verbal instructions can
  start the stack. `scripts/smoke-local.sh` (S01-20) is the Sprint 1 smoke test:
  it starts a Compose profile (default `core`: PostgreSQL + pgvector, Redis,
  Garage), waits for every service to become `healthy` through its compose
  healthcheck, prints a per-service summary and tears the stack down, exiting
  non-zero if any service is not healthy. `messaging`/`full` need a one-time
  `KAFKA_CLUSTER_ID`, which the local `infra/compose/.env` provides. Verified:
  `docker compose config` passes for all six profiles and the smoke test passes
  for `core` and `full` (all services reach `healthy`).

### Fixed

- **Typed config binds under the default profile (`S01-17`).** The `agentflow.app.*`
  configuration was declared with a nested `agentflow.app.name` key, but the Java
  model is `@ConfigurationProperties(prefix = "agentflow")` with a flat `appName`
  field, which binds from `agentflow.app-name` / `agentflow.appName` only. The nested
  key never bound, so `app.name` came back as `null` under the default profile and the
  Spring context failed to start. All `application*.yml` profiles now use the flat
  `agentflow.app-name` key; the `@NotBlank` validation message and Javadoc were
  corrected to match. The startup-failure test now asserts on the real
  `ConfigurationPropertiesBindException` reported by `ApplicationContextRunner` (a
  missing required value fails context startup via `@Validated` binding), and
  `./gradlew clean check` is green.
- **`apps/agentflow-app` test task runs (S01-07).** The Boot 4.1.1 BOM manages JUnit
  to the 6.0.x line and `spring-boot-test` is compiled against the JUnit Platform
  6.0 `Store` API, so forcing the JUnit 5 line broke Spring context startup
  (`NoSuchMethodError`) and the platform discovered zero tests. We keep the
  BOM-managed JUnit 6.0.3 and add `junit-platform-launcher` (also BOM-managed) to
  the test runtime classpath. The `test` task now compiles and runs the
  `AgentflowApplicationTests` context-load test. `failOnNoDiscoveredTests = true`
  makes a future zero-test discovery fail the build instead of passing silently.
