# `docs/runbooks/` — operational runbooks

Recovery and troubleshooting procedures for alerts and failures: worker
hangs/stuck runs, Kafka outage during create-run, Redis down, DLQ redrive.
Every P0 alert has a runbook.

Runbooks reference real metrics, logs and APIs; they never suggest manual
database updates without a controlled path. Redrive respects idempotency.

The durable-execution **model** (run/step lease, fencing, recovery) lives in
[`../architecture/durable-execution.md`](../architecture/durable-execution.md)
(S07-17). These runbooks are the operational surface on top of it.

- **`workflow-recovery.md`** (S07-17) — worker hangs/stuck runs, crashed runs,
  approval-parked runs, and failed runs: what to look for (metrics/logs), how to
  confirm what happened (SQL/API), and how to act (cancel, retry, force a lease).
- **`local-auth.md`** (S04-14) — log in locally against the secured `/api/v1`
  API with a real Keycloak token: the machine-to-machine and the Authorization
  Code + PKCE flows, the `curl`/token commands, and issuer/audience
  troubleshooting.
- **`observability/*.md`** (S13) — observability alerts and recovery.

Scaffolded in **S01-03**. Durable execution design (`durable-execution.md`) and
the recovery runbook (`workflow-recovery.md`) are added in **S07-17**;
observability runbooks in **S13**.
