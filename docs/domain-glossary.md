# Domain Glossary — AgentFlow

> **Status:** Accepted · **Owner:** engineering · **Task:** `S02-01`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (§4, §5, §6, §7, §8, §9, §10)
> **Purpose:** one shared vocabulary. A concept is called by the same name in
> tables, DTOs and code so a reader never has to guess whether two words mean
> the same thing — or one word means two things.

---

## How to read this document

Every entry gives:

- **Concept** — the canonical English term for both humans and code identifiers.
- **Shape** — the table(s), API field(s) or event field(s) that carry it.
- **Meaning** — what the concept is and, when useful, what it is **not**.
- **Alias to avoid** — names that must **not** be used for this concept, so the
  name does not drift across a table, a DTO and the API.

Canonical identifiers here are anchored to the **target data model**
(`AGENTFLOW_ARCHITECTURE_AND_PLAN.md` §8.1) and the **`/api/v1` API** (§9). The
package-by-feature skeleton (`S02-07`) adopts these identifiers verbatim; tables,
column names and API response fields must match the entries below.

This glossary is the single source of truth for terms. It links to the module
map (`docs/architecture/module-map.md`) and the architecture internals, but does
**not** duplicate their content.

---

## 1. Tenant context (module `identity`)

| Concept | Shape | Meaning |
|---|---|---|
| **Tenant** | table `tenants` (`id`, `slug`, `status`, `created_at`); field `tenant_id` | An isolated organization/team; the unit of data separation. The tenant is derived **only** from the verified security context (HTTP SecurityContext, or a stored event re-checked against PostgreSQL), never from the request body, query or Kafka headers. A resource is read by the pair `(tenant_id, resource_id)`. |
| **Tenant scope** | `tenant_id` on business records + a tenant filter that is part of the query, not a post-filter | All reads/writes/search/events are scoped to the tenant. Another tenant's resource is indistinguishable from a missing one (`404`/`403`). |
| **Tenant claim** | the `tenant_id` claim in the verified OAuth 2.0 JWT (S04-05) | The trusted carrier of the tenant in an authenticated request: a Keycloak mapped claim present only when the user has a tenant membership. Read **only** from the verified token by `TenantClaimExtractor`; a token without it (no membership) has no tenant and is denied. Never taken from the body/query/Kafka header. Threaded by `TenantContext` (S04-06). |
| **Permission scope** | the id of a `Permission` entry, e.g. `workflow:read`, `workflow:write`, `workflow:activate`, `document:read`, `document:write`, `run:execute`, `run:approve`, `ops:dlq` (S04-04) | The coarse capability a JWT grants. The ids live in the `identity` module's `Permission` catalogue, are mapped from JWT role claims by `ClaimsToAuthoritiesMapper` (deny-by-default) and are the single source of truth for the S04-08 endpoint access matrix. A permission scope never replaces the per-resource tenant/ownership check (`ADR-007`). |

**Alias to avoid:** a "tenant" is not an "org", "organization", "project" or
"customer" elsewhere in the code. `tenant_id` is the column everywhere.

---

## 2. Agents (module `agentcatalog`)

| Concept | Shape | Meaning |
|---|---|---|
| **Agent (agent profile)** | table `agent_profiles` (`id`, `tenant_id`, `name`, `model_provider`, `model_name`, `system_prompt_ref`, `tool_policy`, `budget_policy`, `version`, `status`) | A tenant-scoped, versioned bundle of *which model to use* and *which tool and budget policies apply to it*. Owned by `agentcatalog`. The active version of an agent profile is immutable (`unique (tenant_id, name, version)`). |
| **Agent profile version** | `agent_profiles.version` | A single immutable version of an agent profile. |

**Alias to avoid:** an "agent" is **not** a "tool", an "LLM" or a "workflow". It
is the profile that groups a model with its policies. Do not store the model
directly on a workflow run; a run references the pinned agent via the workflow
definition.

