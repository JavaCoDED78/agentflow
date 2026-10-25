# ADR-006. The model is an untrusted planner, never an authorization authority

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** S09 (Planner + server-side validation gate)
- **Supersedes:** none. One of the core domain/runtime ADRs (`ADR-005`…`ADR-007`, reserved in the
  plan §3).
- **Superseded by:** none

## Context

AgentFlow's AI layer is an LLM that proposes *plans* — typed graphs of steps to run
(`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`, `FINALIZE`). The
model is powerful and persuasive, but it is also an **untrusted input**: it can be confused,
hallucinated, prompt-injected through a document it retrieves, or deliberately steered by an
attacker to take an action the user did not authorize.

Invariant 7 states the whole policy: "AI is an untrusted planner. Model output → typed plan →
server-side validation gate → executor. The model must not change permissions, policies, or
allowlists. Permissions and allowlists are decided by the backend." `AGENTS.md` invariant 1
(tenant isolation) and invariant 13 (immutability) constrain the answer too: a model cannot widen
its own tenant scope, and it cannot change an activated workflow.

The acceptance criterion is that the model only ever *proposes* and the backend only ever
*authorizes and executes*. A model that can add a tool to its allowlist, change a permission, read
a secret, or bypass a budget is a security failure, not a feature.

## Decision

The model is a **planner only.** It returns a schema-constrained plan (through the `ChatModelPort`,
never the raw provider). That plan is validated **server-side, before** any step runs: a
`ValidationGate` checks the DAG, the caller's `Permission` (via the access matrix, `S04-08`), the
tool allowlist, the budget, and the step/limit caps. Only validated steps reach the executor, which
runs them through the typed tool registry (`ADR-005`) and records cost/audit.

The model is deliberately **not** an authorization authority:

- it cannot add a tool to the allowlist, remove one, or introduce a new one — tool types are a
  closed set, and a new one is an ADR-worthy decision (plan §3);
- it cannot grant, change, or bypass a `Permission`; permissions come only from the verified
  security context (invariant 1, `ADR-007`);
- it cannot read a secret; secrets are managed, never surfaced to the model;
- it cannot bypass the cost/step budget; the budget is enforced server-side.

Planning, authorization, execution and verification are separate layers (invariant 7). The model
occupies only the first.

## Alternatives considered

1. **Trust the model to self-limit** (the model "knows" not to touch security). Con: the model is
   untrusted by construction; this is the prompt-injection / over-authorization failure the
   invariant guards against. Rejected.
2. **Let the model build the whole workflow, including new tools.** Con: arbitrary tool
   registration by the LLM or a user is explicitly forbidden (`AGENTS.md`); a new tool is a
   security-sensitive addition. Rejected.
3. **Planner + server-side validation gate (chosen).** The model proposes; the backend
   authorizes and executes. Matches invariant 7, `ADR-007` and `ADR-005`.

## Consequences

- **Positive:**
  - The dangerous surface stays closed: unknown tools and unknown risk classes are denied
    (invariant 7), and the model cannot widen its own privileges.
  - Prompt injection that reaches through a retrieved document is content the validator sees but
    the executor never obeys without a backend permission.
  - The model can be swapped or retried behind the `ChatModelPort` without touching the security
    model.
- **Negative / operational:**
  - The validation gate must actually reject an over-reaching plan, or the gate is on paper. The
    `ai` module is currently a skeleton (`Planner` and `ValidationGate` are documented in
    `docs/architecture/module-api.md` but not yet implemented), so this is the follow-up that must
    land for `ADR-006` to be in force by code, not just by doc.
  - Bounding steps, elapsed time and cost keeps a runaway plan cheap (invariant 7).
- **Follow-ups:**
  - `S09` builds the `Planner` and the `ValidationGate` in the `ai` module (documented in
    `module-api.md`), the typed tool registry in `toolexecution`, and the failure tests (prompt
    injection in a document, a tool reaching a private IP, the model asking for a forbidden tool).
  - The `ai` module owns no table (`ADR-002`); it exposes the `ChatModel`/`Embedding` ports.
