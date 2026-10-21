# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- **PR architecture review checklist (`S02-14`).** The PR template
  (`.github/PULL_REQUEST_TEMPLATE.md`) gains a dedicated **Architecture review**
  section that replaces the previous single `Risks / ADR` block. Every PR now
  self-checks the architectural dimensions that matter for this repo: **Module
  ownership** (stay within the owning module's tables, repositories and internal
  packages, use the public API + published events, no new dependency cycle),
  **Tenant scope** (`(tenant_id, resource_id)` scoped reads/writes/searches, a
  foreign tenant's resource is indistinguishable from a missing one, tenant from
  verified context not the request body), **Retries & reliability** (transient vs.
  permanent classification, bounded exponential backoff/jitter, external calls
  persisted before the call and treated as at-least-once not exactly-once),
  **Side effects** (idempotent or lease/epoch-fenced calls, cancellation on a safe
  boundary, outbox/inbox and dedup respected), **Audit** (append-only actor/action/
  outcome without secrets), **Telemetry** (no secrets, full prompts/documents or
  high-cardinality labels in metrics/logs) and an **Integrity** gate, plus an
  explicit **ADR required?** question for a new module dependency, BOM bypass,
  persistence/migration change, contract change or invariant change. The checklist
  applies to code, config, migrations and contract changes, references
  `docs/architecture/module-map.md` and the sprint gate, and is documented in
  `CONTRIBUTING.md`'s review-before-PR guidance. Documentation-only change to the
  contribution workflow — no code, contracts or the `docs/README.md` tree changed.
  Verified by integrity (`git diff --check`, diff review) — no build gate for a
  PR-template change.
- **ADR template (`S02-12`).** `docs/adr/000-template.md` is the copy-paste starting
  point for any new architecture decision record: the header metadata (`Status`,
  `Date`, `Task`, `Supersedes`/`Superseded by`), the mandatory `Context` →
  `Decision` → `Alternatives considered` → `Consequences` sections (matching the real
  ADRs `ADR-001`…`ADR-004`), the rules for *when an ADR is needed* (new module
  dependency, BOM bypass, persistence/migration change, new node type or risk class,
  abandoning an invariant), and the register steps (next free `ADR-NNN`, row in
  `docs/adr/README.md`, link from the architecture index). Statuses follow
  `docs/adr/README.md`: `Proposed` → `Accepted`; a superseded ADR is kept and linked
  to its successor. `docs/adr/README.md` points to the template; `000-template.md` is
  the only file in `docs/adr/` that is not itself an ADR. Verified by integrity (every
  relative link resolves, `git diff --check`) — no build gate for a documentation-only
  task.
- **Error taxonomy (`S02-10`).** `docs/architecture/error-taxonomy.md` is the new
  canonical set of error classes every module classifies by; each has a stable
  `error_class` identifier, an HTTP mapping (RFC 9457 Problem Details), a retry
  decision, and an example. Classes: `validation` (400), `authentication` (401),
  `authorization` (403), `conflict` (409), `transient_infra` (503),
  `provider_rate_limit` (429), `provider_timeout` (504), `tool_policy_denial`
  (403), `cancellation` (202, not an error), `internal` (500 catch-all), plus the
  `404`/`code: not_found` mapping for missing and foreign-tenant resources. Retry
  applies only to the transient classes (bounded exponential backoff + jitter,
  `S07-10`); permanent classes end as `FAILED` / client error / DLQ and never
  retry; ambiguous provider-timeout outcomes are recorded, never claimed
  exactly-once (`ADR-003`). The identifiers are the contract shared by the global
  error handler, the worker retry classifier (`S07-11`), and the `error_class`
  metric label; the taxonomy is owned by `common`. `docs/README.md` links to it
  and its `⚠ planned (S02-10)` marker is removed. Verified by integrity (link
  resolution, `git diff --check`) — no build gate for a documentation-only task.
