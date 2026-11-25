# Sprint 14 — Resilience, security testing and load baseline

Detailed per-task changelog records for the resilience/security/load thread. Slim one-line summaries with links are in the root [`CHANGELOG.md`](../../CHANGELOG.md).

## Contents

- [S14-01 — failure matrix](#s14-01--failure-matrix)

---

## S14-01 — failure matrix

### What changed

The platform now has a single operational record of how each critical dependency fails — [`docs/testing/failure-matrix.md`](../testing/failure-matrix.md). One outage scenario per critical dependency, with the failure injection, the expected behaviour, the recovery target, the observable metric/alert and the concrete evidence (test / runbook / live check) behind each claim. The six critical dependencies covered are: **PostgreSQL**, **Kafka**, **Redis**, **object storage** (Garage / S3), the **AI provider** and the **worker role** (workflow-worker / ingestion-worker).

Each component is documented in a compact overview table plus a per-component section with the six required columns. Two gaps are recorded in the matrix's [Known gaps](../testing/failure-matrix.md#known-gaps) instead of being papered over: the dedicated live PostgreSQL-outage integration test is `S14-07`, and the object-storage client is not wired yet (`ADR-014`), so the S3 outage path is verified only up to the port boundary — no live `docker stop agentflow-garage` behaviour is asserted.

### Why

Sprint 14's gate is "отказоустойчивость покрыта automated tests" — every critical dependency must have an outage scenario someone can check. The matrix is the honest map of what is proven today (atomicity, idempotency, fail-open limiter, bounded retries, lease recovery) and what is still planned (live PostgreSQL outage, a wired object-storage client), so each `S14-*` task closes its row rather than the matrix claiming a guarantee it cannot show. No code or contract changed — documentation only, so `CHANGELOG.md` records the documentation addition and no runtime behaviour.

### Verification

Documentation-only change; no build or test gate applies to a new markdown file. The verification is the documentation-verification procedure:

- `git diff --check` — whitespace / final newline / trailing-whitespace.
- Every relative in-document link in the new/touched files was resolved against the file that owns it (the documentation-verification resolver) — no dead links; planned targets are plain code paths with a `⚠ planned` badge, never links.
- `git status --short` — the change touched only the new doc, the index, the devlog and `CHANGELOG.md`.

Every cited evidence test was inspected in the tree before being cited (name and package confirmed), and every metric / alert UID, threshold and `for` duration in the matrix was taken from `infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml` and `docs/observability/metrics.md` — none is invented.

### Documentation

- [`docs/testing/failure-matrix.md`](../testing/failure-matrix.md) — new: the matrix (overview table + per-component sections + known gaps).
- [`docs/README.md`](../README.md) — the `testing/` tree entry and the two table rows (the failure matrix, and the `S14-17` test-strategy marker).
- [`docs/devlog/sprint-14-resilience.md`](sprint-14-resilience.md) — this file.
- [`docs/devlog/README.md`](README.md) — the new sprint devlog link.
- `CHANGELOG.md` — Unreleased / Added entry.

### Scope / follow-up

The matrix is deliberately not a test suite: each row is an honest "proven / unproven" statement, and each `S14-*` task (`S14-02` … `S14-08`) closes its own row — `S14-07` for the PostgreSQL outage scenario, the Sprint 10 object-storage client work for the Garage outage scenario, `S14-03` for cross-tenant regression and `S14-08` for the Redis per-function outage table. The `S14-17` test strategy document (`docs/testing/README.md`) will be the entry point that consolidates this matrix with the rest of the testing strategy.
