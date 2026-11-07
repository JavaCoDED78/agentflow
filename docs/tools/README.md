# Tool authoring guide

How to add a new **safe** tool to AgentFlow. Owner module: `toolexecution`
(typed tool registry, permission/budget check, timeout and JSON Schema-validated results).
This document is the single source of truth for tool authoring; the module rules live in
[`apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/AGENTS.md`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/AGENTS.md),
the shared vocabulary in [`docs/domain-glossary.md`](../domain-glossary.md) ("Tools" section), and the
machine-readable contract shape in
[`contracts/`](../../contracts/).

A **tool** is a registered, allowlisted, typed action the executor may call: a defined
`(name, version)`, an input JSON Schema, an output JSON Schema, required permission scopes, a risk
class, a per-call timeout, a retry policy and a human-approval requirement. Unknown tools are never
called.

> This guide never shows arbitrary code execution, shell access, dynamic class loading or
> model-controlled registration. Those are forbidden by invariants and by the root `AGENTS.md`
> ("Forbidden"), and a tool that needs them does not belong on this platform.

## 1. The anatomy of a call

Every tool call passes through the same pipeline, in this order. A call that fails any gate never
reaches the next one.

```mermaid
flowchart TD
    A[Resolve (name, version) via ToolRegistry] --> B[Invocation policy guard: ToolInvocationPolicy]
    B -->|ALLOWED| C[Input schema gate: SchemaValidator]
    B -->|DENIED| X[Call never starts: no history, no audit]
    C -->|valid| D[Bounded executor: BoundedToolExecutor]
    C -->|invalid| Y[SchemaValidationException before any call]
    D -->|answered in time| E[Output schema gate: SchemaValidator]
    D -->|tool_timeout / concurrency_limited| Z[Retryable, bounded backoff]
    E -->|conforms| F[Result accepted; step can succeed]
    E -->|violation| V[SchemaValidationException; result never accepted]
    D -. every concluded call .-> G[Invocation history + audit for side-effect calls]
```

| Stage | Component | What it decides | On failure |
|---|---|---|---|
| Resolve | `ToolRegistry.resolve(name, version)` | A descriptor is registered for the pair | `Optional.empty()` — unknown tool is denied, never invoked |
| Policy | `ToolInvocationPolicy.decide(...)` | Risk class known, caller holds every required scope **now**, human approval attested when required | `PermissionDecision.DENIED` with a stable `reason()` (`unknown_tool`, `missing_scope`, `unknown_risk_class`, `missing_approval`) |
| Input gate | `SchemaValidator.validate(argument)` | The parsed argument conforms to `ToolDescriptor.inputSchema()` (plus payload node/depth limits) | `SchemaValidationException` with a stable `reason()` (`type-mismatch`, `missing-required`, `unknown-property`, `payload-too-large`, `payload-too-deep`) — **before any side effect** |
| Bounded call | `BoundedToolExecutor.execute(...)` | The call answers within `descriptor.timeout()` under the global / per-tenant concurrency caps | `ToolExecutionThrottleException` with `reason()` `tool_timeout` (ambiguous, retryable — invariant 6) or `concurrency_limited` (rejected before start, retryable); the tool's own fault is rethrown unchanged |
| Output gate | `SchemaValidator.validate(result)` | The produced result conforms to `ToolDescriptor.outputSchema()` | `SchemaValidationException` — the result is never accepted or checkpointed as a success |
| History | `ToolInvocationHistoryService` (the `ToolInvocationListener` seam) | Exactly one metadata row per concluded call: `(name, version)`, attempt, latency, outcome (`SUCCEEDED` / `THROTTLED` / `TIMED_OUT` / `FAULTED`), `step-run:` output reference | Never changes the call outcome; never stores arguments, payload or exception messages |
| Audit | `AuditService` via the history service | An `audit_events` record for every **started** call whose risk class is not `READ_ONLY` | A read-only call changes nothing; a throttled call never started, so neither is audited |

