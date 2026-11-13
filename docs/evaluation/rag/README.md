# RAG evaluation corpus

The fixed eval set every retrieval change is scored against (S10-20). The Planner has its
baseline cases under [`../README.md`](../README.md) (S09-14); this directory is the retrieval
counterpart: a small, stable corpus of synthetic sample documents and fixed questions with
reference pages/chunks, so two retrieval configurations — a retuned `threshold`, a `RankFusion`
change, a new chunking or embedding pipeline — are always compared on the same questions with the
same expectations.

## How it works

[`RagEvalRunner`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalRunner.java)
drives the production
[`RetrievalService`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/application/RetrievalService.java)
through the fixed questions of the
[`RagEvalCorpus`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalCorpus.java)
— the real path, not a parallel one: each case runs the hybrid retrieval (vector half S10-14 +
lexical half S10-15 + reciprocal-rank fusion S10-16), builds citations through the production
S10-17 layer, and applies the production S10-18 `GroundingPolicy`. The runner is pure and
deterministic: no Spring, no I/O, no clock, no randomness.

The eval needs no model and no network: the corpus vectors come from the deterministic
[`RagEvalEmbedding`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalEmbedding.java)
— a hashed bag of words into the same 768-dimensional space the production repository stores — and
the DB-free wiring emulates the production query semantics (tenant predicate inside the query,
cosine ordering, lexical token match) over the fixture store. The tenant and knowledge base are
labelled fixture ids, not an authorization source.

## The metrics

| Metric | Passing condition |
|---|---|
| `recall@1/3/5/10` | the fraction of the question's reference chunks inside the top-k of the fused hybrid result; a case fails when its reference chunks are not all inside the top-5 |
| `citations` | `correct` when one citation exists per retrieved chunk and every citation resolves to an **owned** corpus chunk with the exact document / page metadata — never a citation for a chunk the retrieval did not return or another tenant owns (the S10-17 acceptance, restated for the corpus) |
| `evidence` | `as-expected` when the grounding matches the question — `Grounded` for an evidence question, `InsufficientEvidence` for the off-topic fixture (S10-18) |

A case **passes** on full recall@5, correct citations and the expected evidence verdict. The
corpus also pins two behaviours no recall number shows:

- **tenant isolation** — `a-tenant-predicate-placement` vectorises *closer to the foreign decoy
  chunk than to any owned chunk*; it passes only while the tenant predicate (applied inside the
  query, never as a post-filter) keeps the foreign chunk out of the result;
- **no evidence** — `x-no-evidence` asks about nothing in the corpus and must report an evidence
  gap, never an invented citation.

## The fixed set

The corpus, the question list and the reference chunk/page mapping are documented in
[`corpus.md`](corpus.md) and fixed in code in
[`RagEvalCorpus#baseline()`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalCorpus.java).

## Running and comparing

The offline gate runs the whole eval against the deterministic fixture embedding — no API key, no
network:

```bash
./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.knowledgebase.evaluation.*'
```

To compare a **retrieval change** against the baseline, run the same `RagEvalRunner` with the
changed `RetrievalService` configuration (threshold, top-k bounds, a retuned `RankFusion`, a new
chunking pipeline) and diff the rendered report: `RagEvalReport.render()` is byte-stable (fixed
`\n` line endings, no timestamps, corpus order), so any regression names the case, the metric and
the failing condition. An opt-in run against a real provider follows the same runner with a
provider-backed `EmbeddingPort` wired into the `RetrievalService`.

## Verification

The eval is pinned by
[`RagEvalRunnerTest`](../../../apps/agentflow-app/src/test/java/com/example/agentflow/knowledgebase/evaluation/RagEvalRunnerTest.java)
(7 tests): the baseline passes end to end (15/15 cases: full recall at the target depth, correct
citations, the expected evidence verdict), the foreign decoy is never retrieved even when it is
the closest chunk in the store, the off-topic question reports `InsufficientEvidence`, and the
rendered report is byte-stable across runs. The corpus documents are synthetic — authored for the
eval, no third-party text — so the fixture is safe to keep in Git.
