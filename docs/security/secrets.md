# Provider secrets (S09-03)

> Owner: the `ai` module (provider configuration) and `observability` (log masking).
> Governing invariants: `ADR-006` (the model is an untrusted planner, never given authority over its own credentials), invariant 8 (secrets never land in a row, log, metric, trace, Kafka payload or prompt), invariant 10 (no secrets in Git, build args, logs, metrics or traces).

A provider API key (`OPENAI_API_KEY`, or another provider's equivalent) is the single credential the
platform needs to call an external model. `S09-03` closes the three ways it could leak: into **Git /
build**, into the **logs / stack traces / diagnostic endpoints**, and into the **image build**. The
key is read from the environment or a secret store, is opt-in, and is masked before anything is
written to stdout.

## Where the key lives

The key is **injected from outside the image**, never baked into the code or the config file:

- It is read from `agentflow.ai.provider.openai.api-key`, which falls back to the `OPENAI_API_KEY`
  environment variable — see [`OpenAiChatModelConfiguration`](../apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/OpenAiChatModelConfiguration.java).
- The whole provider configuration is **opt-in**: the beans exist only when
  `agentflow.ai.provider=openai` (`@ConditionalOnProperty`). On the default, `test` and `local`
  profiles without that property there is **no** `ChatModel` bean and **no** key on the classpath, so
  the core app boots exactly as it did before the AI layer existed.
- The base URL and model id are configurable with **documented placeholders** (`gpt-4o-mini`), never
  real credentials. A missing key does not fail boot — the client resolves `OPENAI_API_KEY` from the
  environment at call time and the provider rejects an unauthenticated call, which is the correct
  fail-fast for a missing secret.
- In Compose and Helm the value arrives through an environment placeholder
  (`${OPENAI_API_KEY:-...}`) from a local `.env` / a Kubernetes Secret, never from a committed
  value. The repository holds only placeholders.

### Local (development) storage

For local runs the key lives in a **git-ignored** `.env` file or the operating-system keychain /
secrets manager — never in a tracked file, never in the editor. The compose defaults you can see in
`infra/compose/*.yml` (for example the local M2M client secret, `local-dev-m2m-secret-rotate-me`)
are local-only, clearly marked, and are **not** production secrets. Rotate any real value into a
Kubernetes Secret / external secret store before a real deployment.

## Never in logs, stack traces or Actuator

Two independent layers keep a key from leaving the process:

1. **Log masking.** [`SecretRedactingValueMasker`](../apps/agentflow-app/src/main/java/com/example/agentflow/observability/SecretRedactingValueMasker.java)
   is registered as the `<valueMasker>` in [`logback-spring.xml`](../apps/agentflow-app/src/main/resources/logback-spring.xml).
   The encoder calls it for every field value, so a secret embedded in a longer message (for example
   `provider api_key=<key>`) is masked to `***` before it is serialized to structured JSON on stdout
   — and therefore never reaches Loki, disk, or the collector. Since `S09-13` it also masks the whole
   value of a sensitive prompt / document field (`documentBody`, `prompt`, `systemPrompt`,
   `userContent`, the injected `messages`), so none of that untrusted content — a canary included —
   reaches the collector.
2. **Actuator exposure.** Only `health` and `info` are reachable over HTTP; the sensitive `env`,
   `configprops`, `beans` and `mappings` endpoints are outside the exposure allow-list and answer
   `404` anonymously (see [`application.yml`](../apps/agentflow-app/src/main/resources/application.yml)). A configured
   key therefore cannot be read back through the management surface.
3. **Stack traces.** Unhandled errors are rendered as an `internal`/500 Problem Details without the
   raw exception message (`GlobalExceptionHandler`), so an exception that happens to quote a secret
   does not leak it.

## Never in image build args

A secret must not be passed with `docker build --build-arg SECRET=…` (it survives in the image layer
and in `docker history`). The application image is **not yet built** — the Dockerfile is a later task
(no `Dockerfile` exists in the repository yet), and the current manifests (`infra/compose`,
`infra/helm`) inject secrets through runtime environment variables / Secrets only. When the image is
added, the key must reach the container through a runtime env var or a secret mount, never through a
build-time ARG.

## Canary guard

`S09-03` ships a regression guard that proves the acceptance criterion — *a canary API key does not
appear in Git, logs, or diagnostic endpoints* — with real code:

- The canary is a unique, clearly-fake token stored in exactly one place:
  [`src/test/resources/secrets/canary-openai-key.txt`](../apps/agentflow-app/src/test/resources/secrets/canary-openai-key.txt).
  It is read from there at test time, so it is never written into a source file.
- [`CanaryLeakScanTest`](../apps/agentflow-app/src/test/java/com/example/agentflow/ai/security/CanaryLeakScanTest.java)
  (DB-free): runs `git ls-files`, scans every tracked file except the fixture, and asserts the
  canary is nowhere in Git; it also asserts the canary is masked by the real `ValueMasker`.
- [`ProviderSecretsActuatorTest`](../apps/agentflow-app/src/test/java/com/example/agentflow/ai/security/ProviderSecretsActuatorTest.java)
  (`@SpringBootTest`, no provider configured): asserts the canary is not echoed by any reachable
  `/actuator` endpoint and that `env` / `configprops` are closed.

If a developer ever commits a real key (or the canary stand-in for it), `git ls-files` + the scan
fails `./gradlew check`.

## Redact the whole value of prompt / document fields (S09-13)

The provider key is one secret surface; the prompt and the document body are another — and they are
exactly where a secret (or a prompt-injection payload carrying one) is most likely to appear. Invariant 8
applies to them too. The masking above stops at the first whitespace, which is enough for a single-token
secret but not for a prompt or document value that can span many words — a document body, a paragraph, a
JSON `messages` payload — so the canary would otherwise survive inside them. `S09-13` extended
`SecretRedactingValueMasker` with a `PROMPT_CONTENT` rule that masks the whole value of a sensitive prompt
/ document field (`document`, `documentBody`, `prompt`, `systemPrompt`, `userContent`, the injected
`messages`) to end of value, not only up to the first whitespace. The safe observability fields (model,
latency, token count, safe error category) and a bare `promptVersion=<id>` identifier are left unchanged,
and the existing provider-key cases are untouched, so no `S09-03` guard is regressed.

The acceptance is pinned by `AiObservationRedactionTest` (`ai.security`, DB-free): the planted canary is
redacted from an `api_key` pair, a document body, every sensitive prompt field and the `messages` payload,
and survives nowhere in the safe observation. The `S09-03` `CanaryLeakScanTest` is unchanged and still
green.

## Relation to the other Sprint 9 tasks

- **`S09-01` provider port / `ADR-014`** — the domain calls the model through a neutral port and is
  never given a provider SDK or its credentials; the key stays in the opt-in provider config.
- **`S09-02` adapter** — `OpenAiChatModelConfiguration` is where the key is read, opt-in, from
  environment/secret.
- **`S09-04` prompt versioning** — a related hygiene rule: prompt templates are versioned/hashable and
  document content is never logged in full, so the untrusted payload side of invariant 8 is bounded too.

## Acceptance

> The canary API key is not found in Git, logs, or diagnostic endpoints.

- **Git** — verified by `CanaryLeakScanTest.the_canary_lives_only_in_the_sanctioned_git_fixture`:
  `git ls-files` over the whole tree, every path except the fixture scanned, canary absent.
- **Logs** — verified by `CanaryLeakScanTest.{the_canary_is_masked_*}` through the real
  `SecretRedactingValueMasker`, and already covered end-to-end by
  `LoggingJsonFormatTest.emits_structured_json_with_mdc_and_masks_secrets`.
- **Diagnostic endpoints** — verified by `ProviderSecretsActuatorTest` (canary absent from `/health`
  and `/info`; `/env` and `/configprops` closed), complementing `HealthEndpointsTest`.
