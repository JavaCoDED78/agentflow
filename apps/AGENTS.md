# AGENTS.md — `apps/agentflow-app`

The single Spring Boot artifact of the platform. One image, several runtime roles. Modules live in `src/main/java/com/example/agentflow/<module>/` — each has its own `AGENTS.md`. General rules are in the root `AGENTS.md`.

## Runtime roles (one image, different profiles/properties)

- `api` — REST, authentication, validation, agent/workflow/document catalogue, run status. **Does not start Kafka consumers.**
- `workflow-worker` — consumer of workflow events, step execution, retries, checkpoints, lease recovery. **Does not expose controllers as a public entry point.**
- `ingestion-worker` — extract / chunk / embed / index. In the first release it may run as a worker profile of the same image.
- Profile names and the mechanism that enables components per role are ⚠ decided during implementation (S07, S10, S15-06) and recorded here and in Helm values. A role enables/disables listeners and schedulers (outbox publisher, recovery) explicitly, not "everywhere by default".
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

- `./gradlew clean check` — unit + architecture (Modulith/ArchUnit) + formatting; fast tests without Docker.
- `./gradlew integrationTest` — Testcontainers (PostgreSQL+pgvector, Kafka, Redis, Keycloak); the PostgreSQL image is the same as in Compose.
- One formatter; wrong formatting breaks `check`. Java 25 toolchain is explicit, no implicit system JDK.
- New module/package → add an `AGENTS.md` and update `docs/architecture/module-map.md`.
