# AgentFlow

AgentFlow is a platform for building and reliably running AI workflows with
durable state, approved tools, RAG, human approval and observability. Its core
is a small in-house durable workflow engine — it is **not** a chat wrapper
around an LLM.

This file is the entry point and project map. Detailed documentation lives in
[`docs/`](docs/) and the full architecture and 16-week backlog in
[`AGENTFLOW_ARCHITECTURE_AND_PLAN.md`](AGENTFLOW_ARCHITECTURE_AND_PLAN.md).

## Repository layout

| Path | Contents |
|---|---|
| [`apps/`](apps/) | Spring Boot artifacts (one image, several runtime roles) |
| [`infra/`](infra/) | Docker Compose, Kubernetes/Helm, observability |
| [`contracts/`](contracts/) | OpenAPI, JSON Schema and AsyncAPI contracts |
| [`docs/`](docs/) | Architecture, ADRs, runbooks, threat model |
| [`scripts/`](scripts/) | Local smoke and helper scripts |

## Prerequisites

- **JDK 25.** The build pins the Java 25 toolchain for every module; the build
  fails explicitly if another JDK is used (see [`build.gradle.kts`](build.gradle.kts),
  plan task `S01-05`).
- **Gradle 9.8.1** via the wrapper. Always run `./gradlew`; do not rely on a
  system Gradle.

## Build

```bash
./gradlew help          # validate the build entry point
./gradlew build         # compile with the pinned Java 25 toolchain (once modules exist)
```

Gradle reproducibility (wrapper checksum + dependency locking, `S01-09`): a build
against a different dependency graph fails instead of drifting. Regenerate the
locks after any version change with `./gradlew --write-locks generateLockFiles`
and keep `./gradlew check` green.

## Formatting