---

## 3. Tools (module `toolexecution`)

| Concept | Shape | Meaning |
|---|---|---|
| **Tool** | table `tool_definitions` (`id`, `tenant_id` nullable for global tools, `name`, `version`, `input_schema`, `output_schema`, `required_scopes`, `timeout_ms`, `enabled`); descriptor `ToolDescriptor` | A registered, allowlisted, typed action the executor may call: a defined name/version, input/output JSON Schema, required scopes, timeout, retry policy, risk class and a human-approval requirement. Unknown tools are never called. |
| **Tool registry** | in-memory, deterministic | Registers adapters at startup, rejects duplicate `id`/`version`, and forbids dynamic registration by the LLM or user. |
| **Tool risk class** | `READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT` | The blast radius of a tool. An unknown risk class is denied by default (`S11-01`). |
| **Tool execution context** | `ToolExecutionContext` (tenant, actor, run, step, correlation ID, minimal capabilities) | The minimal, tenant-scoped context passed to a tool; secrets are not handed to every adapter. |

MVP built-in tools: `document.search`, `calculator.evaluate` (a restricted
arithmetic parser — **not** `eval`), `report.format`. Demo MCP tool:
`ticket.lookup`.

**Alias to avoid:** a "tool" is not a "node", a "step", a "capability" or a
"function". The Planner may *refer* to a tool by its registered name but may not
add or enable one.

---

## 4. Workflow definitions and versions (module `workflowdefinition`)

| Concept | Shape | Meaning |
|---|---|---|
| **Workflow (workflow definition)** | table `workflow_definitions` (`id`, `tenant_id`, `name`, `description`, `status`, `version`, `created_by`, `created_at`) | A tenant-scoped, versioned definition with a stable `id`; `unique (tenant_id, name)`. The `id` is the definition's persistent identity (its versions live in `workflow_definition_versions`); `status` (default `'ACTIVE'`) and `created_by` are application-managed lifecycle fields, not set directly; `version` is the application-managed optimistic-lock counter — bumped on each successful update and mapped to `409 Conflict` on a stale write (S03-10) — which is distinct from the monotonic business version that addresses an immutable workflow version. |
| **Workflow version** | table `workflow_definition_versions` (`id`, `workflow_id`, `version`, `graph_json`, `checksum`, `status`, `created_by`, `created_at`); `unique (workflow_id, version)` | A single immutable version of a workflow definition. The `graph_json` is a declarative graph of typed steps plus a checksum; an activated version never changes. |
| **Workflow DSL / graph** | `graph_json` | A validated, typed DAG of allowed node types: `RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`, `FINALIZE`. Cycles are not permitted in the MVP; arbitrary user code is forbidden (`ADR-005`). The allowed set is the closed `WorkflowNodeTypeRegistry` (S05-07) — a new type is a new ADR, never a client choice; a client/LLM cannot register its own executable type. The machine-readable contract is [`contracts/workflow-graph/`](../contracts/workflow-graph/README.md) (`S05-03`). |
| **Node / step type** | `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_PLAN`, etc. | A single unit of work in the graph; not the same as a run. |
| **Workflow edge** | `{from, to}` (two node ids) in `graph_json` | An ordered execution-order link between two nodes of a workflow graph. Endpoints are node ids, not nodes; the structural validator (`S05-05`) rejects a dangling, self-referencing or duplicate edge and a duplicate node id, and the DAG gate (`S05-06`) rejects a graph whose edges form a cycle — so an activated graph is always a DAG. |

API: `POST /api/v1/workflows`, `GET /api/v1/workflows` (list: paginated —
`page`, `size` query params; the response carries `page`, `size`,
`totalElements`, `totalPages`, `hasNext`; another tenant's definitions never
appear — tenant isolation), `POST /api/v1/workflows/{workflowId}/versions`,
`POST .../versions/{version}/validate`, `POST .../versions/{version}/activate`,
`GET /api/v1/workflows/{workflowId}`.