- **Time and identifier conventions (`S02-11`).**
  `docs/architecture/engineering-conventions.md` is the new engineering
  conventions for identifiers and time: **identifiers** are `UUID`, `UUIDv7` by
  default for generated IDs (time-sortable, index-friendly) and `UUIDv4` only
  where ordering must be avoided; no sequential/integer business PKs (guessable
  → enumeration); foreign keys are `<resource>_id` in `snake_case`. **Time** is
  UTC everywhere, stored as PostgreSQL `timestamptz` (never `timestamp without
  time zone`), expressed in Java as `Instant`/`OffsetDateTime` (never
  `java.util.Date`/`Calendar`/`LocalDateTime`, never `System.currentTimeMillis()`),
  and always produced through the injected `Clock`. The injectable `Clock` is the
  `S02-11` acceptance criterion: production uses a `Clock.systemUTC()` bean and
  time-dependent tests override it with `Clock.fixed(Instant, UTC)` (a
  lease/recovery fencing test is shown), so a lease, retry or recovery boundary
  is deterministic. **Naming** is layered: `snake_case` plural tables and
  `snake_case` columns matching the glossary verbatim, `camelCase` API fields
  (`runId` vs `run_id`), `UPPER_SNAKE_CASE` enums in DB / `snake_case` in JSON,
  and the Prometheus-safe `snake_case` `error_class` (`S02-10`). Money stays
  `NUMERIC`/`BigDecimal`. The doc anchors to the glossary (canonical names), the
  consistency ADRs (`ADR-002`…`ADR-004`) and invariant 10, distinguishes its
  scope from the planned `docs/architecture/database.md` (tables/migrations,
  `S03-16`), and links only to documents that exist. `docs/README.md` links to
  it in the architecture tree and the entry is `N/A` for any behaviour change.
  Verified by integrity (every relative link resolves, `git diff --check`) — no
  build gate for a documentation-only task.
- **Module boundary architecture gate (`S02-08`).** A JUnit 5 test,
  `ArchitectureBoundaryTest`, runs `@ArchTest` rules that forbid every compile-time
  edge in `docs/architecture/module-map.md` that the table does not permit. Each of
  the twelve modules contributes one sub-rule
  (`noClasses().that().resideInAPackage(<module>).should().dependOnClassesThat()
  .resideInAnyPackage(<all other modules minus self minus allowed>)`); they are
  combined into a single `CompositeArchRule` so one introduced violation anywhere
  fails `./gradlew check`. External dependencies (Spring, Jackson, `jakarta`, the
  database, Kafka) are not module edges and are therefore not forbidden. `common`
  is excluded from the forbidden set the same way it is in the map (shared
  primitives only). Verified against the acceptance criterion: an artificial
  `common -> audit` import fails the build with a precise message
  (`Field <common.X.t> has type <audit.Y> ... module common may only
  compile-depend on its own package plus []`), and the clean skeleton passes.
  ArchUnit `1.5.1` is pinned in the version catalog
  (`gradle/libs.versions.toml`) and pulled through
  `testImplementation(libs.archunit.junit5)` — it is not part of the Spring Boot
  4.1.1 BOM, so this does not bypass it. IMPORTANT: every `@ArchTest` class must be
  annotated `@AnalyzeClasses`; ArchUnit 1.5.x otherwise refuses the class and
  silently runs zero tests (a false pass). This is recorded in the class Javadoc
  so the annotation is never dropped.
