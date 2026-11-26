# Load tests — k6

[`create-run-status.k6.js`](create-run-status.k6.js) is the load scenario for the
create-run + status polling path (`S14-12`, P0). It is deliberately a small,
fixed-parameter scenario — the platform's production-readiness numbers come from
`S14-13` (baseline environment) and `S14-14` (p50/p95/p99 and saturation), which
will measure this scenario and record the results. This directory is the source
of truth for **where the scenarios live and how to run them**, not for any
performance claim.

## What the scenario does

Per iteration:

1. **Creates** a run — `POST /api/v1/runs` with a **unique** `Idempotency-Key`
   (RFC 4122 v4 UUID, generated in-script) and the fixed payload
   `{"workflowId": "<WORKFLOW_ID>"}`. `202 Accepted` is the success path; any other
   status is counted as a rejected create and the iteration ends.
2. **Polls** the run — `GET /api/v1/runs/{id}` every `POLL_INTERVAL_SEC` tick
   until the run reaches a terminal state (`SUCCEEDED`, `FAILED`, `CANCELLED`)
   or the **polling horizon** (`MAX_POLLS` ticks) elapses.
3. Records the outcomes as k6 metrics (see below); the machine-readable export
   carries all of them.

The fixed dataset is the scenario's own parameters — one pinned workflow id, one
fixed payload shape, fixed scenario defaults. The only per-iteration value is the
idempotency key, and it must be unique: a run whose key collides with an earlier
run would replay the original run instead of creating a new one.

**No LLM is called.** The default scenario points at an API running with the
`local` profile and no worker attached, so every run stays `QUEUED` and every
iteration ends at the polling horizon — the scenario measures the HTTP path
(create + status-read latency, error rate), not workflow duration. A run that
actually executes steps is an operator choice (a workflow-worker profile and a
worker-capable workflow); this scenario never invokes a model, and the README
commands below assume the workerless API.

## Configuration (env vars, `-e KEY=VALUE`)

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | Target API base URL |
| `WORKFLOW_ID` | _(required)_ | UUID of the **active** workflow owned by the local tenant — the dataset |
| `VUS` | `5` | Constant VU count |
| `DURATION` | `60s` | Scenario duration |
| `POLL_INTERVAL_SEC` | `0.5` | Poll tick (seconds) |
| `MAX_POLLS` | `120` | Polling horizon in ticks (~60s at the default tick) |

`WORKFLOW_ID` is required — the script refuses to start without it.

## Prerequisites

- **k6** installed (`k6 version`; the scenario was verified against k6 v2.2.0).
- **The API running** with the `local` profile — see the root `README.md`
  (`./gradlew :apps:agentflow-app:bootRun --args='--spring.profiles.active=local'`).
- **An active workflow in the local database.** The HTTP API has no
  version-create endpoint (only `POST /api/v1/workflows` for definitions and
  `POST .../versions/{versionId}/activate` for activation) — a draft version is
  created through the service layer or an integration test. Without an active
  workflow every create is a `404 not_found` (counted as a rejected create), so
  the scenario is not evidence of the happy path.

## Running

```bash
# one-time preparation: the export path's parent directory must exist,
# k6 silently skips the export otherwise
mkdir -p docs/load-tests/results

k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e WORKFLOW_ID=<fixed workflow UUID> \
  --summary-export=docs/load-tests/results/create-run-status.summary.json \
  docs/load-tests/create-run-status.k6.js
```

Overrides for a different load shape (reproducible only when recorded — the
defaults are the fixed dataset):

```bash
k6 run -e VUS=20 -e DURATION=2m -e MAX_POLLS=40 -e POLL_INTERVAL_SEC=0.25 \
  -e BASE_URL=http://localhost:8080 -e WORKFLOW_ID=<fixed UUID> \
  --summary-export=docs/load-tests/results/create-run-status.summary.json \
  docs/load-tests/create-run-status.k6.js
```

## Machine-readable output

`--summary-export` writes the k6 summary JSON. It carries every custom metric
below, so terminal outcomes, rejected creates and the horizon are all present:

| Metric | Type | Meaning |
|---|---|---|
| `agentflow_load_create_latency_ms` | Trend | `POST /api/v1/runs` latency (ms) |
| `agentflow_load_poll_latency_ms` | Trend | `GET /api/v1/runs/{id}` latency (ms) |
| `agentflow_load_creates_total` | Counter | Number of creates |
| `agentflow_load_creates_rejected` | Counter | Creates that were not accepted (non-202 or no run id) |
| `agentflow_load_polls_total` | Counter | Status reads issued |
| `agentflow_load_runs_succeeded` | Counter | Runs that reached `SUCCEEDED` |
| `agentflow_load_runs_failed` | Counter | Runs that reached `FAILED` |
| `agentflow_load_runs_cancelled` | Counter | Runs that reached `CANCELLED` |
| `agentflow_load_runs_horizon` | Counter | Runs that left the polling horizon or could not be read |

These are **load-test** metrics, not app telemetry — they are not part of the
`agentflow.*` catalogue (`docs/observability/metrics.md`).

The `docs/load-tests/results/` directory is git-ignored (generated output); the
command above writes one file there.

## What this scenario does not prove

- **No performance baseline** — it is not a benchmark. Latency numbers are for a
  smoke run; the baseline environment (CPU/RAM/image versions) is `S14-13` and the
  latency/throughput/saturation numbers are `S14-14`. Neither has been measured;
  nothing in this directory is a performance claim.
- **No workflow duration** — without a worker attached, every run stays `QUEUED`
  and the polls end at the horizon. Full workflow duration is a different
  measurement.
- **No terminal-status distribution** — a `SUCCEEDED`/`FAILED`/`CANCELLED` split
  is only observable against a worker-capable deployment whose workflows
  actually execute.
