# Product Scope — AgentFlow MVP

> **Status:** Accepted · **Owner:** product / engineering
> **Related plan task:** `S01-01` (Sprint 1 — repository foundation)
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (§1–§6)
> **Purpose:** define the boundaries of the first (MVP) release so the team can
> reliably separate *must-have* behavior from *version-two* ideas. This document
> is the single source of truth for "what v1 does and does not do."

---

## 1. What this document is (and is not)

This is a **scope boundary** definition, not a design or a backlog. It answers
three questions before any code is written:

1. **Who** uses the system, and what is the one end-to-end journey they care about?
2. **What** is in the first runnable version, constrained to one model provider
   and one main workflow?
3. **What** is intentionally excluded, and where does it go later?

Out of scope for this doc (delivered elsewhere): data model (`S03-03…S03-16`),
event contracts (`S06-01…S06-16`), DSL/state machine (`S05-01…S05-17`), CI/CD
(`S01-04…S01-20`). If a detail is needed here and does not exist yet, it is
recorded as an open decision, not invented.

---

## 2. Target user

**Persona (single, confirmed for v1): the domain analyst.**

- **Role.** Reviews several financial/technical reports and needs a
  structured, *sourced* comparison instead of reading raw PDFs.
- **Not an ML engineer.** Cares about correctness, citations and trust, not
  model selection or prompt tuning.
- **Operates inside a tenant** (an organization/team). All documents, runs and
  results are tenant-scoped; another tenant's data is indistinguishable from a
  missing one (single `404`/`403` policy).
- **Interface.** Primary surface is the REST API; the demo runs it from a
  browser/CLI. A full UI panel is explicitly v2.

**Explicitly not the v1 persona:** data engineers loading large catalogs, batch
operators, or multi-org SaaS tenants. These are later personas; their needs are
not allowed to drive v1 scope.

---

## 3. Core scenario — `login → upload → index → run → approval → result`

**Demo scenario:** an analyst uploads several quarterly reports and starts the
*"Prepare comparison"* workflow.

| Step | What the user does | What the system guarantees |
|---|---|---|
| **1. Login** | Signs in through Keycloak OIDC. | JWT validated (issuer, audience, signature); a **tenant context + permission scopes** (`workflow:write`, `document:read`, `run:approve`, …) is built. Tenant comes only from the verified token — never from the request body/query. |
| **2. Upload** | Uploads several PDFs to a knowledge base (`POST /api/v1/knowledge-bases/{id}/documents`, multipart). | Each file is validated (MIME, size, extension, `SHA-256`), stored in Garage under a tenant-scoped key, and a document row + `ingestion-requested` outbox event are written **in one DB transaction**. Returns `202`. |
| **3. Index** | Waits; does not block the run. | Ingestion worker extracts text (Tika/PDFBox), chunks (500–800 tokens, 80–120 overlap), embeds via the provider adapter, and indexes into PostgreSQL (pgvector + FTS) **with a tenant filter**. Document moves to `INDEXED` only after all mandatory stages. |
| **4. Run** | Starts *"Prepare comparison"* (`POST /api/v1/workflows/{id}/runs`, with `Idempotency-Key`). | Returns `202 Accepted` + `runId`. State + `workflow.run.requested.v1` event are written outbox-atomically. A worker claims the run (conditional update + `lease`/`lease_epoch`), loads the pinned workflow version, retrieves chunks via **tenant-scoped RAG**, asks the Planner for a typed JSON plan, validates it **server-side**, and executes only allowed tools (`document.search`, `calculator.evaluate`, `report.format`). Every external call is persisted **before** invocation; the outcome is treated as at-least-once, never exactly-once. |
| **5. Approval** | Approves or rejects at the `HUMAN_APPROVAL` node (e.g. before finalizing/sending the report). | Run moves to `WAITING_FOR_APPROVAL`; the decision is idempotent (`Idempotency-Key`) and written to the append-only audit trail. |
| **6. Result** | Queries `GET /api/v1/runs/{runId}` (+ `/steps`, `/events`, `/result`). | The Finalizer writes the result **with citations** (`documentId`, `chunkId`, page/section, score) and a terminal state. Empty retrieval reports "insufficient sources" — never invented citations. |

**Reliability in one sentence:** the run survives a worker crash (lease +
recovery job) and duplicate Kafka events do not double-apply business state
(inbox dedup). This durability — not a clever demo — is the core engineering
value of v1.

---

## 4. In scope for MVP (must-have)

The minimum that makes v1 a *reliable* platform, not a chat wrapper:

- **OIDC auth + tenant-aware authorization** (`identity`), with the local
  auth-only bypass confined to a **test** profile and removed before v1 go-live
  (`S04-10`).
- **Versioned workflow definitions**, with a pinned immutable activated version
  that every run references (`S05-12`).
- **Durable execution:** run/step state machine, leases + fencing tokens,
  checkpoints, retries with backoff, cancellation on a safe boundary, and
  worker recovery after a kill (`S05`, `S07`).
- **Async execution via Kafka** with a transactional outbox and inbox
  idempotency; create-run is transactionally durable (`S06`).
- **Typed, allowlisted tools** with input/output schema validation, timeouts,
  risk class and audit — and **no arbitrary code/shell execution** (`S08`).
- **RAG for PDF and text**: extract → chunk → embed → index, hybrid
  pgvector+FTS search, tenant/ACL filters applied **before** top-k, and
  citations (`S10`).
- **Human approval** for potentially irreversible side effects (`HUMAN_APPROVAL`
  node, idempotent + audited).
- **Usage/cost metering with run budgets** and an append-only audit trail
  (`usage`, `audit`).
- **Observability:** structured JSON logs, trace/correlation IDs, and the
  mandatory metrics (`agentflow_*`) without secrets or high-cardinality labels
  (`observability`).
