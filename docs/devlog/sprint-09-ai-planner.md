# Changelog


# Sprint 9 — AI layer / Planner


## S09-04 — The versioned prompt catalogue: prompt templates are bundled as fixed, hashed policy files, separated from untrusted user content, and every model attempt records the prompt version/hash it planned against.


### What changed
Sprint 9 builds the AI layer on top of the durable workflow engine. Before `S09-04` the domain could
call the model (through the ports, `S09-01`) and the concrete adapter existed (`S09-02`), but there was
no controlled way to ship the model's *policy*: prompts were ad-hoc strings with no version identity and
no enforced split between the policy the backend controls and the untrusted content it forwards. `S09-04`
introduces the **prompt catalogue** as the smallest complete change that closes that gap and satisfies
the acceptance — *every model attempt indicates the prompt version/hash it planned against*.

Three pieces make it up:

- **Bundled, hashed templates.** The policy ships as a plain text resource at
  [`resources/ai/planner.txt`](apps/agentflow-app/src/main/resources/ai/planner.txt). It is a *fixed
  SYSTEM message*: the file content is the source of truth for the prompt hash, and the task plus any
  retrieved document fragments are appended by the backend as a separate, untrusted USER message
  (the prompt-injection surface).
- **`PromptCatalogue`** (`com.example.agentflow.ai.prompt`, DB-free): loads every declared prompt from
  {@code resources/ai/<name>.txt}, derives each {@code hash} as the SHA-256 of the file bytes, and
  renders a prompt with {@code build(name, userContent)} into exactly
  {@code [SYSTEM(policy), USER(userContent)]}. Policy and user content never share one message, so the
  model can never read its own policy as user content. The catalogue never logs, hashes or otherwise
  exposes the injected content, so the untrusted document content stays out of observability (invariant
  8). A missing template throws at construction, so an incomplete {@code ai.prompt} declaration stops
  the app booting (fail fast, like every other required configuration).
- **`PromptVersion`** (`com.example.agentflow.ai.application`, the provider-agnostic port from `S09-01`):
  the immutable contract record carrying {@code name / version / hash} — the observable surface of a
  prompt. It deliberately holds *no* prompt text and *no* user/document content. A model attempt carries
  one of these, so the prompt it planned against is recorded and traceable. The {@code version} is
  assigned in configuration (bump whenever the template changes); the {@code hash} is derived from the
  content, so a content change is always detectable even before the version is bumped.

The catalogue is wired from the {@code ai.prompt} configuration through {@link
com.example.agentflow.ai.prompt.AiPromptProperties AiPromptProperties}
({@code @ConfigurationProperties} + {@code @Validated} + {@code @Valid}) and
[{@link com.example.agentflow.ai.prompt.PromptConfiguration PromptConfiguration}]. Each entry declares a
{@code name} and a human-assigned {@code version}; {@code PromptRef} validates both with {@code @NotBlank},
so a blank {@code version} fails context startup as a {@code ConfigurationPropertiesBindException} — the
project's typed-configuration idiom, the same path every other required property uses (`S01-17`). The
template and declaration are pinned in
[{@code application.yml}](apps/agentflow-app/src/main/resources/application.yml):
{@code ai.prompt.prompts[0]: planner / version "1"}.


### Why
The model is an untrusted planner (`ADR-006`) and the injected task / retrieved-document fragments are
prompt-injection content: the security-critical boundary is that the *policy* the model must obey ships
from a controlled, versioned resource, never merged into — or indistinguishable from — the user input.
Without a catalogue there was no way to (a) record *which* policy a given attempt planned against, which
an audit / regression on a prompt change requires, or (b) make a prompt change detectable: a bare string
edited in code is an unreviewed, unversioned, unhashed drift. Bumping a declared {@code version} on a
content change, and deriving a {@code hash} over the file, turns a prompt edit into a diffable,
traceable, fail-fast-configured change — and the SYSTEM/USER split keeps the policy from being smuggled
back to the model as user content. The {@code PromptVersion} record lives in the `ai.application` port
(S09-01) rather than a provider adapter, so the attempt's prompt identity is recorded without coupling
the domain to a specific provider, and it carries no prompt or content so the per-attempt trace never
leaks them (invariant 8).


