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
- **`dlq-redrive.md`** (S12-12) — when and how to redrive a held DLQ message: the fresh-id
  re-publish, the inbox `409` guard, idempotency, the audit record, how to confirm it landed,
  and when to escalate.
- **`redis-outage.md`** (S12-21) — what a Redis outage looks like, how to confirm it (health
  groups, fail-open limiter), what is safe to do (restart Redis, leave the app running, never
  flush) and when recovery is complete.
- **The nine observability alert runbooks** (S13): **`observability-scrape-target-down.md`** (S13-06) and the eight operational runbooks — **`api-error-rate.md`**, **`db-saturation.md`**, **`outbox-age.md`**, **`kafka-lag.md`**, **`expired-leases.md`**, **`provider-failures.md`**, **`ingestion-failures.md`** (S13-17) and **`redis-outage.md`** (S13-22). Every runbook carries the impact, the diagnostic queries, the probable causes, the safe mitigations, the recovery criterion and an idempotency-aware redrive/rollback note (standardized in **S13-18**); every rule is provisioned from Git with a `runbook_url` annotation and every runbook links back to its rule and the upstream docs.

Scaffolded in **S01-03**. Durable execution design (`durable-execution.md`) and
the recovery runbook (`workflow-recovery.md`) are added in **S07-17**;
the DLQ redrive runbook (`dlq-redrive.md`) in **S12-12**;
the Redis outage runbook (`redis-outage.md`) in **S12-21**;
observability runbooks in **S13**.
