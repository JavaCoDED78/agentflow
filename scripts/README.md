# `scripts/` — operational scripts

Small, reproducible scripts for local verification: smoke and health checks that
run the base stack from a clean checkout, dependency readiness checks and CI
helpers.

Scaffolded in **S01-03**. The smoke/health script is added in **S01-20**.

## `smoke-local.sh` (S01-20)

The Sprint 1 smoke test. Starts a Compose profile (default `core`), waits for
every service in that profile to become `healthy` through its compose healthcheck,
prints a per-service summary and tears the stack down. Verify the base stack from
a clean checkout:

```bash
bash scripts/smoke-local.sh                       # core, then down
bash scripts/smoke-local.sh --profile full        # all services, then down
bash scripts/smoke-local.sh --keep                # leave the stack running
bash scripts/smoke-local.sh --help
```

Defaults to the `core` profile (PostgreSQL + pgvector, Redis, Garage) — the
minimum runtime stack. `messaging`/`full` need a one-time `KAFKA_CLUSTER_ID`,
which the local `infra/compose/.env` provides. The script exits non-zero when any
service does not become healthy, so it is a CI smoke gate.
