# `docs/runbooks/` — operational runbooks

Recovery and troubleshooting procedures for alerts and failures: worker
hangs/stuck runs, Kafka outage during create-run, Redis down, DLQ redrive.
Every P0 alert has a runbook.

Runbooks reference real metrics, logs and APIs; they never suggest manual
database updates without a controlled path. Redrive respects idempotency.

Scaffolded in **S01-03**. Durable execution design and recovery runbook are added
in **S07-17**; observability runbooks in **S13**.
