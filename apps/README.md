# `apps/` — application sources

Application code lives here.

- `agentflow-app/` — one Spring Boot artifact with several runtime roles
  (`api`, `workflow-worker`, `ingestion-worker`), started with Gradle profiles.
- `agentflow-mcp-lab/` — optional isolated MCP demo server (later sprint).

Modules are packages inside `agentflow-app`, not Gradle subprojects. Business
logic stays out of controllers, Kafka listeners and infrastructure adapters;
module boundaries are enforced automatically (see `docs/architecture/`).

Scaffolded in **S01-03**. Application skeleton and Spring Boot wiring are added in
**S01-07 – S01-09**.
