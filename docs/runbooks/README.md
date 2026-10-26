# `docs/runbooks/` — operational runbooks

Recovery and troubleshooting procedures for alerts and failures: worker
hangs/stuck runs, Kafka outage during create-run, Redis down, DLQ redrive.
Every P0 alert has a runbook.

Runbooks reference real metrics, logs and APIs; they never suggest manual
database updates without a controlled path. Redrive respects idempotency.

- **`local-auth.md`** (S04-14) — log in locally against the secured `/api/v1`
  API with a real Keycloak token: the machine-to-machine and the Authorization
  Code + PKCE flows, the `curl`/token commands, and issuer/audience
  troubleshooting.
- **`durable-execution.md`** (S07-17) — worker hangs/stuck runs and recovery.
- **`observability/*.md`** (S13) — observability alerts and recovery.

Scaffolded in **S01-03**. Durable execution design and recovery runbook are added
in **S07-17**; observability runbooks in **S13**.
