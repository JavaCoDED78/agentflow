# `infra/compose/` — local infrastructure as code

Docker Compose files for the runtime stack: PostgreSQL (+ pgvector), Kafka
(KRaft), Redis, MinIO and Keycloak, organised by Compose profiles
(`core`, `messaging`, `identity`, `ai-local`, `observability`, `full`).

Images are pinned to immutable tags. Secrets are never committed; local
credentials are kept out of the repository (see `.gitignore`).

Scaffolded in **S01-03**. The core profile's PostgreSQL service (S01-12) is
present now; Kafka, Redis, MinIO and Keycloak are added in **S01-13 – S01-15**,
and the profiles are fully defined in **S01-16**.

## Running the core profile

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
docker compose --file infra/compose/docker-compose.yml --profile core ps
```

The `postgres` service becomes `healthy` through its healthcheck and keeps data
across restarts via the `agentflow-pgdata` named volume. Stop and remove it with
`... down` (add `-v` to drop the volume too). Real credentials come from a local
`.env` (git-ignored); the defaults are local-only and clearly marked.