- **No-cycles architecture gate (`S02-09`).** The twelve modules of the modular
  monolith must compile-depend on each other in one direction only: a compile-time
  cycle would make the ownership table and the sanctioned feedback path (the
  execution `StepHandler` SPI) meaningless. The gate has two parts. `PackageDependencyGraph`
  (a pure, dependency-free utility in `src/test/java/com/example/agentflow/architecture/`)
  enumerates every elementary cycle in the module graph; `PackageDependencyGraphTest`
  feeds it a specifically created cycle and asserts it is reported (the `S02-09`
  acceptance criterion) and that a self-loop is not treated as a cycle.
  `NoCyclicModuleDependenciesTest` imports the application packages with ArchUnit,
  derives the real module -> module edges from the bytecode, and asserts the graph
  is acyclic at `./gradlew check` time. The gate is a plain JUnit test (not an
  `@ArchTest`) because it has to aggregate the class graph before asserting, and it
  asserts the analysed class set is non-empty so a failed import cannot pass the
  gate with zero analysed packages (a false pass). Verified: the clean skeleton
  passes; a deliberately introduced `config` -> `observability` -> `config` cycle is
  reported by the detector and fails the test; the cycle is then reverted so the
  application stays runnable. The allowed direction of dependencies is documented in
  `docs/architecture/module-map.md` (`docs/README.md` architecture section) and the
  execution SPI is the only feedback path, which is what keeps the graph acyclic.
  No production code changed; ArchUnit `1.5.1` (pinned in the version catalog,
  `S02-08`) is reused, so no dependency is added.
- **Architecture gate wired into `check` (`S02-13`).** The module-boundary (S02-08) and
  no-cycles (S02-09) architecture gates now run as part of the standard Gradle `check`
  lifecycle, so a declared module boundary fails `./gradlew check` on every PR. The gates
  live in `apps/agentflow-app/src/test/java/com/example/agentflow/`: `ArchitectureBoundaryTest`
  (`@AnalyzeClasses` + `@ArchTest` — the annotation is mandatory on every ArchUnit 1.5.x
  class, otherwise the engine silently runs zero tests and the build passes without checking
  anything) enforces the `module -> module` edges in `docs/architecture/module-map.md`, and
  `NoCyclicModuleDependenciesTest` (with the dependency-free `PackageDependencyGraph`)
  verifies the module graph is acyclic. `test` is the fast gate - unit tests plus both
  architecture gates - and `check` depends on it (plus `spotlessCheck` and `verifyWrapper`),
  so the gate cannot be skipped; `failOnNoDiscoveredTests = true` turns a future zero-test
  discovery into a build failure instead of a silent pass. Verified against the acceptance
  criterion: a temporary `common -> observability` import fails `./gradlew check` with a
  precise message (`Field <common.TmpBoundaryViolation.properties> has type
  <observability.LoggingContextProperties>`) and reverting restores a green build. The test
  task structure is two tiers - the fast `check` (unit + architecture + formatting, no
  Docker) on every PR and the full `integrationTest` (Testcontainers on the real
  PostgreSQL/Kafka/Redis images, added in S03+). No new dependency; ArchUnit `1.5.1`
  (pinned in the version catalog, S02-08) is reused.
- **Package-by-feature module skeleton (`S02-07`).** The twelve bounded contexts of the modular
  monolith now each own a package under `apps/agentflow-app/src/main/java/com/example/agentflow/<module>/`
  (`identity`, `agentcatalog`, `workflowdefinition`, `workflowexecution`, `toolexecution`,
  `knowledgebase`, `ingestion`, `ai`, `usage`, `audit`, `messaging`, plus the cross-cutting
  `observability`). Each carries a `package-info.java` declaring the module's public-API boundary
  (application services and permitted DTO/ID/enum types — everything else per
  `docs/architecture/module-api.md`) and an `AGENTS.md` naming the single owner, the table it owns,
  and the internal packages (`persistence` / `web` / `worker` / `domain`) another module must not
  import. The `common` package is scoped to shared primitives only (IDs, `Clock`, the error taxonomy,
  redaction) — no business logic, so it is not an escape route for business rules. The S01-19
  structured-logging code that lived in `com.example.agentflow.logging` is consolidated into
  `observability` (the `logback-spring.xml` `<valueMasker>` reference is updated accordingly). This
  satisfies the `S02-07` acceptance criterion — every function has an explicit module owner — and
  leaves `./gradlew check` green. Boundaries are enforced by the architecture gate (`S02-08` /
  `S02-09`).
