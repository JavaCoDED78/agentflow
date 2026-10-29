# Workflow DSL v1

The declarative, typed language for the graphs that AgentFlow runs. This document describes
**DSL v1**, the only version defined by the machine-readable contract
[`contracts/workflow-graph/workflow-graph.json`](../contracts/workflow-graph/workflow-graph.json)
(JSON Schema, draft 2020-12, `$id` `.../workflow-graph/1.0.0.json`).

A graph is a **directed acyclic graph (DAG)** of typed steps and the edges that order them. It is
serialized verbatim as `workflow_definition_versions.graph_json` and validated before any version is
persisted (`S05-05`, `S05-06`, `S05-07`). The graph is **data**, not a program: nothing here is
executed, so an unknown type can never become executable code (invariant 7 / ADR-005).

> This is the human-readable companion to the JSON Schema. The schema enforces everything portable
> (required fields, the closed node-type set, field shapes and patterns, the node/edge size limits);
> the topology rules JSON Schema cannot express portably — a non-blank unique node id, a closed
> node type, edges that name existing nodes and are not dangling/duplicate/self-referencing, an
> acyclic graph — are enforced by the DSL validator at runtime.

## 1. Top-level shape

Every v1 graph is an object with exactly three keys:

```json
{
  "schemaVersion": "1.0.0",
  "nodes": [ /* at least one, at most 200 */ ],
  "edges": [ /* zero or more, at most 1000 */ ]
}
```

| Key | Type | Rules |
|---|---|---|
| `schemaVersion` | string | **`const`** `"1.0.0"`. Any other value is rejected (S05-04). |
| `nodes` | array | `minItems: 1`, `maxItems: 200`. Ordered; each item is a `node`. |
| `edges` | array | `minItems: 0`, `maxItems: 1000`. Ordered; each item is an `edge`. |

`additionalProperties` is `false` at the top level: only these three keys are allowed.

## 2. Node types

The allowed node set is **closed** and fixed in exactly one place —
`WorkflowNodeTypeRegistry` (`workflowdefinition.domain`, S05-07) — anchored verbatim to the domain
glossary and ADR-005. The registry holds no behaviour that mutates state; a `type` is compared
against it, never interpreted as code. A client or the untrusted planner cannot register its own
type: the pre-persistence gate rejects any type not in the set.

| Node type | Purpose | `config` shape (illustrative) |
|---|---|---|
| `RETRIEVE_CONTEXT` | RAG retrieval; the input to the pipeline | `{ "query": "the user's question", "top_k": 5 }` |
| `LLM_PLAN` | Ask the model for the next plan/steps | `{ "prompt": "plan from retrieved context", "budget": { "max_steps": 10 } }` |
| `TOOL_CALL` | Invoke an approved tool | `{ "tool": "http_fetch", "args": { "url": "https://example.com/data" } }` |
| `HUMAN_APPROVAL` | Pause for human confirmation | `{ "instruction": "confirm the tool result before proceeding" }` |
| `LLM_EVALUATE` | Grade a result against a rubric | `{ "rubric": "correctness against the rubric" }` |
| `FINALIZE` | Emit the pipeline's final answer | `{ "summary": "final answer" }` |

The node type is a plain string compared against the registry — there is no `SCRIPT` node or any
other executable type. (A payload that declares `SCRIPT` is rejected; see §5.)

## 3. Nodes and edges

A **node** is one typed step:

```json
{
  "id": "plan-1",
  "type": "LLM_PLAN",
  "config": { "prompt": "plan from retrieved context", "budget": { "max_steps": 10 } },
  "input":  { "ctx": { "node": "retrieve-1", "output": "context" } },
  "output": { "plan": "the plan the model produced" }
}
```

