# ADR-017. Tool risk classification: a closed, deny-by-default vocabulary owned by the catalogue

- **Status:** Accepted
- **Date:** 2026-11-14
- **Task:** S11-01 (Sprint 11 — human approval, MCP and security of agent actions)
- **Supersedes:** none
- **Superseded by:** none

## Context

AgentFlow's AI layer is an untrusted planner (`ADR-006`). The model proposes tool calls; the
backend authorizes and executes them, and the model must never widen its own privileges
(invariant 7). A tool call's dangerous surface is not its name or its human-readable description —
those are prose the planner reads (a rich description never grants a scope the caller lacks). The
property that decides *how much* side effect a tool can cause is its **risk class** (glossary
"Tool risk class"; `tool_definitions.risk_class`), which the permission check combines with the
caller's scopes to decide whether the call is allowed.

The forces:

- A tool that is not classified cannot be authorised — there is nothing to compare the caller's
  scopes against, so it must be refused.
- An attacker (or a prompt-injected document) can try to describe, register or reference a tool
  whose danger is hidden. The classification must therefore be a **closed** set, and an unknown
  value must be **denied by default**, never guessed.
- The classification is defined once and read across a module boundary: the tool catalogue owns
  the vocabulary (`agentcatalog`), and the executor reads the risk class a tool carries
  (`toolexecution` depends on `agentcatalog` — module-map edge 3).

## Decision

Tool risk classification is a **closed, deny-by-default vocabulary** defined once in the owning
module:

- The set is exactly three ordered values — `READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT` — declared
  as the closed enum `com.example.agentflow.agentcatalog.domain.ToolRiskClass` in `agentcatalog`
  (the catalogue owns the tool allowlist and its risk taxonomy). An unknown value is unreachable:
  `ToolRiskClass.valueOf("...")` throws for anything outside the three, so a descriptor can never
  hold an unclassified value.
- A `ToolDescriptor` (owned by `toolexecution`, carried across edge 3) requires a non-null
  `riskClass`; the canonical constructor rejects an unclassified descriptor with a contract
  `IllegalArgumentException` before it can be registered or called.
- The invocation policy guard (`ToolInvocationPolicy`, the single pre-call decision point,
  `S08-09`) carries `REASON_UNKNOWN_RISK_CLASS` as the named denial for an unclassified tool — a
  defensive fail-safe that can only fire if the type safety is ever bypassed (for example a
  `risk_class` string loaded from the database), so the deny-by-default holds in depth.

A tool that is not classified **cannot be registered and is therefore not available** — it
resolves to nothing in the `ToolRegistry` and the guard denies it. The model cannot propose a risk
class; it proposes a tool by name, and the backend decides the risk.

## Alternatives considered

1. **Trust the description or the model to imply the risk.** Con: the description is untrusted
   prose (ADR-006, invariant 7) and the model is not an authorization authority; a rich
   description would silently grant a side effect the caller lacks. Rejected.
2. **Risk class as a free-form string.** Con: without a closed set there is nothing to deny a
   unknown value against — "unknown" is only meaningful when the allowed values are fixed. Rejected.
3. **Closed enum + deny-by-default enforced at descriptor construction (chosen).** Matches
   invariant 7, `ADR-006` and `ADR-007`; the classification is defined once, owned once, enforced
   before a tool can become available.

## Consequences

- **Positive:**
  - The dangerous surface is closed: a tool must carry one of the three known risk classes to be
    available, and an unknown class is denied by default (invariant 7).
  - Ownership is unambiguous: the catalogue owns "which tools exist and how risky they are"
    (module-map edge 3); `toolexecution` only reads the risk class the catalogue assigned.
  - The model cannot widen its privileges through a tool's risk — classification is a backend
    decision, never a model input.
- **Negative / operational:**
  - Adding a risk class changes a closed, cross-module vocabulary that is persisted (`risk_class`)
    and read across a module boundary. It is therefore a breaking change to the vocabulary: a new
    value is itself an ADR-worthy decision, and consumers (the guard's `reason`, the error
    taxonomy's `tool_policy_denial`) must be updated together with it.
  - The guard's `unknown_risk_class` path is defensive today — the constructor already prevents an
    unclassified descriptor, so a valid descriptor never reaches it. That is intentional (defense in
    depth for the persisted-load path, a follow-up), not dead code to remove.
- **Follow-ups:**
  - Persisting the allowlist (`tool_definitions`) — the load path converts the stored `risk_class`
    string back to the enum via `valueOf`, so an unknown stored value is rejected at load, mirroring
    this construction-time gate.
  - The human-approval lifecycle (`S11-02`…`S11-06`) builds on the same classification: a `WRITE` /
    `EXTERNAL_SIDE_EFFECT` action pauses on `HUMAN_APPROVAL`; `requiresHumanApproval` is the flag the
    guard checks, fed by the approvals store once it exists.
