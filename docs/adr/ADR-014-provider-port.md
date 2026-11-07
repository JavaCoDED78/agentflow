---
title: The AI provider is accessed behind a neutral application port
status: Accepted
date: 2026-11-07
task: S09-01
supersedes: none
superseded-by: none
---

# ADR-014. The AI provider is accessed behind a neutral application port

## Context

Sprint 9 builds the AI layer on top of the durable workflow engine. The `ai` module owns the model
adapter, the Planner, the server-side validation gate and the finalizer (documented as the public
boundary in `docs/architecture/module-api.md`), but it currently has **no code at all** — it is a
skeleton (`ai/AGENTS.md`, `ai/package-info.java`). The next tasks need to call a model:

- the **Planner** proposes a typed plan (S09-06) and the `LLM_EVALUATE` node evaluates a result;
- **ingestion / knowledgebase** generate embeddings for documents (S10).

The model is an untrusted planner, never an authorization authority (`ADR-006`), and the whole point
of that stance is that the *security-critical* layers (planning, authorization, execution,
verification) stay separate. If the domain code imported a provider SDK's request/response DTOs
directly, those layers would be coupled to one provider: changing or retrying the provider, or
adding a second one, would mean editing the Planner, the validation gate and the embedding callers.
The plan calls this out explicitly for `S09-01`: *define the interface for planning / evaluation /
embedding calls; isolate the domain from the SDK of a specific provider; do not build an over-generic
abstraction before the second implementation appears.*

## Decision

The `ai` module exposes **two narrow outbound ports** in its public `application` boundary, with the
request/response types they carry as plain, provider-agnostic records:

- **`ChatModelPort`** for text generation — one `chat(ChatRequest)` returning a `ChatResponse`.
  Planning and evaluation are both chat completions, so they share this one port.
- **`EmbeddingPort`** for embeddings — one `embed(EmbeddingRequest)` returning an `EmbeddingResponse`.

The contract types (`ChatMessage`, `ChatOptions`, `ChatRequest`, `ChatResponse`, `ChatUsage`,
`MessageRole`, `EmbeddingRequest`, `EmbeddingResponse`, `EmbeddingUsage`) are pure records in
`com.example.agentflow.ai.application`: they import no Spring and no provider package. The schema a
Planner asks for as structured output rides on `ChatOptions.outputSchemaJson` as a neutral JSON
**string** that the provider adapter renders into the SDK's structured-output format — so the contract
never imports a provider's schema or message type. Structured output is expressed, never
provider-specific.

The domain (Planner, validation gate, embedding callers) builds a contract record and calls the port;
it only ever sees a `String`/`float[]` result and parses/validates it server-side. The **concrete
provider adapter is the only code that imports the provider SDK**, and it is added in `S09-02` behind
the port. The port is deliberately narrow (one method each), matching the two ports documented in
`module-api.md`, and is **not** a generic "LLM" abstraction — it exists because a provider must be
behind a boundary, not because a second provider already exists (YAGNI).

Required fields are validated up front (fail-fast): `ChatRequest`/`EmbeddingRequest` reject a
blank model and empty inputs; collections are returned unmodifiable; `float[]` embeddings are copied
on the way out. None of this is provider behaviour — it is the invariant-safe shape of the contract
(invariants 1, 8, 10).

## Alternatives considered

1. **Call the provider SDK directly from the domain (no port).** Con: the Planner, the validation
   gate and the embedding callers each import the provider DTO, so a provider change touches every
   security-critical layer at once — exactly the coupling the untrusted-planner stance (ADR-006) is
   meant to prevent. Rejected.
2. **A single generic `ModelProviderPort` with one broad `generate(...)` method.** Con: a generic
   abstraction for a problem that has one current implementation is the over-engineering the task
   warns against; chat and embedding have different shapes and lifecycles, so two narrow ports are
   simpler and match the documented `ChatModelPort` / `EmbeddingPort`. Rejected.
3. **Put the provider DTOs in the contract and let the domain handle them.** Con: the acceptance
   criterion is that the domain does **not** depend on provider-specific DTOs; shipping those DTOs in
   the contract violates it. Rejected.

## Consequences

- **Positive.** The security-critical layers depend only on neutral records; the provider can be
  swapped, retried or second-sourced by changing one adapter behind the port, without touching the
  Planner, the validation gate or the embedding callers. The contract is pure (no Spring, no provider
  import), so it is easy to read and to test, and the fail-fast validation keeps an invalid request
  from ever reaching the network.
- **Negative / operational.** The port is an interface with no implementation until `S09-02`; the
  `ai` module stays a skeleton until the adapter is wired, so ADR-006 is in force by *doc* only until
  then. A `ChatResponse.content` of `null` (a refusal / empty completion) is handled as malformed
  output by the failure policy (S09-08), not treated as a valid plan.
- **Guard.** `AiProviderPortArchitecturalTest` imports the `ai` module and asserts that none of its
  classes depend on a provider SDK package (`org.springframework.ai` and the provider clients), so a
  future adapter that leaks a provider DTO into the contract is caught by `./gradlew check`.
- **Follow-ups.** Wire the concrete Spring AI adapter behind the ports (S09-02, opt-in, keys from
  secrets); define the Planner's response schema (S09-05) and the `PlannerService` (S09-06), which
  consume `ChatModelPort`; version the prompts (S09-04).