**Alias to avoid:** a "workflow" (definition) is not its "run". A run
references a pinned *workflow version*, not the loose definition.

---

## 5. Runs and steps (module `workflowexecution`)

| Concept | Shape | Meaning |
|---|---|---|
| **Run** | table `workflow_runs` (`id`, `tenant_id`, `workflow_version_id`, `status`, `input_ref/json`, `result_ref/json`, `idempotency_key`, `attempt`, `lease_owner`, `lease_until`, `lease_epoch`, `next_action_at`, `version`, timestamps); response field `runId` | One execution of a workflow against a specific pinned version and input. `unique (tenant_id, idempotency_key)` where the key is not null. The canonical identifier is `runId`. |
| **Run status** | `workflow_runs.status` | `QUEUED → RUNNING → SUCCEEDED`/`FAILED`/`CANCELLED`, with `WAITING_FOR_APPROVAL`, `RETRY_WAIT`, `CANCEL_REQUESTED` as intermediate states. The full state set, the terminal states, the diagram and
the per-transition event/command/condition table are in
[`docs/architecture/run-state-machine.md`](architecture/run-state-machine.md)
(`S05-01`); transitions stay centralized in the single transition service
(`S05-11`) and must not be set directly from a controller. |
| **Step** | table `workflow_step_runs` (`id`, `run_id`, `step_key`, `status`, `attempt`, `input_hash`, `output_ref/json`, `error_code`, `error_summary`, timestamps, `lease_epoch`); `unique (run_id, step_key, attempt)` | A single step execution within a run. Not the same as a run or an event. |
| **Step status** | `workflow_step_runs.status` | `PENDING → READY → RUNNING → SUCCEEDED`, with alt `RETRY_WAIT`, `WAITING_FOR_APPROVAL`, `FAILED`, `SKIPPED`, `CANCELLED`. |
| **Attempt** | `run.attempt`, `step.attempt`, `attempt` history | The `attempt` is persisted **before** an external call; an ambiguous external outcome is marked, never treated as exactly-once (`ADR-003`). |
| **Checkpoint** | `step.output_ref` (+ schema version) | A persisted per-step result used for recovery; big outputs go to object storage, a link + checksum stays in the DB. |
| **Cost budget** | per-run budget, per-tenant quota, `usage_records`, `tenant_budgets` | A hard limit on steps, calls, tokens and estimated cost; reserved before an expensive call, settled idempotently. |

API: `POST /api/v1/workflows/{workflowId}/runs` (`Idempotency-Key`, `202
Accepted` + `runId`), `GET /api/v1/runs/{runId}` (+ `/steps`, `/events`,
`/result`), `POST /api/v1/runs/{runId}/cancel`.

**Alias to avoid:** a "run" is not a "process", "instance", "job" or "job run".
Use `runId` in API responses and `workflow_runs`. A "step" is not an "event" —
each step transition appends a `workflow_events` row.

### Lease / recovery primitives

| Concept | Shape | Meaning |
|---|---|---|
| **Lease** | `workflow_runs.lease_owner`, `lease_until`, `lease_epoch`; heartbeat; recovery job | A worker atomically claims a run and receives `lease_until`, `worker_id` and an incremented `lease_epoch`. Only the current owner may extend it; every worker write re-checks the expected `lease_epoch` (`fencing`) so a stale worker cannot overwrite the new owner's result. |

---

## 6. Events and messaging (module `messaging`)

