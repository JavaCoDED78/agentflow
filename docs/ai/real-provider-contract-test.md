# Real provider contract test (S09-12)

The OpenAI adapter contract — response mapping, token usage, structured output and timeout
classification — is verified against a **live** provider by `RealProviderContractTest`
(`apps/agentflow-app/src/test/java/com/example/agentflow/ai/provider/RealProviderContractTest.java`).
The offline counterpart (what every PR runs) is the deterministic `FakeModelProvider` fixture
suite (S09-11); this suite proves the real API has not drifted from the mapping the fake stands in
for.

## Opt-in, never on the standard gate

The suite is tagged `real-provider` and is **excluded** from the standard `test` task (and
therefore from `./gradlew check`), so the everyday gate stays free, offline and deterministic — it
needs neither an API key nor a network connection. It runs only through the dedicated Gradle task,
and only when a key is present:

```bash
# manual run
OPENAI_API_KEY=... ./gradlew :apps:agentflow-app:realProviderContractTest

# without OPENAI_API_KEY the task is SKIPPED with an explicit reason — it never fails
# a machine that simply has no key configured
```

There is no CI workflow wired to this task in the repository on purpose: schedule it in your own
cron / CI with the key injected from the secret store, and make the job fail when the report
contains skipped or failed tests (a scheduled run that silently skips proves nothing).

## Configuration

| Setting | Env | System property | Default |
|---|---|---|---|
| Provider API key | `OPENAI_API_KEY` | `agentflow.contract-test.api-key` | — (required) |
| Model id | `AGENTFLOW_CONTRACT_TEST_MODEL` | `agentflow.contract-test.model` | `gpt-4o-mini` |
| Base URL (compatible gateway) | `OPENAI_BASE_URL` | `agentflow.contract-test.base-url` | OpenAI public endpoint |
| Overall-timeout bound (ms) | `AGENTFLOW_CONTRACT_TEST_TIMEOUT_MILLIS` | `agentflow.contract-test.timeout-millis` | `500` |

The key comes from the environment / secret management only — never from the repository, build
arguments, logs or the report. The test prompts are fixed, non-sensitive fixtures.

## What it verifies

| Check | Assertion against the live provider |
|---|---|
| `maps_a_real_completion_into_the_contract` | A plain chat completion maps to the neutral `ChatResponse`: non-null content, the provider-reported model and finish reason, and `ChatUsage` with positive input/output counts whose total matches the sum. |
| `maps_structured_output_into_the_contract` | A request carrying a neutral `outputSchemaJson` renders into the provider's structured-output form; the completion parses as JSON conforming to the schema (the Planner's S09-05 seam end to end). |
| `an_overall_timeout_against_a_real_in_flight_call_is_classified_as_timeout` | The production `ResilientChatModel` aborts a real in-flight call (a forced ~150-token generation cannot finish inside the bound) and the production `ProviderExceptionClassifier` maps the abort to `TIMEOUT` (retryable). |

The happy-path checks boot the real `OpenAiChatModelConfiguration` (adapter + resilience layer)
through `ApplicationContextRunner`, so the rendering/mapping under test is exactly what production
runs.

Notes:

- The overall-timeout seam is used rather than a millisecond SDK read timeout: the latter also
  interrupts the TLS handshake on some network paths (classified `CONNECTION`), which is
  environment noise. SDK-level read-timeout classification stays covered offline by
  `ProviderExceptionClassifierTest` and the fake `TIMEOUT` fixture.
- With an invalid key the suite fails loudly from the provider's real error (for example a 401 or
  a geo-block 403) — that is the contract test working as intended, not flakiness.

## Report

The report is the standard Gradle test report:

- JUnit XML: `apps/agentflow-app/build/test-results/realProviderContractTest/`
- HTML: `apps/agentflow-app/build/reports/tests/realProviderContractTest/index.html`

A green run reports `tests=3, failures=0, errors=0, skipped=0`. The suite never logs prompts,
responses or the key; the only recorded failure data is the provider's exception class and status.

## Cost

Three calls per run, each a few tokens on `gpt-4o-mini` (the forced long generation of the timeout
check is aborted client-side after the bound). Cost per run is negligible; run it on a schedule,
not per commit.
