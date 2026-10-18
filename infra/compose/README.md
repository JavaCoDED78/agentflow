# `infra/compose/` — local infrastructure as code

Docker Compose files for the runtime stack: PostgreSQL (+ pgvector), Kafka
(KRaft), Redis, MinIO and Keycloak, organised by Compose profiles
(`core`, `messaging`, `identity`, `ai-local`, `observability`, `full`).

Images are pinned to immutable tags. Secrets are never committed; local
credentials are kept out of the repository (see `.gitignore`).

Scaffolded in **S01-03**. The core profile's PostgreSQL (S01-12) and Redis
(S01-13) services are present now; Kafka (S01-14) and MinIO/Keycloak (S01-15)
are added in the following tasks, and the profiles are fully defined in
**S01-16**.

## Running the core profile

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
docker compose --file infra/compose/docker-compose.yml --profile core ps
```

The `postgres` service becomes `healthy` through its healthcheck and keeps data
across restarts via the `agentflow-pgdata` named volume. The `redis` service
(S01-13) also reaches `healthy` via `redis-cli ping`; it persists local
development data through an append-only file (AOF, `appendfsync everysec`) on the
`agentflow-redis` named volume, so it survives a restart. Redis is a cache /
short-lived coordination store, never a source of truth — workflow state lives in
PostgreSQL, so losing Redis must not lose a run. No auth is configured on the
trusted local network; production (Kubernetes) must enable `requirepass` from a
Secret. Stop and remove both with `docker compose ... down` (add `-v` to drop the
volumes too). Real credentials come from a local `.env` (git-ignored); the
defaults are local-only and clearly marked.
