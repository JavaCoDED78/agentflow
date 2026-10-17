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

## Documentation map

- General rules: [`AGENTS.md`](AGENTS.md)
- Module docs: [`docs/AGENTS.md`](docs/AGENTS.md), [`apps/AGENTS.md`](apps/AGENTS.md)
- Product boundaries: [`docs/product-scope.md`](docs/product-scope.md)
- Changelog: [`CHANGELOG.md`](CHANGELOG.md)