Java sources are standardized with one formatter —
[Google Java Format](https://github.com/google/google-java-format), wired into
the build through the [Spotless](https://diffplug.github.io/spotless/) Gradle
plugin (`S01-10`). The `spotlessCheck` task is part of `./gradlew check`, so a
formatting violation fails the build:

```bash
./gradlew spotlessApply   # auto-format every Java source under src/ (S01-10)
./gradlew spotlessCheck   # fail the build if any source is not formatted
./gradlew check           # compile + tests + formatting gate
```

`googleJavaFormat()` enforces Google Java style — 2-space indentation, braces,
spacing and import order — and combined with
[`.editorconfig`](.editorconfig) (LF line endings, no trailing whitespace,
final newline) it standardizes indentation, imports, whitespace and line
endings. The target is every Java source under `src/` (main + test).

## Quickstart

Copy `.env.example` to `.env` for local development. `.env` is ignored by Git;
this file contains only `CHANGEME` placeholders and localhost defaults, and real
secrets are read from the environment or a secret store — never from Git
(`S01-11`).

```bash
cp .env.example .env
```

### 1. Start the base stack

The minimum runtime stack is the `core` Compose profile: PostgreSQL (+ pgvector,
FTS), Redis and Garage (S3-compatible object storage). Run it from the repo root:

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
```

Tear it down with `docker compose --file infra/compose/docker-compose.yml --profile core down`
(add `-v` to drop the volumes). The `full` profile (`--profile full`) starts every
service — Kafka, Keycloak, Ollama and the observability stack too. The `messaging`
profile needs a one-time `KAFKA_CLUSTER_ID`; the local `infra/compose/.env`
provides it, so `messaging`/`full` work out of the box. Per-service detail and the
remaining profiles are in [`infra/compose/README.md`](infra/compose/README.md).

### 2. Verify with the smoke test

[`scripts/smoke-local.sh`](scripts/smoke-local.sh) (`S01-20`) starts a profile,
waits for every service to become `healthy` through its compose healthcheck,
prints a per-service summary and tears the stack down. It is the Sprint 1 smoke
test — run it from a clean checkout:

```bash
bash scripts/smoke-local.sh                       # core, then down
bash scripts/smoke-local.sh --profile full        # all services, then down
bash scripts/smoke-local.sh --keep                # leave the stack running
bash scripts/smoke-local.sh --help
```

### 3. Run the API

Logs are structured JSON on stdout (S01-19): every event carries
`@timestamp`, `level`, `message`, `logger_name`, `thread_name` and the `service` /
`environment` MDC values, and secret-like values (Bearer tokens, API keys,
passwords, AWS key IDs) are masked to `***`. Run the API locally with the `local`
profile for `DEBUG` logging:

```bash
./gradlew :apps:agentflow-app:bootRun --args='--spring.profiles.active=local'
```

### Ports

| Service | Port | Profile |
|---|---|---|
| PostgreSQL (+ pgvector) | 5432 | core |
| Redis | 6379 | core |
| Garage (S3 API) | 3900 | core |
| Kafka | 9092 | messaging |
| Keycloak (OIDC) | 8083 | identity |
| Ollama (local LLM) | 11434 | ai-local |
| Observability (Grafana) | 3000 | observability |
| Observability (OTLP gRPC / HTTP) | 4317 / 4318 | observability |

Ports are configurable through `POSTGRES_PORT`, `REDIS_PORT`, `GARAGE_S3_API_PORT`,
`KAFKA_PORT`, `KEYCLOAK_PORT` and `OLLAMA_PORT` in a local `.env` (defaults above).
The rest of the placeholders are in [`.env.example`](.env.example).

### Environment variables

Spring Boot reads `.env` as environment variables / system properties through
typed `@ConfigurationProperties` with validation: a missing required value fails
startup explicitly (`S01-17`). The app connects to the compose services over
`localhost` (datasource, Redis, Kafka, Garage, Keycloak issuer) and exports
telemetry to the observability profile's OTLP endpoint. The Kafka KRaft
`KAFKA_CLUSTER_ID` is the only value that must be generated once; full reference
and the local-only defaults are in [`.env.example`](.env.example).

### Troubleshooting

- **`docker compose config` fails** — pin Compose images to immutable tags (never
  `latest`) and check for an unmerged change; the smoke test validates the file.
- **`KAFKA_CLUSTER_ID` is undefined** — the `messaging`/`full` profiles format a
  KRaft cluster on first start. Generate a cluster id once into
  `infra/compose/.env`:
  ```bash
  KAFKA_CLUSTER_ID=$(docker run --rm apache/kafka:4.3.1 /opt/kafka/bin/kafka-storage.sh random-uuid)
  ```
- **The API fails to start** — it expects the base stack up and the required
  values in `.env`; start the `core` profile first and read the logs (structured
  JSON, secrets masked).

## API

The `/api/v1` REST API is documented in full in
[`docs/architecture/api-conventions.md`](docs/architecture/api-conventions.md):
endpoints, pagination, RFC 9457 Problem Details, optimistic locking, the
tenant-scoped lookup policy and the procedure for changing the contract. The
machine-readable contract is generated deterministically and checked in CI — see
[`contracts/openapi/README.md`](contracts/openapi/README.md).

## Database

AgentFlow runs on PostgreSQL 18 (+ pgvector). The single persistence approach is
Spring Data JDBC with explicit, tenant-filtered SQL (no JPA + jOOQ side by
side); column, table, index and versioning conventions are in
[`docs/architecture/database-conventions.md`](docs/architecture/database-conventions.md).

## Migrations

Schema changes are forward-only Flyway SQL under `classpath:migrations`, applied
as one controlled step — never on startup (`spring.flyway.enabled=false`):

```bash
./gradlew flywayMigrate          # apply classpath:migrations to the configured DB
```

The rules and the single process (forward-only, expand/contract, one owning
module per migration) are in
[`docs/architecture/database-migrations.md`](docs/architecture/database-migrations.md).

## Tests

- `./gradlew check` — unit tests, the module-boundary and no-cycles architecture gates, the formatting gate (`spotlessCheck`) and the OpenAPI contract gate. Runs without Docker.
- `./gradlew integrationTest` — Testcontainers suites on the real PostgreSQL/pgvector, Kafka, Redis and Keycloak images (the same images the Compose profiles pin).

## Documentation map

- General rules: [`AGENTS.md`](AGENTS.md)
- Documentation index: [`docs/README.md`](docs/README.md) — where every topic lives (current and `planned`)
- Module docs: [`docs/AGENTS.md`](docs/AGENTS.md), [`apps/AGENTS.md`](apps/AGENTS.md)
- Product boundaries: [`docs/product-scope.md`](docs/product-scope.md)
- Contracts: [`contracts/`](contracts/) — OpenAPI, event schemas, AsyncAPI
- Changelog: [`CHANGELOG.md`](CHANGELOG.md)
