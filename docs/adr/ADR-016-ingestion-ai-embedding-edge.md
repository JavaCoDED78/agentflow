# ADR-016. ingestion embeds chunks through the ai embedding port

- **Status:** Accepted
- **Date:** 2026-11-12
- **Task:** S10-12 (Sprint 10 — embeddings adapter and batching)
- **Supersedes:** none. Extends the provider-port boundary (`ADR-014`) to the embedding path of the
  ingestion pipeline.
- **Superseded by:** none

## Context

The ingestion pipeline the module map assigns to `ingestion` is extract → chunk → **embed** →
index. The embed step is implemented by `ingestion.application.embed.EmbeddingGenerationService`
(S10-12): it batches the chunk texts and calls the neutral `ai.application.EmbeddingPort`, whose
concrete provider adapter and resilience layer (`ai.provider.ResilientEmbeddingModel`, retrying
provider rate limits and transient faults per `ProviderResiliencePolicy`) stay behind the port
(`ADR-014`, ADR-006).

The module-allowed dependency set of `ingestion` was `{ingestion, knowledgebase, messaging}`
(arrows 6 and 11) — it did **not** contain `ai`. Embedding a chunk set therefore needs a compile
edge `ingestion → ai`, and the module map forbids any edge outside the allowed set (root
`AGENTS.md`; `docs/architecture/module-map.md`).

This is not a new design direction: the plan's own module API map already documents the
`ingestion` internals as "embedding adapters (depend on the `ai` embedding port)"
(`docs/architecture/module-api.md`, from `S02-04`), and the `EmbeddingPort` javadoc (`S09-01`)
names `ingestion` as one of its two callers. The edge was anticipated by the contracts; this ADR
records it in the dependency table so the architecture gate and the documentation agree.

## Decision

**Grant `ingestion` a new compile edge to `ai`, recorded here as arrow 19, and embed chunk sets
from `ingestion.application.embed.EmbeddingGenerationService` through the neutral `EmbeddingPort`.**
The edge is the narrowest possible: `ingestion` reaches `ai` only through the port contract types
in the `ai` module's public `application` package (`EmbeddingPort`, `EmbeddingRequest`,
`EmbeddingResponse`, `EmbeddingUsage`), never `ai.provider` (the provider SDK and its resilience
implementation stay invisible to the caller), `ai.persistence` or `ai.worker`. The graph stays
acyclic: `ai` depends only on `toolexecution` and `workflowexecution`, so `ingestion → ai` closes
no loop (verified by `PackageDependencyGraphTest` and `NoCyclicModuleDependenciesTest`).

The split of responsibilities between the two modules is deliberate:

- `ai` owns everything provider-specific: the SDK, the failure classification
  (`ProviderExceptionClassifier`), the bounded retry / overall timeout / concurrency cap
  (`ResilientEmbeddingModel` over `ProviderResiliencePolicy`), and — in a later change — the
  concrete provider adapter for embeddings, wired opt-in like `OpenAiChatModelConfiguration`.
- `ingestion` owns the pipeline semantics: batching the chunk texts (configuration, not a magic
  number), preserving input order across batches, validating every provider response against the
  port contract (one vector per text, uniform width) and aggregating the token accounting. It has
  no provider knowledge and no retry logic of its own.

## Alternatives considered

1. **Give the embed step to the `ai` module entirely** (an `ai`-owned embedding service that
   `ingestion` would call). Rejected: the extract → chunk → embed → index pipeline and its
   idempotency are `ingestion`'s responsibility (module map, business-rule table); batching chunk
   sets is pipeline semantics, not provider semantics. It would also pull `DocumentChunk`-shaped
   knowledge into `ai`.
2. **Duplicate the resilience logic inside `ingestion`** (a module-local retry policy, the
   established pattern for `RetryPolicy`). Rejected: provider failure classification is
   SDK-specific and already has one owner (`ProviderExceptionClassifier`); a second copy in
   `ingestion` would either import the classifier (needs this edge anyway) or re-implement the
   provider fault mapping (a second, drifting owner of the same rule).
3. **Carry primitives only** (define a parallel `ingestion.domain` embedding-port shape with no
   `ai` import, wired at the application root). Rejected: it duplicates the neutral contract the
   `ai` module already publishes as its public API for exactly this purpose (`ADR-014`), and the
   root wiring would smuggle the edge past the architecture gate instead of recording it.
4. **Route the call through `knowledgebase`** (`ingestion` already depends on it, and it depends on
   `ai`). Rejected: `knowledgebase` would have to expose a pass-through embedding port — a new,
   unrelated responsibility on the wrong module and an implicit, undocumented dependency of the
   pipeline on the retrieval owner.

## Consequences

- **Positive:**
  - The embed step is implemented where the pipeline lives, against the neutral port the plan
    already assigned to it (`S02-04` module API map, `S09-01` port javadoc).
  - Provider rate limits and transient faults are retried by the single owner of provider failure
    classification; `ingestion` never sees a provider SDK type.
  - The edge is enforced, not conventional: `ArchitectureBoundaryTest` rejects any `ingestion`
    import outside `{ingestion, knowledgebase, messaging, ai}`.
- **Negative / operational:**
  - `ingestion` now has a compile dependency on `ai`, so the reach must stay narrow: only the
    public `ai.application` port types. A future need for anything else from `ai` requires another
    ADR.
  - The `EmbeddingPort` bean remains opt-in (`ADR-014`): a default boot has none, so the
    `EmbeddingGenerationService` is constructed without a port and fails loudly at
    `embed(...)` call time — the app boots without a provider or an AI key exactly as before
    (S01-02), following the `PlannerService` / `ChatModelPort` optional-port idiom.
- **Follow-ups:**
  - The concrete provider embedding adapter (e.g. Spring AI `EmbeddingModel` behind
    `EmbeddingPort`, wired under `agentflow.ai.provider=openai` / a local Ollama profile, reusing
    `agentflow.ai.retry.*`) is a separate change; the port and its resilient wrapper are in place.
  - `S10-13` wires the service into the indexing worker and persists the vectors; `S10-14` adds the
    tenant/ACL-filtered retrieval that consumes them.

## When to write an ADR

A new dependency or trust edge between modules (respecting the no-cycle and module-ownership
rules) — exactly this case (root `AGENTS.md`; `docs/architecture/module-map.md`).