### Verification
Unit and architectural, DB-free, no model / API key / Kafka / system-clock dependency:

- **`PromptCatalogueTest`** (`ai.prompt`, 6 tests): loads the bundled `planner` template, asserts the
  derived {@code hash} equals the real SHA-256 of the template file bytes (not fabricated), that
  {@code build} produces exactly two messages with the SYSTEM content equal to the loaded template and the
  USER content equal to the injected string, that blank user content and an unknown prompt are rejected,
  and that an empty catalogue has no content.
- **`AiPromptConfigurationTest`** (`ai.prompt`, 3 tests, {@code ApplicationContextRunner}): the
  {@code ai.prompt.prompts[0]} binding produces a {@code PromptCatalogue} with the declared prompt and a
  64-char hash; a blank {@code version} fails startup through the real {@code @Validated} binding path
  ({@code ConfigurationPropertiesBindException}); an unknown prompt name fails startup with
  "prompt template not found on classpath".
- **`PromptVersionTest`** (`ai.application`, 7 tests): the record exposes exactly
  {@code name / version / hash}, and rejects a null/blank name, a blank version, and a non-hex / wrong-
  length hash.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.prompt.PromptCatalogueTest'
--tests 'com.example.agentflow.ai.prompt.AiPromptConfigurationTest'
--tests 'com.example.agentflow.ai.application.PromptVersionTest'` green — **16 tests, 0 failures (JUnit
XML checked)**; `:apps:agentflow-app:check` green (compilation, unit, architecture gates, spotless
formatting, OpenAPI contract).


### Docs
- [`apps/agentflow-app/src/main/resources/application.yml`](apps/agentflow-app/src/main/resources/application.yml)
  — the {@code ai.prompt.prompts} declaration (the pinned template + version).
- [`apps/agentflow-app/src/main/resources/ai/planner.txt`](apps/agentflow-app/src/main/resources/ai/planner.txt)
  — the bundled, versioned planner policy (the header documents the "bump version on change" rule).
- [`apps/agentflow-app/AGENTS.md`](apps/agentflow-app/AGENTS.md)
  — noted the bundled prompt templates with a version/hash under `ai.prompt`.
- [`docs/security/secrets.md`](docs/security/secrets.md) — the S09-04 prompt-versioning hygiene rule
  (versioned / hashable templates) recorded alongside the provider-secrets handling.
- `CHANGELOG.md` — added the S09-04 bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Define the Planner's response schema (`S09-05`); add the `PlannerService` (`S09-06`), which consumes
`ChatModelPort` and records the `PromptVersion` on the attempt; apply the validation gate after the
Planner (`S09-07`) and the bounded repair policy (`S09-08`).
## S09-01 — The AI provider port: the `ai` domain calls a neutral contract, never a provider DTO (S09-01).


### What changed
Sprint 9 builds the AI layer on top of the durable workflow engine, and the `ai` module is still a
skeleton (`ai/AGENTS.md`, `ai/package-info.java`). S09-01 defines **how the domain calls the model
provider** so that the security-critical layers (planning, authorization, execution, verification —
`ADR-006`) depend on a contract that no specific provider can reach. Two narrow outbound ports are
defined in the module's public `application` boundary:

- **`ChatModelPort`** — text generation, one `chat(ChatRequest)` returning a `ChatResponse`. Planning
  and evaluation are both chat completions, so they share this one port.
- **`EmbeddingPort`** — embeddings, one `embed(EmbeddingRequest)` returning an `EmbeddingResponse`.

The contract types are pure, provider-agnostic records in `com.example.agentflow.ai.application`
that import no Spring and no provider package: `ChatMessage`, `ChatOptions`, `ChatRequest`,
`ChatResponse`, `ChatUsage`, `MessageRole`; and `EmbeddingRequest`, `EmbeddingResponse`,
`EmbeddingUsage`. The schema a Planner asks for as structured output rides on `ChatOptions.outputSchemaJson`
as a neutral JSON **string** that the provider adapter renders into the SDK's structured-output form
*behind the port*, so the contract never imports a provider's schema or message type — structured
output is expressed, never provider-specific.

Required fields are validated up front (fail-fast): `ChatRequest` / `EmbeddingRequest` reject a blank
model and empty inputs; collections are returned unmodifiable; `float[]` embeddings are copied on the
way out, so the holder owns its data. None of this is provider behaviour — it is the invariant-safe
shape of the contract (invariants 1, 8, 10). The concrete provider adapter is the **only** code that
will import a provider SDK, and it stays behind the port (added in `S09-02`). The ports are deliberately
narrow (one method each, matching the two ports in `docs/architecture/module-api.md`) and are **not** a
generic "LLM" abstraction — the port exists because a provider must be behind a boundary, not because a
second provider already exists (YAGNI).


### Why
Before this task the plan assumed the Planner (`S09-06`), the `LLM_EVALUATE` node and the
ingestion / knowledgebase embedding callers (`S10`) would call a model, but there was no boundary to
keep them decoupled from any provider. If the domain imported a provider SDK's request/response DTOs
directly, every security-critical layer would be coupled to one provider: swapping, retrying or
second-sourcing the provider would mean editing the Planner, the validation gate and the embedding
callers at once — exactly the coupling the untrusted-planner stance (`ADR-006`) exists to prevent. The
plan calls it out for `S09-01`: *define the interface for planning / evaluation / embedding calls;
isolate the domain from the SDK of a specific provider; do not build an over-generic abstraction
before the second implementation appears.* One port per concern (chat vs. embedding) matches the two
ports already documented in `module-api.md` and avoids over-engineering a problem that currently has
one implementation.


### Verification
Unit and architectural, DB-free, no model / Kafka / system-clock dependency:

- **`AiProviderPortContractTest`** (`ai.application`, 12 tests) pins the neutral contract: a `ChatMessage`
  exposes its role and content and rejects a null role / blank content; `ChatOptions` accepts a blank
  slate and carries a structured-output schema; `ChatRequest` validates its required fields (null model,
  blank model, null messages, empty messages), holds its messages unmodifiable (the caller's list is
  neither mutated nor leaked), a null `content` is an accepted refusal, and the model label is
  validated; `EmbeddingRequest` validates its required fields and `EmbeddingResponse` copies each
  `float[]` on the way out and validates the model label.
- **`AiProviderPortArchitecturalTest`** (`architecture`, 1 test) asserts that no class in the `ai`
  module's `application` package references a provider SDK package (`org.springframework.ai`,
  `com.openai`, `com.anthropic`, `com.google.ai`, `cohere`) — guarding the contract against a future
  adapter that leaks a provider DTO into it. The guard is scoped to the `application` package on
  purpose: `S09-02` added the sanctioned `provider` adapter (`OpenAiChatModelAdapter`) as the single
  class allowed to import the SDK. A guard `assertThat(classes).isNotEmpty()` prevents the gate from
  passing vacuously.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.application.AiProviderPortContractTest' --tests 'com.example.agentflow.architecture.AiProviderPortArchitecturalTest'` green