| Concept | Shape | Meaning |
|---|---|---|
| **Event** | table `workflow_events` (append-only); `id`, `tenant_id`, `run_id`, `step_run_id`, `event_type`, `payload_redacted`, `occurred_at` | A domain fact appended for a run/step; replayed (ordered by `occurred_at`) via `GET /api/v1/runs/{runId}/events` so a run's state can be reconstructed (S05-09). |
| **Event envelope** | Kafka record payload (`eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`, `aggregateType`, `aggregateId`, `correlationId`, `causationId`, `payload`) | The minimal envelope for all events. `tenantId` is for routing/correlation only — the worker still re-checks ownership against PostgreSQL. |
| **Outbox** | table `outbox_events` | The DB-side outbox: state and the outbox record are written in the same transaction; publishing is idempotent by `event_id`. |
| **Correlation headers** | `CorrelationHeaders` (value type: `correlationId`, `causationId`, `traceparent`); persisted verbatim in `outbox_events.headers` (S06-12) | The correlation context carried by an event. `correlationId` defaults to the `runId`; the first event of a chain omits `causationId` and a blank `traceparent` is omitted. A correlation label only — never a secret, the full prompt, the full document, or `tenantId` (invariants 1/7/8). The producer (`workflowexecution` `RunService`) is the sole writer; the consumer re-checks tenant ownership against PostgreSQL. |
| **Inbox** | table `inbox_messages` (`consumer_name`, `event_id`, `status`, `attempts`, `next_attempt_at`, `last_error`, `outcome`, `processed_at`, `created_at`); PK `(consumer_name, event_id)` | Consumer-side dedup: a business change and its inbox record are written in the same transaction. |
| **DLQ message** | table `dlq_messages` (`event_id`, `topic`, `consumer_name`, `status`, `attempts`, `last_error`, `recorded_at`); UNIQUE `(consumer_name, event_id)` | The durable, held-for-investigation copy of a failed consumer outcome (S06-11). Appended once per `(consumer, event)` by the `messaging` `DlqService` when a consumer records a `RETRY` (a transient fault exhausted its bounded immediate retry) or `DEAD_LETTER` (a permanent / malformed event short-circuited the retry); a duplicate append is a no-op (at-least-once, `ADR-003`). PostgreSQL is the source of truth for the disposition (invariant 2); records are evidence for investigation, not reprocessed by default. `last_error` is redacted (invariant 8). |
| **Topic** | `agentflow.workflow.run-requested.v1`, `.../step-ready.v1`, `.../run-cancelled.v1`, `agentflow.document.ingestion-requested.v1`, `.../indexed.v1`, `.../run-finished.v1` | Versioned (`topic-name.vN`) topics. `run-requested`/`step-ready`/`run-cancelled` keys are `tenantId:runId`; document topics key by `tenantId:documentId`. |

**Guarantee:** at-least-once delivery **with idempotency** (`ADR-003`). Exactly
once is **never** promised between PostgreSQL and Kafka. DLQ is for
investigation, not unbounded retry.

**Alias to avoid:** an "event" (domain fact in `workflow_events`) is not a Kafka
"message"/"envelope", and a "message" is not a "run". Do not fold these into one
word.

---

## 7. Knowledge base, documents and chunks (modules `knowledgebase`, `ingestion`)

| Concept | Shape | Meaning |
|---|---|---|
| **Knowledge base** | table `knowledge_bases` (`id`, `tenant_id`, `name`, `embedding_model`, `chunking_config`, `status`); `unique (tenant_id, name)` | A tenant-scoped collection of indexed documents. |
| **Document** | table `documents` (`id`, `tenant_id`, `knowledge_base_id`, `object_key`, `filename`, `mime_type`, `size_bytes`, `sha256`, `version`, `status`, `pipeline_version`, `page_count`, timestamps) | The original object (kept in object storage under a server-generated, tenant-scoped `object_key`) plus its metadata. `sha256` + `pipeline_version` drive ingestion idempotency. |
| **Document lifecycle** | `documents.status` | `UPLOADED → QUEUED → PROCESSING → INDEXED`, with `FAILED`, `DELETE_REQUESTED`, `DELETED`. |
| **Chunk** | table `document_chunks` (`id`, `tenant_id`, `document_id`, `chunk_index`, `page_from`, `page_to`, `content`, `content_hash`, `embedding`, `metadata_json`); `unique (document_id, chunk_index, pipeline_version)` | One fragment of a document indexed for retrieval, with page metadata and an embedding. The tenant filter is mandatory on chunk search. |

