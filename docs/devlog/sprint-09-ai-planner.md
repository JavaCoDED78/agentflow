# Changelog


# Sprint 9 — AI layer / Planner


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