(13 tests, 0 failures — JUnit XML checked); `:apps:agentflow-app:check` green (compilation, unit,
architecture gates, spotless formatting, OpenAPI contract).


### Docs
- `docs/adr/ADR-014-provider-port.md` — the decision: two narrow neutral ports, structured output as a
  neutral `String`, fail-fast contract shape, the adapter behind the port (`Context → Decision →
  Alternatives → Consequences → Accepted`).
- `docs/adr/README.md` — indexed `ADR-014` in the register.
- `apps/agentflow-app/src/main/java/com/example/agentflow/ai/AGENTS.md` — noted the defined ports and
  their `application`-package placement.
- `CHANGELOG.md` — added the S09-01 bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Wire the concrete Spring AI adapter (`S09-02`, opt-in, keys from secrets) behind the ports; version the
prompts (`S09-04`) and define the Planner's response schema (`S09-05`); add the `PlannerService`
(`S09-06`), which consumes `ChatModelPort`; cap the Planner's step/time/cost budget.


## S09-03 — Close the provider secrets: the OpenAI API key is injected, opt-in, masked, and guarded by a canary so it never reaches Git, logs, diagnostics, or the image build.


### What changed
`S09-03` closes the three ways the OpenAI provider API key could leak: into Git / build, into the
logs / stack traces / diagnostic endpoints, and into the image build. The key is read from
`agentflow.ai.provider.openai.api-key` (falling back to `OPENAI_API_KEY`) by the opt-in
[`OpenAiChatModelConfiguration`](apps/agentflow-app/src/main/java/com/example/agentflow/ai/provider/OpenAiChatModelConfiguration.java)
(`@ConditionalOnProperty agentflow.ai.provider=openai`) — with no provider profile there is no
`ChatModel` bean and no key on the classpath, so the core app boots exactly as before. It is masked
by [`SecretRedactingValueMasker`](apps/agentflow-app/src/main/java/com/example/agentflow/observability/SecretRedactingValueMasker.java)
(registered as the `<valueMasker>` in [`logback-spring.xml`](apps/agentflow-app/src/main/resources/logback-spring.xml))
so a key embedded in a message is masked before stdout; only `health` and `info` are exposed over the
Actuator ([`application.yml`](apps/agentflow-app/src/main/resources/application.yml)), so `env` /
`configprops` cannot be read back; and unhandled errors go through the `internal`/500 path
(`GlobalExceptionHandler`) without the raw exception message. The image is not built yet (no
Dockerfile in the repository); the Compose / Helm manifests inject secrets through runtime environment
placeholders, never build args.

