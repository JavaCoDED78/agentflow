# AGENTS.md — `apps/agentflow-app`

The single Spring Boot artifact of the platform. One image, several runtime roles. Modules live in `src/main/java/com/example/agentflow/<module>/` — each has its own `AGENTS.md`. General rules are in the root `AGENTS.md`.

## Runtime roles (one image, different profiles/properties)

- `api` — REST, authentication, validation, agent/workflow/document catalogue, run status. **Does not start Kafka consumers.**
- `workflow-worker` — consumer of workflow events, step execution, retries, checkpoints, lease recovery. **Does not expose controllers as a public entry point.**
- `ingestion-worker` — extract / chunk / embed / index. In the first release it may run as a worker profile of the same image.
- Profile names and the per-role enabling mechanism are now implemented and recorded in Helm values: the `api` role does not start consumers, the `workflow-worker` role runs the step loop with lease recovery (`agentflow.worker.loop-enabled` / `agentflow.worker.recovery-enabled=true`), and `ingestion-worker` runs the extract / chunk / embed / index pipeline. Listeners and schedulers (outbox publisher, recovery) are enabled per role via `@ConditionalOnProperty` — `agentflow.worker.loop-enabled`, `agentflow.worker.recovery-enabled`, `agentflow.worker.ingestion-loop-enabled` (S10-13, the document indexing consumer, default off), `agentflow.kafka.producer.enabled` — explicitly, not "everywhere by default" (outbox/inbox S06, worker S07, ingestion S10-13).
- Environments: `local`, `test`, `kubernetes`. `test`-only profiles (mock auth, fake AI provider) never reach `kubernetes`/production.

## Configuration

- Only typed `@ConfigurationProperties` with validation. A missing required parameter → a clear startup failure. No hardcoded `localhost`.
- Secrets come from env/secret references; never in Git, build args, logs or Actuator.
- Lease/heartbeat/recovery, retry, budgets and upload limits live in configuration, not in code.
- Actuator: expose only the needed health/info; liveness and readiness separately; `env`, `configprops`, `beans`, `mappings` closed; the metrics scrape endpoint reachable only on the internal network.
- Graceful shutdown is mandatory (SIGTERM): a worker stops claiming new runs, gives the current step bounded time, and stops heartbeating.

## Resources and migrations

- Migrations: one tool, forward-only, expand/contract for incompatible changes; never edit an applied migration. Rules — `docs/architecture/database-migrations.md`, conventions — `docs/architecture/database-conventions.md`. Every table belongs to one module; this must be obvious from the migration name/comment.
- Migrations run as one controlled Job/init phase, not concurrently from several replicas.
- Prompt templates live in `src/main/resources` with a version/hash (see `ai.prompt`).
- Logs are JSON on stdout with `traceId`/`spanId`; never print JWTs, API keys or secrets.

## Tests and build

Source sets (`apps:agentflow-app/build.gradle.kts`): `test` (unit, classes named `*Test`, DB-free, fast) and `integrationTest` (classes named `*IT`, Testcontainers on the real PostgreSQL/pgvector, Kafka, Redis and Keycloak images — the same images Compose pins). The `integrationTest` task does **not** run under `check`.

- `./gradlew check` — unit + architecture (Modulith/ArchUnit boundary + no-cycles) + formatting (`spotlessCheck`) + OpenAPI contract gate; fast, no Docker.
- `./gradlew integrationTest` — the full `*IT` Testcontainers suite.
- One module at a time: `./gradlew :apps:agentflow-app:test --tests '*workflowexecution*'` and `./gradlew :apps:agentflow-app:integrationTest --tests '*workflowexecution*'`.
- Architecture tests live in `src/test/java`; a boundary or cycle change breaks `check`, so fix the dependency, never exclude the test.
- One formatter (Google Java Format via Spotless); wrong formatting breaks `check`. Run `./gradlew spotlessApply` after editing Java. Java 25 toolchain is explicit, no implicit system JDK.

## New module checklist

Adding a module `<module>` under `com.example.agentflow.<module>`:

- Create the package (`domain` / `application` / `persistence` / `worker`, and `web` if it exposes REST) with its own `AGENTS.md`.
- Own exactly one set of tables/repositories — one migration under `classpath:migrations`, named and commented with the owner — and expose a root-package public API; never import another module's repositories, entities or internal packages.
- Update `docs/architecture/module-map.md` (ownership + allowed dependencies). Add an architecture test if a new boundary or dependency edge appears; an ADR if a rule above changes.
- Log the change in `docs/devlog/sprint-XX-*.md` and add a `CHANGELOG.md` entry; update this `AGENTS.md` when the module adds commands, permissions or events.
