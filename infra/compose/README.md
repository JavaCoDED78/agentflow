# `infra/compose/` — local infrastructure as code

Docker Compose files for the runtime stack: PostgreSQL (+ pgvector), Kafka
(KRaft), Redis, MinIO and Keycloak, organised by Compose profiles
(`core`, `messaging`, `identity`, `ai-local`, `observability`, `full`).

Images are pinned to immutable tags. Secrets are never committed; local
credentials are kept out of the repository (see `.gitignore`).

Scaffolded in **S01-03**. Services are added in **S01-12 – S01-16**; profiles are
defined in **S01-16**.