The acceptance — *a canary API key is not found in Git, logs, or diagnostic endpoints* — is pinned by
a planted canary in exactly one place,
[`src/test/resources/secrets/canary-openai-key.txt`], read at test time (never in a source file):

- `CanaryLeakScanTest` (`ai.security`, DB-free): `git ls-files` over the whole tree, every path except
  the fixture scanned and asserted canary-free; plus the canary is masked by the real
  `SecretRedactingValueMasker` (whole-value and embedded in a larger message).
- `ProviderSecretsActuatorTest` (`ai.security`, `@SpringBootTest`, no provider configured): the canary
  is not echoed by `/health` or `/info`, and `/env` / `/configprops` are closed.


### Why
`S09-02` wired the key through the opt-in provider config, but the security-critical question — *where
does the key actually stop leaking?* — was still open. The model is an untrusted planner (`ADR-006`)
and invariant 8 says a secret never lands in a row, log, metric, trace, Kafka payload or prompt: the
key had to be proven, not just configured. The cheapest durable proof is a canary: plant a unique fake
key; if it ever appears in Git, logs, or the diagnostic surface, the regression fails — which is
exactly what happens when a real key is committed by mistake.


### Verification
`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.security.CanaryLeakScanTest' --tests 'com.example.agentflow.ai.security.ProviderSecretsActuatorTest'`
— green (6 tests: 4 + 2). The scan boots the app on the `test` profile with no provider, so it needs
no model, API key or network. `:apps:agentflow-app:check` green (compilation, unit, architecture
gates, spotless formatting, OpenAPI contract).


### Docs
- `docs/security/secrets.md` — the secrets handling note: where the key lives, local storage, the logs
  / stack-trace / Actuator hygiene, the build-arg rule, the canary guard, and the acceptance.
