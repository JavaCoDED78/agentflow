# The RAG eval corpus

The fixed sample documents, chunks and questions of the RAG evaluation (S10-20). The
authoritative fixture is
[`RagEvalCorpus#baseline()`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalCorpus.java)
in code; this document is the readable map of it. Everything is **synthetic** — authored for the
eval, no third-party text, no personal data, no secrets — so the corpus is safe to keep in Git.
Every id is a labelled fixture constant.

## Fixture identities

| Identity | Fixture id |
|---|---|
| Eval tenant | `00000000-0000-0000-0000-00000000e710` |
| Foreign (decoy) tenant | `00000000-0000-0000-0000-00000000f710` |
| Eval knowledge base | `00000000-0000-0000-0000-00000000b710` |
| Embedding model label | `rag-eval-hash-768/v1` |
| Pipeline version | `rag-eval-pipeline-v1` |
| Requested top-k | 10 (the eval wiring's `max-top-k`) |

The eval tenant and the foreign tenant are data, never an authorization source: the runner serves
no request and reads no security context.

## The sample documents

Three owned documents (13 chunks) plus one foreign decoy document (1 chunk):

| Document | Id (suffix) | Pages | Chunks | Theme |
|---|---|---|---|---|
| `agentflow-retrieval-runbook.md` | `d0a0` | 3 | 6 (`a0c0`–`a0c5`) | the platform's own retrieval pipeline — model resolution, the two halves, fusion, bounds, citations |
| `warehouse-inventory-policy.md` | `d0b0` | 2 | 4 (`b0c0`–`b0c3`) | synthetic warehouse operations — cycle counts, reorder points, quarantine, picking |
| `q3-platform-uptime-report.md` | `d0c0` | 2 | 3 (`c0c0`–`c0c2`) | synthetic quarterly uptime and incident numbers |
| `tenant-isolation-brief.md` — **foreign tenant** | `d0f0` | 1 | 1 (`f0c0`) | the isolation decoy, worded to vectorise maximally close to `a-tenant-predicate-placement` |

The owned chunks and their page references (the "эталонные pages/chunks" the plan fixes):

| Chunk | Document | Pages |
|---|---|---|
| `a0c0` | `agentflow-retrieval-runbook.md` | 1–1 |
| `a0c1` | `agentflow-retrieval-runbook.md` | 1–1 |
| `a0c2` | `agentflow-retrieval-runbook.md` | 2–2 |
| `a0c3` | `agentflow-retrieval-runbook.md` | 2–2 |
| `a0c4` | `agentflow-retrieval-runbook.md` | 3–3 |
| `a0c5` | `agentflow-retrieval-runbook.md` | 3–3 |
| `b0c0` | `warehouse-inventory-policy.md` | 1–1 |
| `b0c1` | `warehouse-inventory-policy.md` | 1–1 |
| `b0c2` | `warehouse-inventory-policy.md` | 2–2 |
| `b0c3` | `warehouse-inventory-policy.md` | 2–2 |
| `c0c0` | `q3-platform-uptime-report.md` | 1–1 |
| `c0c1` | `q3-platform-uptime-report.md` | 2–2 |
| `c0c2` | `q3-platform-uptime-report.md` | 2–2 |

## The questions

Fourteen evidence questions name the chunk the answer must be found in; one off-topic question
expects an evidence gap. A question passes on full recall of its references inside the top-5,
correct citations and the expected evidence verdict.

| Case | Question (abridged) | Reference chunk(s) |
|---|---|---|
| `a-model-resolution` | How does retrieval resolve the embedding model before searching? | `a0c0` |
| `a-cross-tenant-empty` | What happens when a search runs against another tenant's knowledge base? | `a0c0` |
| `a-tenant-predicate-placement` | Where do the tenant filter predicates run in the nearest-neighbour search? | `a0c1` — **decoy:** `f0c0` vectorises closer but must never surface |
| `a-lexical-tokenisation` | How is an untrusted keyword tokenised for the full-text search? | `a0c2` |
| `a-hybrid-fusion` | How does hybrid fusion rank a chunk that both halves matched? | `a0c3` |
| `a-distance-threshold` | What happens to a candidate whose cosine distance exceeds the threshold? | `a0c4` |
| `a-insufficient-evidence` | What does an empty retrieval report instead of citations? | `a0c5` |
| `b-cycle-count` | When does a cycle count variance open a recount task? | `b0c0` |
| `b-reorder-point` | How is the reorder point of a stock keeping unit calculated? | `b0c1` |
| `b-quarantine` | How is quarantined stock treated in available-to-promise? | `b0c2` |
| `b-fifo` | Which lot ships first when picking an order? | `b0c3` |
| `c-uptime` | What uptime did the platform record in the third quarter? | `c0c0` |
| `c-incident` | How was incident 4711 mitigated? | `c0c1` |
| `c-recovery` | Were any recovery point objectives missed in the third quarter? | `c0c2` |
| `x-no-evidence` | What is the maternity leave duration for warehouse staff? | — (expects `InsufficientEvidence`) |

## The eval embedding

[`RagEvalEmbedding`](../../../apps/agentflow-app/src/main/java/com/example/agentflow/knowledgebase/evaluation/RagEvalEmbedding.java)
vectorises chunk and query alike: alphanumeric tokens (lowercased, a small English stopword set
removed, length ≥ 3) each add one unit to one slot of a 768-wide vector chosen by the token's
`String#hashCode`, L2-normalised. Two texts that share content words land close; unrelated texts
sit at the maximum cosine distance 1.0 — the separation the recall metric and the `x-no-evidence`
case rely on. It is a fixture, never a production embedding provider, and needs no model, key or
network.

## Baseline report

The current baseline (deterministic, reproduced by `RagEvalRunnerTest`):

```text
rag-eval baseline: 15/15 cases passed
PASS a-model-resolution refs=1 retrieved=3 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-cross-tenant-empty refs=1 retrieved=2 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-tenant-predicate-placement refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-lexical-tokenisation refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-hybrid-fusion refs=1 retrieved=2 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-distance-threshold refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS a-insufficient-evidence refs=1 retrieved=2 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS b-cycle-count refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS b-reorder-point refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS b-quarantine refs=1 retrieved=2 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS b-fifo refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS c-uptime refs=1 retrieved=4 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS c-incident refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS c-recovery refs=1 retrieved=3 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
PASS x-no-evidence refs=0 retrieved=0 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=as-expected
```

A retrieval change is compared by re-running the runner and diffing this rendering — any
regression names the case and the metric.

## Verification

Run the offline gate (JUnit-XML-verified, 7 tests, DB-free):

```bash
./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.knowledgebase.evaluation.*'
```

The suite asserts the baseline passes end to end, the decoy never surfaces, the off-topic
question reports an evidence gap, and the rendering is byte-stable across runs.