- **One provider adapter** (Spring AI), isolated behind a port, with a
  fake-provider path for CI (no real model/key/internet) (`S09`).

---

## 5. Out of scope for MVP (intentionally excluded)

These are **not** deferred by accident — they are deliberately left out so v1
stays focused and verifiable.

| Area | Excluded from MVP | Where it goes |
|---|---|---|
| **Billing** | Full SaaS metering-billing and payments | Explicitly out of MVP (§1.3); usage recording only. |
| **Arbitrary code** | Running untrusted user code / dynamic tool registration by the LLM or user | Forbidden invariant; only registered allowlisted tools. |
| **Self-modifying AI** | Model changing its tool allowlist, permissions, policies or secrets | `ADR-006` — AI is an untrusted planner, never an authorization authority. |
| **Multi-region / multi-cluster HA** | Production-scale multi-zone clusters with dozens of replicas | Out of MVP (§1.3); local Compose / single-kind-cluster only. |
| **Complex UI panel** | Full web admin/authoring UI | REST API + demo UI only; panel is v2. |
| **Exactly-once delivery** | Promise of exactly-once between PostgreSQL and Kafka | Never promised; at-least-once + idempotent business operations only (`ADR-003`). |
| **Second provider / model routing** | A second cloud provider, A/B model selection | v1 = one provider; the port exists so a second is a later, isolated change. |
| **Custom user-authored graphs** | End-user DAG builder / arbitrary graphs | v1 = the one approved workflow type; custom graphs need the full validator + registry (`S05`). |
| **Qdrant / other vector stores** | Comparing a second vector backend | pgvector + FTS only for MVP; comparison is an end-of-project experiment. |
| **Multi-tenant SaaS** | Multi-org onboarding, org roles, usage-per-org | v1 = single-tenant operational model; tenant isolation is real, but multi-org is v2. |
| **OCR** | Optical recognition for scanned documents | Extract text only; OCR is a separate task. |
| **LLM-as-a-judge-only eval** | Using an LLM as the sole quality gate | Curated deterministic eval corpus + source-grounded metrics only (`10.4`). |

---

## 6. v1 constraints: one provider, one workflow

To keep the first release bounded and testable, v1 fixes two degrees of freedom:

1. **One model provider.** Exactly one cloud ChatModel (an OpenAI-compatible
   `ChatModel`) is configured and wired through the provider port. The
   adapter hides the SDK so a second provider is a later, isolated change, not
   a refactor. `Ollama` remains a documented local option, **not** part of the
   v1 default path.
2. **One main workflow.** A single approved workflow type — *"Prepare
   comparison"* — drives the demo. Its node types are limited to the allowed
   registry (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`,
   `LLM_EVALUATE`, `FINALIZE`); cycles in the graph are not permitted.

Everything in §4 must run against these two constraints. Any request to add a
second provider or workflow type before v1 go-live is a scope change and needs
an ADR.

---

## 7. Must-have vs. version-two ideas

This table is the primary acceptance aid for `S01-01`: anything in the **v2**
column is a valid future idea but must not be built into v1.

| Area | v1 (MVP, must-have) | v2 (explicitly later) |
|---|---|---|
| Persona | Single analyst persona | Data engineers, operators, batch jobs |
| Provider | One cloud provider (adapter-backed) | Second provider, model selection/routing |
| Workflows | One approved workflow type | Custom user-authored graphs + builder UI |
| Tools | 3 built-in tools + 1 demo MCP tool | Third-party tools, tool marketplace |
| Storage | Garage + pgvector/FTS | Qdrant comparison, large-scale indexing |
| Multi-tenant | Single-tenant operational model | Multi-org SaaS, org roles, per-org quotas |
| Interface | REST API + demo (browser/CLI) | Full web authoring/ops panel |
| Evaluation | Deterministic curated corpus | Live LLM eval programs with SLOs |
| Deployment | Docker Compose / single-kind cluster | Multi-cluster GitOps, HPA |

---

## 8. Open decisions and risks

These remain unresolved and need an ADR or a later task; none is a blocker for
writing code against the scope above.

- **Single confirmed persona** — the analyst is the v1 persona, but the exact
  login/permission surface (roles vs. scopes) is fixed in Sprint 4.
- **Provider choice** — the *shape* (one adapter-backed provider) is decided;
  the concrete vendor/patch version is chosen in `S09-02` after checking Spring
  AI/Boot compatibility.
- **Idempotency/return policy for re-uploads** — whether re-uploading the same
  file returns the existing version or a new one is a product policy to decide
  with the ingestion idempotency design (`S06-06`, document uniqueness).
- **Risk:** scope creep is the main hazard. Any "while we're at it" addition
  (second provider, UI, billing) must be pushed to the v2 column.

---

## 9. How to use this document

- **Is it a v1 feature?** It must fit §4 and the one-provider/one-workflow
  constraint (§6). Otherwise it is v2 (§7).
- **Someone asks to defer a §4 item?** It breaks the Definition of Done; do
  not silently drop it.
- **Someone asks to add a §7 item before go-live?** Treat it as a scope change
  requiring an ADR, not a v1 change.

---

## A. Glossary (v1 terms, pinned)

| Term | Meaning here |
|---|---|
| **Tenant** | An isolated organization/team; the unit of data separation. |
| **Knowledge base** | A tenant-scoped collection of indexed documents. |
| **Workflow** | A versioned, validated, typed graph of steps (`ADR-005`). |
| **Run** | One execution of a workflow against a specific version and input. |
| **Checkpoint** | Persisted per-step result/output ref used for recovery. |
| **Citation** | A grounded reference (`documentId`, `chunkId`, page, score) to a retrieved chunk. |