| Field | Required | Notes |
|---|---|---|
| `id` | **yes** | Non-blank, `^[a-zA-Z][a-zA-Z0-9_-]*$`, `maxLength 128`, **unique** across the graph. Uniqueness is enforced by the DSL validator (JSON Schema cannot express cross-item uniqueness portably). |
| `type` | **yes** | One of the six closed node types (§2). |
| `config` | no | Free-form per-step parameters, `additionalProperties: true`. Treated as **untrusted data, never executed** (invariant 7). |
| `input` | no | Optional wiring: a map of input name to a literal string or a `{ "node": ..., "output": ... }` reference to an upstream node's output. |
| `output` | no | Optional declaration of this node's named outputs (non-blank strings); downstream `input`s reference them. |

An **edge** expresses execution order:

```json
{ "from": "plan-1", "to": "tool-1" }
```

`from` and `to` are node ids that must exist; self-references, dangling endpoints and duplicate
edges are rejected by the DSL validator (S05-05, S05-06), not by this schema. Edges may be empty for
a single-node graph. `input`/`output` are **data mapping** (which value feeds which step); `edges`
are **execution order** — the two are independent.

## 4. The canonical valid examples

The canonical examples live in [`contracts/workflow-graph/`](../contracts/workflow-graph/) and pass
the current validator (verified below). They cover the whole vocabulary.

**Linear pipeline** (`valid/example-linear.json`, all six node types, one path):

```json
{
  "schemaVersion": "1.0.0",
  "nodes": [
    { "id": "retrieve-1", "type": "RETRIEVE_CONTEXT", "config": { "query": "the user's question", "top_k": 5 } },
    { "id": "plan-1", "type": "LLM_PLAN", "config": { "prompt": "plan from retrieved context", "budget": { "max_steps": 10 } } },
    { "id": "tool-1", "type": "TOOL_CALL", "config": { "tool": "http_fetch", "args": { "url": "https://example.com/data" } } },
    { "id": "approval-1", "type": "HUMAN_APPROVAL", "config": { "instruction": "confirm the tool result before proceeding" } },
    { "id": "eval-1", "type": "LLM_EVALUATE", "config": { "rubric": "correctness against the rubric" } },
    { "id": "finalize-1", "type": "FINALIZE", "config": { "summary": "final answer" } }
  ],
  "edges": [
    { "from": "retrieve-1", "to": "plan-1" },
    { "from": "plan-1", "to": "tool-1" },
    { "from": "tool-1", "to": "approval-1" },
    { "from": "approval-1", "to": "eval-1" },
    { "from": "eval-1", "to": "finalize-1" }
  ]
}
```

**Branching DAG** (`valid/example-branching.json`): two `LLM_PLAN` branches feeding one merge,
with `input` wiring from the retriever's `output`:

```json
{
  "schemaVersion": "1.0.0",
  "nodes": [
    { "id": "retrieve-1", "type": "RETRIEVE_CONTEXT", "config": { "query": "a retrieval query" }, "output": { "context": "the retrieved context" } },
    { "id": "plan-a", "type": "LLM_PLAN", "config": { "prompt": "plan A" }, "input": { "ctx": { "node": "retrieve-1", "output": "context" } } },
    { "id": "plan-b", "type": "LLM_PLAN", "config": { "prompt": "plan B" }, "input": { "ctx": { "node": "retrieve-1", "output": "context" } } },
    { "id": "merge-1", "type": "TOOL_CALL", "config": { "tool": "concat", "args": {} } },
    { "id": "finalize-1", "type": "FINALIZE", "config": { "summary": "merged answer" } }
  ],
  "edges": [
    { "from": "retrieve-1", "to": "plan-a" },
    { "from": "retrieve-1", "to": "plan-b" },
    { "from": "plan-a", "to": "merge-1" },
    { "from": "plan-b", "to": "merge-1" },
    { "from": "merge-1", "to": "finalize-1" }
  ]
}
```

## 5. Validation — the rules and the real rejection messages

The `workflowdefinition` module runs the graph through the same gate the API does before a version
is persisted. The gate is in three layers:

- **`WorkflowGraphParser#collectIssues(String)`** — parses the JSON and collects *every* schema/shape
  problem as `GraphValidationIssue(path, message)` (used by the read-only `POST …/validate` endpoint
  and by `WorkflowVersionService` on create).