API: `POST /api/v1/knowledge-bases`, `POST /api/v1/knowledge-bases/{id}/documents`
(multipart upload), `GET /api/v1/documents/{documentId}`,
`POST /api/v1/knowledge-bases/{id}/search`.

**Alias to avoid:** a "document" is not a "chunk". A "chunk" is not a
"paragraph" or "block" in the persistence layer — the stored unit is a chunk.

---

## 8. Retrieval, citations and RAG (module `knowledgebase` / `ai`)

| Concept | Shape | Meaning |
|---|---|---|
| **Retrieval** | tenant/ACL-filtered vector (pgvector) + FTS (full-text) hybrid | Retrieval applies the tenant/ACL predicate **before** top-k, as part of the query, not as a post-filter. |
| **Citation** | response field set: `documentId`, `chunkId`, page/section, score | A grounded reference to a single retrieved chunk. Only real retrieved chunks are cited; empty retrieval reports "insufficient evidence" — never invented citations. |

---

## 9. Human approval (module `workflowexecution`; `S11`)

| Concept | Shape | Meaning |
|---|---|---|
| **Approval (approval record)** | table `approvals` (`id`, `tenant_id`, `run_id`, `step_key`, `status`, `action`, `payload_fingerprint`, `requester`, `decision_actor`, `decision_at`, `decision_comment`, `expiry`, `created_at`) | A durable, immutable-payload-bound pause point for a side effect. |
| **Approval node** | graph node `HUMAN_APPROVAL` | Stops the run at a safe side-effect boundary, moves it to `WAITING_FOR_APPROVAL`, and releases the worker lease while waiting. |
| **Approval decision** | `POST /api/v1/runs/{runId}/approvals/{approvalId}` (`Idempotency-Key`), scope `run:approve` | `approve`/`reject`/expiry, idempotent, written to the append-only audit trail. The decision is bound to the exact tool-argument fingerprint — an approved set of arguments cannot execute a different one. |

**Alias to avoid:** an "approval" is not a "permission", a "sign-off" or a
"workflow event". It is a specific, audited, idempotent decision on one step.

---

## 10. Usage, budgets and audit (modules `usage`, `audit`)

| Concept | Shape | Meaning |
|---|---|---|
| **Usage record** | table `usage_records` (`id`, `tenant_id`, `run_id`, `step_run_id`, `provider`, `model`, `input_tokens`, `output_tokens`, `estimated_cost`, `currency`, `recorded_at`); cost is `NUMERIC`/Decimal | A single token/cost measurement tied to a specific run/step/attempt. Distinguishes `estimated` from provider-reported usage. |
| **Usage / cost** | `usage_records.estimated_cost` (Decimal), `currency` | Money is `NUMERIC`, never `float`. |
| **Budget** | `tenant_budgets` (`tenant_id`, `period`, `max_runs`, `max_tokens`, `max_cost`) / per-run budget | A reservation that blocks the next operation when exceeded, settled idempotently. |
| **Quota** | per-tenant limit on concurrent runs / usage | Concurrency counted in PostgreSQL; Redis only for auxiliary rate limiting / caching, never the sole durable usage state. |
| **Audit event** | table `audit_events` (`id`, `tenant_id`, `actor_subject`, `action`, `resource_type`, `resource_id`, `outcome`, `correlation_id`, `metadata_redacted`, `created_at`); append-only | The immutable log of security- and business-sensitive actions (definition mutations, run start/cancel, upload/read, approval, tool allow/deny/execute, ops). No secrets or full prompts. |

**Alias to avoid:** a "usage record" is not a "bill", an "invoice" or a
"billing event" — the MVP records usage only; billing is out of scope.

---

