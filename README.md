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

## Local environment

Copy `.env.example` to `.env` for local development. `.env` is ignored by Git;
this file contains only `CHANGEME` placeholders and localhost defaults, and real
secrets are read from the environment or a secret store — never from Git
(`S01-11`).

The minimum runtime stack is one Docker Compose profile (PostgreSQL + pgvector,
`S01-12`):

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
```

## Documentation map

- General rules: [`AGENTS.md`](AGENTS.md)
- Module docs: [`docs/AGENTS.md`](docs/AGENTS.md), [`apps/AGENTS.md`](apps/AGENTS.md)
- Product boundaries: [`docs/product-scope.md`](docs/product-scope.md)
- Changelog: [`CHANGELOG.md`](CHANGELOG.md)
