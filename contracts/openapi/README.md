# `contracts/openapi/` — HTTP API contract

OpenAPI specification for the REST API (`/api/v1`). The spec is generated
deterministically and checked in CI; breaking changes require an explicit
baseline update.

## Generation

The file is generated **from the running application** by the Gradle task
[`generateOpenApiContract`](../../apps/agentflow-app/build.gradle.kts), which runs
[`OpenApiContractTool`](../../apps/agentflow-app/src/main/java/com/example/agentflow/tooling/OpenApiContractTool.java):
it boots the app on the default profile (no database required), waits for the
live `/api/v1/v3/api-docs` endpoint, fetches the description and writes a
*normalized* copy. Normalization drops the volatile ephemeral-port `servers`
entry, so the committed baseline is byte-for-byte reproducible and a diff ever
shows only a real change.

**Never edit `openapi.json` by hand.** Regenerate the file instead; a hand edit
would be overwritten on the next generation and would not survive a reviewer
asking "where did this come from?". The generation order is stable, so the
committed file is the source of truth for the contract, not a merge target.

## Breaking-change approval flow

The contract gate (`OpenApiContractCheckTest`, run by `./gradlew check`) compares
the checked-in baseline against the freshly generated spec and fails the build on
any *breaking* change: a removed path or HTTP method, a removed required
property, a property `type` change, or a removed `enum` value. Compatible changes
(added path/method, added property, added response) are reported but do not fail.

When a change is intended — including an intentional breaking one — approve it
deliberately rather than letting the gate fail silently:

1. Regenerate the baseline: `./gradlew generateOpenApiContract`.
2. Review the diff of `contracts/openapi/openapi.json`.
3. Record the change in `CHANGELOG.md` (`Unreleased` → `Added`/`Changed`), and
   an ADR if the contract shape changed in a way the team should remember.
4. Open the PR; the gate is green on the refreshed baseline.

Scaffolded in **S01-03**. The specification and security schemes are added in
**S03-12**.
