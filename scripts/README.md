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

## `observability-clean-start-test.sh` (S13-20)

The observability clean-start test. Drops the observability stack's state volumes
(Grafana sqlite, Prometheus TSDB, Loki chunks/index, Tempo blocks), brings the
stack back up and verifies over the Grafana API that everything is re-provisioned
from Git — every datasource, dashboard and alert rule is present, read-only /
file-provenance, and a manual UI edit of a provisioned dashboard is refused:

```bash
bash scripts/observability-clean-start-test.sh
```

The expected surfaces are read from the Git files (`infra/observability/grafana/`),
never hardcoded, so a new dashboard/datasource/rule added to Git is automatically
checked. Exits non-zero on any failure. It wipes and recreates the named volumes
(`agentflow-observability`, `agentflow-prometheus`, `agentflow-loki`,
`agentflow-tempo`) — the dev/demo stack carries no durable data. See
[`infra/observability/README.md`](../infra/observability/README.md) for the S13-20
acceptance record.
