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

## Risks / ADR
- [ ] Tenant isolation, idempotency, retries and recovery considered
- [ ] No secret, no disabled test, no fake stub or empty TODO
- [ ] ADR needed for cross-module dependency, BOM bypass, persistence/migration, contract or invariant change (yes / no): __________

## Documentation
- [ ] README / CHANGELOG updated or `N/A`
- [ ] Contracts (OpenAPI / JSON Schema / AsyncAPI) updated or `N/A`
- [ ] Docs (`docs/`) updated or `N/A`

## Verification
<!--
Paste the exact commands run and their real outcome. State PASS / FAIL / not-run.
Never report a build, test or container as passing unless it actually ran.
-->