- **`WorkflowGraphValidator#collectIssues(WorkflowGraph)`** — the domain structural gate (S05-05,
  S05-06, S05-07): size limits, acyclicity, and the node/edge topology rules.
- **`WorkflowGraphParser#parse()`** / **`WorkflowGraphValidator#validate(WorkflowGraph)`** — the
  throwing variants, which fail on the first problem as `InvalidGraphException` → HTTP 400.

Each structural error names the **JSON path** it came from (`nodes[i]`, `edges[j] field 'to'`,
`schemaVersion`, or `$` for a whole-graph problem), so a client can locate the offending element.
`collectIssues` returns them all in check order: size → node ids → node types → edges → cycle.

The rules and the exact messages they produce (captured from the real validator):

| Rule | Rejected message | Path |
|---|---|---|
| Empty graph | `workflow graph must contain at least one node` | `$` |
| More than 200 nodes | `workflow graph has *N* nodes, which exceeds the limit of 200` | `$` |
| More than 1000 edges | `workflow graph has *N* edges, which exceeds the limit of 1000` | `$` |
| Blank node id | `workflow graph node id must not be blank at nodes[*i*]` | `nodes[*i*]` |
| Duplicate node id | `workflow graph node id '*id*' is duplicated: first defined at nodes[*a*] and again at nodes[*i*]` | `nodes[*i*]` |
| Blank node type | `workflow graph node type must not be blank` | `nodes[*i*]` |
| Unknown node type (not in the closed set) | `unknown workflow node type: *TYPE*` | `nodes[*i*]` |
| Dangling edge endpoint | `workflow graph edge edges[*j*] field 'to' references unknown node '*id*'` | `edges[*j*] field 'to'` |
| Self-referencing edge | `workflow graph edge edges[*j*] is a self-reference (from and to are '*id*')` | `edges[*j*]` |
| Duplicate edge | `workflow graph edge edges[*j*] duplicates an earlier edge (*from* -> *to*)` | `edges[*j*]` |
| Cycle | `workflow graph contains a cycle (a workflow graph must be an acyclic DAG)` | `$` |
| Wrong `schemaVersion` | `unsupported workflow graph schemaVersion '*V*'; expected '1.0.0'` | `schemaVersion` |

A cyclic graph with a dangling endpoint reports the dangling endpoint **first**, then the cycle —
the acyclicity check runs only after every edge resolves.

## 6. Invalid examples — what gets rejected

These payloads each fail the current validator. The output is the real message set from
`WorkflowGraphParser#collectIssues`.

**Unknown (non-closed) node type — `SCRIPT` is executable code, so it is not allowed:**

```json
{ "schemaVersion": "1.0.0", "nodes": [ { "id": "script-1", "type": "SCRIPT" } ], "edges": [] }
```
```
FAIL   [nodes[0]] unknown workflow node type: SCRIPT
```

**Cycle (`plan-1 → tool-1 → plan-1`):**
```
FAIL   [$] workflow graph contains a cycle (a workflow graph must be an acyclic DAG)
```

**Dangling edge endpoint (`to: "does-not-exist"`):**
```
FAIL   [edges[0] field 'to'] workflow graph edge edges[0] field 'to' references unknown node 'does-not-exist'
```

**Self-referencing edge (`plan-1 → plan-1`):** reported as a self-reference *and* (because it is a
one-node cycle) as a cycle.
```
FAIL   [edges[0]] workflow graph edge edges[0] is a self-reference (from and to are 'plan-1')
[$]    workflow graph contains a cycle (a workflow graph must be an acyclic DAG)
```

**Duplicate edge (`plan-1 → finalize-1` twice):**
```
FAIL   [edges[1]] workflow graph edge edges[1] duplicates an earlier edge (plan-1 -> finalize-1)
```

**Duplicate node id (`plan-1` twice):**
```
FAIL   [nodes[1]] workflow graph node id 'plan-1' is duplicated: first defined at nodes[0] and again at nodes[1]
```

