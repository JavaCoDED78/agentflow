# ADR-005. Versioned workflow DSL with no arbitrary code

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** S03-09 (workflow DSL graph validation; the versioned run/step execution engine is `S05`)
- **Supersedes:** none. One of the core domain/runtime ADRs (`ADR-005`…`ADR-007`, reserved in the
  plan §3).
- **Superseded by:** none

## Context

AgentFlow runs workflows authored by end users. A workflow is a plan that performs actions —
retrieve context, call an LLM, call a tool, ask a human to approve, evaluate, and finalize. If a
user could supply an arbitrary graph of arbitrary code, every workflow would be an unreviewed
arbitrary-code execution path, which is exactly what invariant 7 prohibits: "arbitrary user
supplied code is prohibited" and "the model must not change permissions, policies or allowlists".

The plan (§3) fixes the DSL as a **versioned JSON graph of typed steps**, and the domain glossary
pins the allowed node types (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`,
`LLM_EVALUATE`, `FINALIZE`). The `workflowdefinition` module persists one immutable row per version
(`workflow_definition_versions`, migration `V3_3_2`, `ADR-009`), so an activated version is
immutable (invariant 13): a new change is a new version, never an edit of an old one.

The acceptance criterion is that an invalid or unsafe graph is **rejected before** it is
serialized or activated: the DSL is a validated, typed graph of allowed steps, not a bag of
arbitrary code.

## Decision

A workflow is a **versioned, typed JSON graph of allowed steps.** Only the node types named in the
domain glossary are allowed (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`,
`LLM_EVALUATE`, `FINALIZE`); everything else is a validation boundary, never a runtime escape. The
graph is validated structurally by `WorkflowGraphValidator` before it is serialized, and the deeper
DAG validation — cycles, connectivity, the node graph, required fields and limits — is the DSL
validator's responsibility (`S09`), which is the authority for the allowed node set going forward.

Versioning is by design: the DSL is the `graph_json` payload of one immutable
`workflow_definition_versions` row (migration `V3_3_2`), addressed by a monotonic `version`. An
activated version never changes — a new graph is a new version (invariant 13). Cycles are not
permitted in the MVP; retries and repeated attempts are handled by the execution engine, not by the
graph.

No node type performs arbitrary code execution. A `TOOL_CALL` node invokes only an explicitly
allowlisted tool through the typed tool registry (invariant 7); an `LLM_PLAN` node only proposes a
plan that the server-side validation gate (`ADR-006`) checks; a `FINALIZE` node only writes the
result. The DSL is a validated, typed graph, not a programming language.

## Alternatives considered

1. **Allow arbitrary code nodes** (a `SCRIPT`/`JAVASCRIPT` node that runs user-supplied code).
   Pro: maximum flexibility. Con: turns every workflow into unreviewed arbitrary-code execution —
   the exact failure invariant 7 guards against. Rejected.
2. **Allow arbitrary node types.** Pro: more step kinds without code changes. Con: widens the
   attack surface and the validator; a new node type is itself an ADR-worthy decision (plan §3,
   "a new workflow node type"). Rejected as an open door.
3. **Versioned, typed graph of allowed steps (chosen).** Keeps the composition flexibility of a
   JSON graph but bounds it to a closed, validated set of steps and to one immutable row per
   version. Matches the plan's DSL choice and `ADR-009`.

## Consequences

- **Positive:**
  - A workflow can never be an arbitrary-code execution path; the dangerous surface is a closed,
    validated set of steps.
  - Immutable versions (`ADR-009`) give a stable, auditable record of exactly what an activated
    workflow did.
  - Tenant isolation (`ADR-007`) and the untrusted-planner gate (`ADR-006`) sit cleanly on top of a
    graph the backend fully controls.
- **Negative / operational:**
  - Adding a node type is a real change, not a config flip: it needs a new ADR (plan §3) and a new
    validator entry. The MVP keeps cycles disallowed.
  - The deep DAG validator (`S09`) is a later task; the structural gate (`WorkflowGraphValidator`)
    only checks node ids/types/blankness and the closed type set — deeper cycle/connectivity checks
    are not yet in the MVP.
- **Follow-ups:**
  - `S09` owns the DSL validator (cycles, connectivity, required fields, limits) as the authority
    for the allowed node set going forward.
  - `S05` owns the versioned run/step execution engine that reads the activated version and
    advances it through the state machine with leases, retries and recovery (`ADR-003`).
