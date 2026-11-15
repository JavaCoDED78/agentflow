# `apps/` — application sources

Application code lives here.

- `agentflow-app/` — one Spring Boot artifact with several runtime roles
  (`api`, `workflow-worker`, `ingestion-worker`), started with Gradle profiles.
- `agentflow-mcp-lab/` — the isolated MCP demo server (S11-08, ADR-019): a separate
  Spring Boot artifact exposing one read-only fixture tool over Streamable-HTTP; no
  business-database credentials, run independently via
  `./gradlew :apps:agentflow-mcp-lab:bootRun`.

Modules are packages inside `agentflow-app`, not Gradle subprojects. Business
logic stays out of controllers, Kafka listeners and infrastructure adapters;
module boundaries are enforced automatically (see `docs/architecture/`).

Scaffolded in **S01-03**. Application skeleton and Spring Boot wiring are added in
**S01-07 – S01-09**.