**Wrong `schemaVersion` (`"2.0.0"`):**
```
FAIL   [schemaVersion] unsupported workflow graph schemaVersion '2.0.0'; expected '1.0.0'
```

## 7. End-to-end: from graph to persisted version

A graph enters the platform and is validated before it reaches storage:

1. **Preview** — `POST /api/v1/workflows/{workflowId}/validate` (read-only, `S05-13`) sends the raw
   `graph_json` body (untrusted *data*, never executed) to
   `WorkflowGraphParser#collectIssues`. It never creates a version or an audit row; it returns
   `valid=false` with the full list of `(path, message)` violations so the client can fix the
   problem before pinning.
2. **Create + activate** — `POST /api/v1/workflows/{workflowId}/versions` creates a `DRAFT` version
   and `POST …/versions/{version}/activate` transitions it `DRAFT → ACTIVATED` (S05-12). The graph
   is serialized through `WorkflowGraphCodec` (ADR-009) under a size bound and pinned as the
   version's SHA-256 `checksum`. The validation gate (`WorkflowGraphValidator#validate`) runs on
   create: a dangling edge, unknown type, cycle or oversized graph throws `InvalidGraphException` →
   HTTP 400, so an invalid graph never becomes an immutable, activated version.
3. **Immutable after activation** — once `ACTIVATED`, a version never changes (invariant 13); a new
   version is the only way forward.

## 8. Verifying the examples yourself

Every example in §4 and §5 passes (or is rejected for the documented reason) by the **real**
validator that the project ships. The DSL validator is a pure domain gate in the `workflowdefinition`
module — it never touches the database, Kafka or a model — so the authoritative way to verify a
graph is the project's own test suite, which runs against the built classes and the canonical
fixtures in [`contracts/workflow-graph/`](../contracts/workflow-graph/):

```bash
./gradlew :apps:agentflow-app:test \
  --tests "com.example.agentflow.workflowdefinition.persistence.WorkflowGraphParserTest" \
  --tests "com.example.agentflow.workflowdefinition.domain.WorkflowGraphValidatorTest" \
  --tests "com.example.agentflow.workflowdefinition.persistence.WorkflowGraphTopologyTest" \
  --tests "com.example.agentflow.workflowdefinition.application.GraphValidationServiceTest"
```

These four suites cover the parser (`collectIssues`), the domain structural gate, acyclicity, and
the `POST …/validate` application path. A confirmed run in this change:

| Suite | Tests | Failures |
|---|---|---|
| `WorkflowGraphParserTest` | 21 | 0 |
| `WorkflowGraphValidatorTest` | 20 | 0 |
| `WorkflowGraphTopologyTest` | 1 | 0 |
| `GraphValidationServiceTest` | 4 | 0 |

The canonical fixtures (`valid/example-linear.json`, `valid/example-branching.json`, and the
`topology/invalid-*` and `invalid/` counter-examples) are asserted in these suites. The exact
`nodes[i]` / `edges[j] field 'to'` / `$` / `schemaVersion` JSON paths in §5 are produced by
`WorkflowGraphValidator#collectIssues(WorkflowGraph)`, and the throwing variants
(`parse()` / `validate(...)`) fail on the first problem as `InvalidGraphException` → HTTP 400.

## 9. Notes and invariants

- **Closed node set.** `SCRIPT` and any other type are rejected (S05-07). A node type is compared
  against `WorkflowNodeTypeRegistry`, never executed. Adding a type is a new ADR + a new registry
  entry, not a config change.
- **Data, not code.** `config` is validated for shape only and treated as untrusted input to the
  execution layer. The graph declares *what* runs and in *what order*; it does not contain logic.
- **Deterministic topology.** For a valid (acyclic) graph `WorkflowGraphValidator#topologicalOrder`
  returns a stable node ordering (Kahn's algorithm with a definition-order tie-break), so execution
  order is reproducible for a given input.
- **Size bounds** (`maxItems: 200` nodes, `1000` edges) bound graph size and downstream cost
  (S05-03, S05-06); a graph over either limit is rejected before it reaches storage.