The pipeline is exercised end to end by
[`ToolErrorMatrixTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/toolexecution/application/ToolErrorMatrixTest.java)
(S08-14); every row of the error matrix gives exactly one retry / state / audit result.

## 2. The registration contract: `ToolDescriptor`

A tool is described by one immutable
[`ToolDescriptor`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/ToolDescriptor.java).
The canonical constructor validates every field, so an invalid descriptor can never reach a call.

| Field | Rule |
|---|---|
| `id` | Stable, unique identifier for this definition (audit/persistence); non-blank |
| `name` | The callable name the Planner refers to; non-blank |
| `version` | Non-blank; `(name, version)` is the unique registry key — there is no implicit "latest" |
| `description` | Human-readable prose the Planner reads to pick a tool. **Metadata, never authorization** — a rich description never grants a scope the caller lacks (ADR-006, invariant 7) |
| `inputSchema` | Machine-readable JSON Schema (draft 2020-12) the argument is validated against; required |
| `outputSchema` | Machine-readable JSON Schema the result is validated against; required |
| `requiredScopes` | Permission scopes this call needs. An **empty set means "no side effect is permitted"**, not "the description says it is safe"; stored unmodifiable |
| `riskClass` | `READ_ONLY`, `WRITE` or `EXTERNAL_SIDE_EFFECT` ([`ToolRiskClass`](../../apps/agentflow-app/src/main/java/com/example/agentflow/agentcatalog/domain/ToolRiskClass.java)); an unknown risk class is denied by default |
| `timeout` | Per-call bound, strictly positive; enforced by the bounded executor |
| `retryPolicy` | Bounded backoff: `RetryPolicy.of(maxAttempts, baseDelay, multiplier, maxDelay, jitter)`; `RetryPolicy.ofDefaults()` is 3 attempts, 1 s base, ×2 growth, 60 s cap, full jitter |
| `requiresHumanApproval` | `true` for a `WRITE` / `EXTERNAL_SIDE_EFFECT` whose action must pause for a human decision; on the automatic path such a call is always denied with `missing_approval` until the approvals store (S11) attests a grant |

Registration is **platform-only and happens once at startup**: the `toolRegistry` bean seeds the
built-in descriptors from
[`BuiltInTools`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/application/BuiltInTools.java)
via
[`ToolRegistryConfiguration`](../../apps/agentflow-app/src/main/java/com/example/agentflow/config/ToolRegistryConfiguration.java).
There is no HTTP endpoint or API that a Planner or a user can call to register a tool — dynamic
registration by the LLM or a user is impossible (root `AGENTS.md`, "Forbidden"). A duplicate
`(name, version)` registration throws `DuplicateToolRegistrationException` out of the `@Bean`
factory, so the Spring context fails to start.

## 3. Step by step: adding a new tool

1. **Implement the tool logic in `toolexecution.domain`.** Pure, dependency-free, no Spring, no I/O:
   reads neither time nor randomness — inject a `Clock` / `Supplier` seam if it needs them
   (invariant 10). Guard the public entry point with explicit
   `IllegalArgumentException` checks (never `Objects.requireNonNull`, which throws the
   unclassifiable `NullPointerException`), and fail with a typed exception carrying a **stable,
   machine-readable reason code** (like `CalculationException.reason()`), never with a free-text
   message a caller must parse.
2. **Write strict schemas.** Build the input/output JSON Schemas as draft 2020-12 documents with
   `required` and `additionalProperties: false`, and hold them as
   [`JsonSchema`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/JsonSchema.java)
   values (`JsonSchema.of(Map)`). A strict contract rejects unknown arguments before the tool body
   ever runs. (The shipped v1 calculator/report descriptors use a permissive bare-object root; the
   `ticket` descriptor shows the strict shape to copy — see §5.)
3. **Add the descriptor factory** to `BuiltInTools` (or a sibling factory class) and include it in
   `builtinDescriptors()` so the registry seeds it at startup.
4. **Wire the executor adapter by name.** The runtime adapter for a tool is a
   [`ToolInvocation<T>`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/application/ToolInvocation.java)
   lambda keyed by the descriptor `name`, composed as the pipeline in §1: resolve the descriptor,
   re-check `ToolInvocationPolicy`, validate the input, run the body through
   `BoundedToolExecutor`, validate the output. The `TOOL_CALL` `StepHandler` (workflowexecution
   SPI) is the production composer; see `ToolErrorMatrixTest#invokeAutomatic` for the canonical
   composition order.
5. **Choose the risk class honestly.** `READ_ONLY` observes data only (repeatable, no scope, no
   approval). `WRITE` changes tenant-scoped state; `EXTERNAL_SIDE_EFFECT` acts on the outside world
   in a way the platform cannot undo. Anything beyond `READ_ONLY` must declare its `requiredScopes`
   and set `requiresHumanApproval` — and the side effect must be **idempotent or guarded**, because
   delivery is at-least-once and a retried call can repeat (invariant 3 / 6; the timeout outcome is
   ambiguous by design). Never claim exactly-once.
6. **Keep bounds in configuration, not in code.** The pool and the caps come from
   `agentflow.tools.executor.pool-size` (default 8), `global-max-concurrency` (default 32) and
   `per-tenant-max-concurrency` (default 8); the per-tool `timeout` is part of the descriptor. A
   misconfigured cap fails boot at the `BoundedToolExecutor` constructor.
7. **Test.** snake_case test methods, DB-free unit tests over the pure tool plus the pipeline rows
   of the error matrix (denial before side effect, invalid input before the call, ambiguous timeout,
   overload, transient flap bounded by `RetryPolicy`, permanent fault keeps its own type). No H2, no
   fake repository.
8. **Document.** Update this guide's reference sections if you changed a shipped tool, the module
   `AGENTS.md` if ownership changed, `docs/domain-glossary.md` if a shared term changed, and
   `CHANGELOG.md`.

## 4. Reference tool: `calculator` (v1)

The safe arithmetic evaluator
([`Calculator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/Calculator.java),
S08-05) — the template for a read-only tool.

| Property | Value |
|---|---|
| `id` | `tool-calculator-1` |
| `name` / `version` | `calculator` / `1` |
| `riskClass` | `READ_ONLY` |
| `requiredScopes` | `[]` (no side effect) |
| `timeout` | 5 s |
| `retryPolicy` | defaults (3 attempts, 1 s / ×2 / 60 s cap, jitter) |
| `requiresHumanApproval` | `false` |

Input example (conforms to the registered input schema):

```json
{ "expression": "2 + 3 * 4" }
```

Result: `14`. The evaluator is a **closed numeric dialect** — `+ - * /`, parentheses and
decimal/scientific literals over exact `BigDecimal` operands, parsed by a self-contained
recursive-descent parser. There is no `eval`, no shell, no reflection and no class loading, so an
untrusted Planner cannot turn an argument into code (invariant 7). Division is bounded to 34
fractional digits (half-up) so `1 / 3` is deterministic; every intermediate result is magnitude-bounded
to `1E1000`; divide-by-zero and malformed input fail with stable reason codes
(`division-by-zero`, `invalid-token`, `empty-expression`, `overflow`). Proof:
[`CalculatorTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/toolexecution/domain/CalculatorTest.java) (29 tests).

## 5. Reference tool: `report` (v1)

The safe report formatter
([`ReportFormatter`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/ReportFormatter.java),
S08-06) — the template for a read-only tool that renders **untrusted content**.

| Property | Value |
|---|---|
| `id` | `tool-report-formatter-1` |
| `name` / `version` | `report` / `1` |
| `riskClass` | `READ_ONLY` |
| `requiredScopes` | `[]` |
| `timeout` | 10 s |
| `retryPolicy` | defaults |
| `requiresHumanApproval` | `false` |

Input example (conforms to the registered input schema):

```json
{
  "format": "MARKDOWN",
  "facts": [
    {
      "text": "Revenue grew 12% year over year",
      "citations": [
        { "source": "Q3 earnings report", "url": "https://example.com/q3" }
      ]
    }
  ]
}
```

Result (Markdown): a `# Report` header plus one `-` bullet per fact with citation sources inline.
Every dynamic value is HTML-escaped in both output formats (`<script>` becomes `&lt;script&gt;`), so
a prompt-injected fact can never become executable markup; the report is capped at 4000 characters,
dropping whole facts and flagging the result `truncated`, so the output shape is stable for empty,
valid and oversized input. Proof:
[`ReportFormatterTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/toolexecution/domain/ReportFormatterTest.java) (19 tests).

For the strict-schema shape to copy for a **new** tool, see the `ticket` descriptor in
`BuiltInTools` (`tool-ticket-lookup-1`, `ticket` / `1`): a required `ticketId` string with
`additionalProperties: false` in, and a closed set of answer fields out.

## 6. Hard rules (never break)

- **No arbitrary code execution.** No `eval`, shell, scripting engine, reflection trick or class
  loading. A tool is a closed dialect over declared inputs — if the task needs general code, it is
  not a tool.
- **No dynamic registration.** The model or a user can never register, upgrade or unregister a
  tool; registration is a startup-time platform operation.
- **The description is not authorization.** Scopes, risk class and approval are decided
  server-side from the verified permission context; the Planner only *chooses* among registered
  tools (ADR-006).
- **Tenant identity comes only from the verified context.** `ToolExecutionContext` is built
  server-side (HTTP security context or the stored run re-checked against PostgreSQL) and carries
  no secret — no JWT, API key, token, full prompt or document. A tool never trusts a tenant or
  actor from its arguments.
- **Idempotency for side effects.** Delivery is at-least-once; a timed-out call is ambiguous (the
  effect may have completed). Make side effects idempotent or guarded, and declare the honest risk
  class — never claim exactly-once.
- **Stable failure tokens, redacted records.** History and audit store reason codes and bounded,
  redacted summaries — never exception messages (they can quote secrets), never arguments or
  payloads.
- **Bounded everything.** A timeout on every descriptor, bounded retries, bounded payloads
  (node/depth limits), bounded output size, concurrency caps. Untrusted input — model output,
  documents, tool arguments — is validated before use.
- **No surprise egress.** A tool that calls an external service is a separate design review
  (SSRF, allowlisted targets, timeouts); it is not covered by this guide.

## 7. Verification

The examples in §4–§5 are pinned to the real descriptors and tools by
[`ToolAuthoringGuideExamplesTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/toolexecution/application/ToolAuthoringGuideExamplesTest.java):
each example payload is validated against the registered input schema and executed through the real
domain tool, and the descriptor tables are asserted against the registered values — so this guide
cannot silently drift from the code. Run:

```bash
./gradlew :apps:agentflow-app:test --tests '*ToolAuthoringGuideExamplesTest'
```
