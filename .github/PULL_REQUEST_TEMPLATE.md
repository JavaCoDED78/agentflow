## Description
<!--
Describe the change and why it is needed. Reference the task id (for example
S06-02) and the plan section it belongs to. Keep the PR focused on ONE problem
and ONE outcome.
-->

## Task
- [ ] Task id and sprint
- [ ] One problem, one outcome

## Tests
- [ ] Unit tests for the changed behavior (state transitions, invariants, boundaries)
- [ ] Negative / failure / recovery path covered where relevant
- [ ] Integration test for DB / Kafka / Redis / security where relevant
- [ ] `./gradlew clean check` (or the relevant task) runs and passes

## Architecture review
<!--
Review the architectural impact before opening the PR. Applies to code, config,
migrations and contract changes; mark N/A only when a dimension is genuinely out
of scope for this change. Reference `docs/architecture/module-map.md` and the
sprint gate for the selected task.
-->
- [ ] **Module ownership** — the change stays within its owning module's tables, repositories and internal packages; cross-module calls use the public API and published events; no new dependency cycle.
- [ ] **Tenant scope** — every read, write and search is `(tenant_id, resource_id)` scoped; another tenant's resource is indistinguishable from a missing one; the tenant is derived from verified context, not the request body.
- [ ] **Retries & reliability** — transient vs. permanent errors are classified separately; retries are bounded with exponential backoff/jitter; external calls are persisted before the call and treated as at-least-once, never exactly-once.
- [ ] **Side effects** — external-facing calls are idempotent or fenced by lease/epoch; cancellation stops on a safe boundary; outbox/inbox and dedup are respected.
- [ ] **Audit** — actors, actions and outcomes for state-changing events are recorded as append-only without secrets.
- [ ] **Telemetry** — metrics and logs carry no secrets, full prompts or documents, and no high-cardinality labels (`tenantId`, `runId`, `userId`, `documentId`).
- [ ] **Integrity** — no disabled tests, no fake stubs or empty TODOs, no secret in code/config/examples, no auth/tenant bypass.
- [ ] **ADR required?** new module dependency, BOM bypass, persistence/migration change, contract change or invariant change → yes / no: __________

## Documentation
- [ ] Docs (`docs/`) updated or `N/A`
- [ ] CHANGELOG updated or `N/A`
- [ ] Contracts (OpenAPI / JSON Schema / AsyncAPI) updated or `N/A`

## Verification
<!--
Paste the exact commands run and their real outcome. State PASS / FAIL / not-run.
Never report a build, test or container as passing unless it actually ran.
-->