## 11. AI orchestration (module `ai`)

| Concept | Shape | Meaning |
|---|---|---|
| **Model provider / port** | application port + one adapter | The single ChatModel adapter behind a port; a second provider is a later, isolated change. |
| **Planner** | structured, schema-constrained output | Proposes a typed JSON plan from allowed node types only; it is an untrusted planner, never an authorization authority (`ADR-006`). |
| **Validation gate** | server-side DAG/permission/tool/budget check | Validates the Planner's plan (DAG, allowed tools, arguments, tenant scope, step limit, budget) before execution; unknown/cyclic/over-limit plans never reach the executor. |
| **Executor** | runs validated steps via the tool registry | Executes only validated steps; tool arguments pass JSON Schema validation. |
| **Finalizer** | result with citations + cost metrics | Writes the result with citations and the terminal state; records cost metrics. |
| **Embedding** | `document_chunks.embedding`, provider adapter | The vector representation of a chunk's content. |

---

## 12. Errors and observability cross-cutting terms

| Concept | Shape | Meaning |
|---|---|---|
| **Error class / taxonomy** | error classification (`S02-10`) | Errors are classified; retry applies **only** to transient classes. Validation/policy errors are permanent. |
| **Correlation / trace ID** | `correlation_id`, `traceId`/`spanId` | Cross-process correlation; live only in traces/logs, never in metric labels. |
| **Metric label** | bounded category labels only | Never `tenant_id`, `run_id`, `user_id`, `document_id` or a prompt in labels (`ADR-007`, observability). |

---

## 13. Naming authority (term → owner)

One owner per concept, matching the module map:

| Concept | Owning module | Persistence |
|---|---|---|
| Tenant | `identity` | `tenants` |
| Agent (profile) | `agentcatalog` | `agent_profiles` |
| Tool | `toolexecution` | `tool_definitions` |
| Workflow definition / version | `workflowdefinition` | `workflow_definitions`, `workflow_definition_versions` |
| Run / step / attempt | `workflowexecution` | `workflow_runs`, `workflow_step_runs` |
| Event / envelope / outbox / inbox / DLQ | `messaging` | `workflow_events`, `outbox_events`, `inbox_messages`, `dlq_messages` |
| Knowledge base / document / chunk | `knowledgebase` (+ `ingestion`) | `knowledge_bases`, `documents`, `document_chunks` |
| Approval | `workflowexecution` | `approvals` |
| Usage / budget / quota | `usage` | `usage_records`, `tenant_budgets` |
| Audit event | `audit` | `audit_events` |

---

## 14. Do-not-use alias list (summary)

Keep these distinct so one name never drifts across tables, DTOs and the API:

- **Run** — use `runId` / `workflow_runs`. Not *process / instance / job*.
- **Step** — use `step` / `workflow_step_runs`. Not *event / node-in-db*.
- **Event** — use `workflow_events` (domain fact) or the Kafka **envelope**.
  Don't collapse "event" and "run".
- **Agent** — use *agent* / *agent profile*. Not *tool / LLM / workflow*.
- **Tool** — use *tool* / `tool_definitions`. Not *node / capability*.
- **Workflow** — definition vs. its *run* (pinned version). Don't mix.
- **Document** vs. **Chunk** — original vs. indexed fragment.
- **Approval** — the audited *decision*, not a generic *permission*.
- **Usage record** — a measurement, not a *bill*.

---

## 15. How this glossary is verified

- Every term above is anchored to the plan's target data model (`S03`),
  `/api/v1` API (`S03-12`), run/step state machine (`S05`), Kafka/event contract
  (`S06`) and AI orchestration (`S09`).
- The acceptance criterion is *one concept — one name* across tables, DTOs and
  code; when `S02-07` builds the package skeleton, identifiers match this
  document verbatim so no drift is introduced.
- See `docs/README.md` (documentation index) and `docs/AGENTS.md` (rules and
  conventions).