- `CHANGELOG.md` — added the S09-03 bullet under `Unreleased → Security` (newest-first).
- This file.


### Follow-up
Version the prompts (`S09-04`); define the Planner's response schema (`S09-05`); add the
`PlannerService` (`S09-06`); cap the Planner's step/time/cost budget.

## S09-02 — The concrete AI provider adapter behind the port: a Spring AI (OpenAI) implementation of `ChatModelPort`.


### What changed
`S09-02` wires the first (and only) provider implementation behind `ChatModelPort` (`S09-01`). The
concrete adapter `OpenAiChatModelAdapter` (`ai.provider`) implements the port's `chat(ChatRequest)` and
maps the neutral request/response onto the Spring AI types. It is the only class in the module that
imports `org.springframework.ai` — the provider SDK stays behind the port, never reaching the
`application` contract (invariant 1: the domain depends on a contract, not a provider DTO).

The mapping is faithful to the contract's invariants:

- a `null` completion text (the provider refused or returned an empty completion) is returned as `null`
  content, never an empty string, so the domain treats it as malformed output (`S09-08`);
- the token accounting is derived from the provider `Usage` and is `null` when the provider reports
  none — Spring AI's `EmptyUsage` (zero tokens on every field) is the "reports none" sentinel and maps
  to `null`, so a missing usage is never read as a free completion by cost accounting;
- the structured-output schema and per-call tuning (temperature, max tokens) ride the neutral
  `ChatOptions`; each neutral role is rendered into the provider's role vocabulary (never the reverse);
- the constructor rejects a `null` model (`NullPointerException`).


### Why
Before `S09-02` the `ai` module was a skeleton: the domain could call only through the abstract port,
with no provider behind it, so the Planner (`S09-06`), evaluation (`LLM_EVALUATE`) and — via the
`EmbeddingPort` — ingestion could not run. The concrete adapter is the smallest complete change that
makes the port real, and it is deliberately provider-narrow (only OpenAI / Spring AI today), not an
over-generic "LLM" abstraction: a second provider should appear before the abstraction is generalized
(YAGNI). It stays opt-in (`OpenAiChatModelConfiguration`, `@ConditionalOnProperty`
`agentflow.ai.provider=openai`) so the core app still boots on the default profile with no AI key and
no `ChatModel` bean, and the provider's API key / base URL are read from the environment or a secret,
never hardcoded.


### Verification
Unit and architectural, DB-free, no model / API key / network dependency:

- **`OpenAiChatModelAdapterTest`** (`ai.provider`, 7 tests) drives the real mapping against a fake
  `ChatModel` that captures the built `Prompt` and returns canned Spring AI responses — completion to
  contract, `null` content on refusal, all three roles, tuning + schema on the prompt, `null` usage
  when the provider reports none, model fallback, and the null-model constructor guard.
- **`OpenAiChatModelConfigurationTest`** (`ai.provider`, 3 tests) uses `ApplicationContextRunner` to
  pin the opt-in gate: the `ChatModel` and `ChatModelPort` beans exist only when
  `agentflow.ai.provider=openai`, and are absent otherwise.
- **`AiProviderPortArchitecturalTest`** (`architecture`) was re-scoped: the no-provider-SDK guard now
  applies to the `application` package (the sanctioned `provider` adapter is the single importer), so
  the boundary is still protected against a DTO leak while the real adapter compiles.

`./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.provider.*' --tests 'com.example.agentflow.architecture.AiProviderPortArchitecturalTest'` green; `:apps:agentflow-app:check` green (803 tests, 0 failures; spotless formatting clean).


### Docs
- `CHANGELOG.md` — added the `S09-02` bullet under `Unreleased → Added` (newest-first).
- This file.


### Follow-up
Version the prompts (`S09-04`) and define the Planner's response schema (`S09-05`); add the
`PlannerService` (`S09-06`), which consumes `ChatModelPort`; cap the Planner's step/time/cost budget.

