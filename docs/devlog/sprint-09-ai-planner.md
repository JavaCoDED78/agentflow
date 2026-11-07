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
- **`AiProviderPortArchitecturalTest`** (`architecture`, 1 test) imports the `ai` module and asserts
  that none of its classes references a provider SDK package (`org.springframework.ai`, `com.openai`,
  `com.anthropic`, `com.google.ai`, `cohere`) — guarding the boundary against a future adapter that
  leaks a provider DTO into the contract. A guard `assertThat(classes).isNotEmpty()` prevents the gate
  from passing vacuously.

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