- **Consistency model ADRs (`S02-06`).** The durable-execution consistency model is documented as three
  architecture decision records — `docs/adr/ADR-002-postgresql-source-of-truth.md` (PostgreSQL is the
  single store of record; Kafka delivers and Redis caches, so losing either must not lose a run),
  `docs/adr/ADR-003-at-least-once-idempotency.md` (at-least-once delivery plus inbox/`event_id`
  idempotency; an external LLM/tool call is never exactly-once and its ambiguous outcome is marked in
  usage/history), and `docs/adr/ADR-004-transactional-outbox.md` (state and the `outbox_events` record
  write in one PostgreSQL transaction, published by a separate idempotent poller, with no shared
  PostgreSQL/Kafka transaction). Together they satisfy the `S02-06` acceptance criterion: the documents
  make no unjustified end-to-end exactly-once claim. They expand the plan §3 condensed descriptions of
  `ADR-002`…`ADR-004` (Context, Decision, Alternatives, Consequences), keep `docs/domain-glossary.md`'s
  reference to `ADR-003` valid, and the `adr/` register and the `adr/` tree in `docs/README.md` now
  point at them.
- **Modular monolith first ADR (`S02-05`).** `docs/adr/ADR-001-modular-monolith.md` records the
  deployment/topology decision: all twelve bounded contexts live in one Spring Boot artifact
  (`apps/agentflow-app`) and one repository with explicit public APIs between contexts, enforced
  by the automated architecture gate (`./gradlew check`, `S02-08` / `S02-09` / `S02-13`); the
  topology is one image, several roles, split physically only when a measured need (divergent
  resource profile, independent scaling, a distinct security boundary or an independent failure
  domain) appears. It captures the Context, Alternatives, Decision and Consequences the `S02-05`
  acceptance criterion requires, compares microservices-first and a flat monolith, and pins the
  split triggers plus the "the API Deployment must not run consumers; worker Deployments must not
  expose controllers as a public entrypoint" constraint from the plan §4.1. The ADR register
  (`docs/adr/README.md`) and the `adr/` tree in `docs/README.md` now point at it.
- **Module public API map (`S02-04`).** `docs/architecture/module-api.md` is the
  new public-API boundary for every bounded context. For each module it lists the
  public application services (use cases), the published event types (`agentflow.*
  .vN`), the permitted DTOs / IDs / enums, and the internal packages
  (`persistence` / `web` / `worker` / infra) that other modules must not import.
  It answers the `S02-04` acceptance criterion — another module reaches a target
  module **only** through an application service, a permitted public type or a
  published event type, never by importing repositories or entities — and is the
  "how to reach" counterpart to the module map (`docs/architecture/module-map.md`,
  `S02-03`) and the canonical identifiers in the domain glossary (`S02-01`). The
  `docs/README.md` documentation index links to it.
  single source of truth for terms: it defines the core concepts (tenant, agent /
  agent profile, tool, workflow definition vs. workflow version, run vs. step /
  attempt, event/envelope/outbox/inbox, knowledge base/document/chunk, citation,
  approval, usage record/budget/quota, audit event) and pins canonical
  identifiers to the target data model (`S03`), `/api/v1` API (`S03-12`),
  run/step state machine (`S05`), Kafka/event contract (`S06`) and AI
  orchestration (`S09`). A "do-not-use" alias section keeps the names that drift
  most easily (run, step, event, agent, tool, workflow, document, chunk,
  approval, usage) distinct across tables, DTOs and the API, satisfying the
  S02-01 acceptance criterion of one concept — one name. `docs/README.md` links
  to the glossary and its `⚠ planned (S02-01)` marker was removed.

- **Contribution and changelog rules (`S01-22`).** `CONTRIBUTING.md` now defines
  the branch, PR, test, documentation and secrets rules for contributors: one
  task per PR (`S##-##`), build through the Gradle wrapper (`./gradlew check`,
  `integrationTest`, `bootRun`), Testcontainers for DB/Kafka/Redis (no H2),
  negative/failure/recovery and tenant-isolation tests, "documentation as code",
  executable examples, and a hard rule that real credentials, API keys, JWTs and
  internal tokens never go into the repository or examples. The PR template now
  checks that `docs/`, `CHANGELOG.md` and `contracts/` are evaluated or marked
  `N/A`. `CHANGELOG.md` keeps the `[Unreleased]` skeleton with all four categories
  (`Added` / `Changed` / `Fixed` / `Security`), following Keep a Changelog; a
  first bootstrap entry is recorded under `Added`. The `⚠ planned` markers for
  these documents in `docs/README.md` were removed once they existed.

- **Documentation index and conventions (`S01-21`).** `docs/README.md` is now the
  documentation index: it shows where every topic lives, links the current
  documents (`docs/AGENTS.md`, `docs/product-scope.md`, `docs/adr/`,
  `docs/architecture/`, `docs/runbooks/`, `docs/threat-model/`, and the
  `contracts/` family) and marks future sections with a `⚠ planned` badge so the
  index stays truthful while the backlog is being filled out. A *document type →
  location* table tells a new author where each document belongs, and the
  *One source of truth / Documentation as code / Examples are executable*
  principles are restated so the index does not duplicate the rulebook in
  `docs/AGENTS.md`. `docs/AGENTS.md` gains a **Conventions** section that makes
  the Mermaid diagram rules, the examples-and-commands rules (verifiable
  commands, `curl` matching OpenAPI, no secrets), and the PR-documentation-currency
  rules (docs + contracts change with the code, remove `planned` markers, real
  PASS/FAIL/not-run verification) explicit. The root `README.md` points to the new
  index and to `contracts/`. No content was copied between documents.
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

- **System context diagram (`S02-02`).** `docs/architecture/system-context.md` is
  the new C4 Level 1 *system context* of AgentFlow. It shows every external actor
  and system — Client (the domain analyst via browser/CLI), Keycloak (IdP/OIDC),
  the cloud Model provider (OpenAI-compatible ChatModel), Garage/S3 object
  storage and allowlisted External tools — around the trusted AgentFlow boundary,
  plus the direction of trust for each flow and where secrets and documents
  cross it: Client input is untrusted and the tenant comes only from the verified
  Keycloak JWT; the Model is an untrusted planner; document payloads leave the
  DB as a tenant-scoped ref + `SHA-256` checksum; tool secrets come from secret
  management. The diagram is Mermaid inside Markdown (per
  `docs/AGENTS.md`), rendered with `flowchart` (a viewer-safe type), and
  syntax-validated with `mermaid.parse`. `docs/README.md` links to it from the
  architecture section.

- **Bounded-context module map (`S02-03`).** `docs/architecture/module-map.md` is
  the new C4 Level 2 of AgentFlow. It decomposes the modular monolith into its
  twelve bounded contexts (`identity`, `agentcatalog`, `workflowdefinition`,
  `workflowexecution`, `toolexecution`, `knowledgebase`, `ingestion`, `ai`,
  `usage`, `audit`, `messaging`, `observability`), records each module's
  responsibility, fixes the **single owner of every table** (anchored to the
  glossary's concept → owner table, §13) and the **single owner of every
  invariant** (tenant context, tenant scope, state machine, lease/fencing,
  allowlist, at-least-once delivery, budget, audit, error taxonomy, metric
  labels), and locks the **only allowed `module -> module` dependency edges**
  plus the execution `StepHandler` SPI that keeps the graph acyclic (`S02-09`).
  The table is the ground truth the architecture test checks and the authority
  for table ownership in migrations. `docs/README.md` links to it from the
  architecture section and its `⚠ planned (S02-03)` marker was removed.

### Changed

- No breaking or behavior changes yet. This skeleton is established in
  `S01-22`; add entries here for notable changes that modify existing behavior
  (see `CONTRIBUTING.md`).

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

### Security

- No security advisories yet. Security-relevant changes (for example the repo
  secret hygiene, `S01-11`) are recorded under `Added`; dedicated security
  entries use this section going forward (see `CONTRIBUTING.md`).
