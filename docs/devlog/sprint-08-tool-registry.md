# Changelog


# Sprint 8 — Tool registry



## S08-01 — The registered-tool contract (S08-01).


- **The registered-tool contract (S08-01).** The shape of a *registered tool* is now a typed, immutable value object, `ToolDescriptor`, in `toolexecution.domain` — the value that closes the loop between the tool allowlist (which tool/permission a run is allowed to use) and the executor (which runs it and how). A tool is only callable when it has a {@link ToolDescriptor#riskClass()} the caller is permitted to use, the caller holds every {@link ToolDescriptor#requiredScopes()} scope, and the call passes the {@link ToolDescriptor#inputSchema()} / {@link ToolDescriptor#outputSchema()} validation — none of which is inferred from the human-readable {@link ToolDescriptor#description()}. The `ToolDescriptor` is a `record` with value equality, so the registry can dedupe and cache it (S08-02), and its canonical-constructor validation guarantees an invalid descriptor can never reach a call (non-blank `id`/`name`/`version`, present schemas, present scopes/risk/retry, a strictly positive timeout).

- **`ToolRiskClass` — the tool's blast radius, owned by the catalogue.** The three-class enum (`READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT`) that classifies a tool by the change it can make to external state now lives in `agentcatalog.domain`, not `toolexecution.domain`. The catalogue is the *owner* of the tool allowlist and its risk taxonomy (module-map edge 3 / `docs/architecture/module-api.md`); keeping the risk model there is what lets the catalogue own "which tools exist and how risky they are" while `toolexecution` only *reads* a risk class the catalogue assigned. `toolexecution` depends on `agentcatalog` (edge 3) to carry `ToolRiskClass` into the `ToolDescriptor`, so the model is defined once, owned once, and never duplicated across the boundary. An unknown risk class is denied by default (`S11-01`, ADR-006): the executor trusts the catalogue's closed set, not the Planner.

- **`JsonSchema` — the machine-readable contract, held as an immutable value.** A tool's `inputSchema`/`outputSchema` are held as an immutable, dependency-free value `JsonSchema` (a `Map<String, Object>` root, defensively copied and made unmodifiable), not parsed or validated in the domain. The domain holds the *shape*; schema validation of call arguments happens at the application/persistence boundary (`S08-02`). It rejects a `null` root with `IllegalArgumentException` (a tool without a schema cannot be validated, so it is not registered) and is value-equal so two descriptors with identical schemas compare equal.

- **`RetryPolicy` — how a failed call is retried, owned by the executor.** The retry parameters (max attempts, base delay, multiplier, max delay, jitter) move to `toolexecution.domain.RetryPolicy` as the tool-layer's answer to "how do I retry a failed call" — the tool-level sibling of `workflowexecution.domain.RetryPolicy` (`S07-10`), which still drives a step's *workflow* retry. This is a value object (value equality, no dependencies), carrying the same reproducible, bounded backoff the workflow policy uses, plus the same injection seams so it is testable without the system clock or `Math.random` (invariant 10). It is owned by `toolexecution` because the tool registry decides a *tool's* retry policy; the workflow-level policy still lives in `workflowexecution`.

#### What changed
- `agentcatalog.domain.ToolRiskClass` (new) — the three-value enum (`READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT`); the blast-radius taxonomy owned by the catalogue.
- `agentcatalog.domain.ToolRiskClassTest` (new, unit, DB-free) — the enum has exactly the three canonical values and nothing else, and each risk class is reachable and named.
- `toolexecution.domain.RetryPolicy` (new) — the tool-level retry policy value object: value-equal, dependency-free, reproducible bounded backoff with injected jitter/time, `of(...)`/`ofDefaults()` factories, `delayMillisForAttempt(attemptsUsed)`, `isRetryAllowed`, `nextAttemptTime`, and value equality.
- `toolexecution.domain.RetryPolicyTest` (new, unit, DB-free) — the exponential backoff progression, the `maxDelay` cap, the constant-multiplier edge, jitter reproducibility over 6 × 500 real draws, `isRetryAllowed` bounded by `maxAttempts`, the out-of-range jitter rejection, `nextAttemptTime` from an injected instant, and every invalid-construction case.
- `toolexecution.domain.JsonSchema` (new) — an immutable value wrapping the JSON Schema draft 2020-12 root as a `Map<String, Object>`; defensive copy, unmodifiable root, `null` root rejected, value-equal.
- `toolexecution.domain.JsonSchemaTest` (new, unit, DB-free) — round-trip, unmodifiable root, mutation does not change the value, and `null` root rejection.
- `toolexecution.domain.ToolDescriptor` (new) — the registered-tool contract: the canonical `record` with validated constructor, the `of(...)` factory, `ofDefaults()`, and the non-mutating scopes copy.
- `toolexecution.domain.ToolDescriptorTest` (new, unit, DB-free) — the descriptor carries its schemas and risk class, refuses a blank identity / a null risk class / scopes / retry policy / non-positive timeout, a missing machine-readable schema is rejected, the allowed-scope set is copied (mutating the source set does not change the descriptor), and value equality holds.

#### Why
Before this task the only thing standing between the allowlist (S08-01's `toolexecution.tooling` work) and the executor was an implicit set of field names — nothing typed, validated, or shared, so the "which tools may a run use" decision could not be reasoned about or tested in isolation. `S08-01` makes that decision explicit as one contract. `ToolRiskClass` is owned by the catalogue because the catalogue owns the allowlist and its risk taxonomy (the module-map edge 3 boundary); moving the risk model into `agentcatalog` keeps it defined once, and `toolexecution` reads it through the permitted edge rather than re-declaring it. The description is deliberately *not* authorization: the Planner is an untrusted planner (ADR-006) that reads `description()` to pick a tool, but the permission check (`requiredScopes` + `riskClass`) is decided server-side from the verified permission context, and an empty scope set means "no side effect permitted regardless of the description" (invariant: the model is not an authorization authority). `JsonSchema` and `RetryPolicy` are held as immutable values (no schema-parsing or sleep in the domain) so they are comparable, cacheable, and testable with injected time and randomness (invariant 10), and the canonical-constructor validation keeps an invalid descriptor from ever reaching a call.

#### Verification
- `:apps:agentflow-app:compileJava` — green.
- `:apps:agentflow-app:compileTestJava` — green.
- `:apps:agentflow-app:test --tests "com.example.agentflow.agentcatalog.domain.ToolRiskClassTest" --tests "com.example.agentflow.toolexecution.domain.*"` — **BUILD SUCCESSFUL, 32/32** (`ToolRiskClassTest` 4/4, `JsonSchemaTest` 6/6, `RetryPolicyTest` 12/12, `ToolDescriptorTest` 10/10; all DB-free).
- `:apps:agentflow-app:check` — **BUILD SUCCESSFUL, 0 failures** across unit + architecture + formatting + OpenAPI contract gates; `spotlessApply` applied.
  - Architecture gate `ArchitectureBoundaryTest` ran active (`tests=1, skipped=0`, `failures=0`) — the new `toolexecution → agentcatalog` dependency edge is on the allow-list, so the module boundary holds.
  - `NoCyclicModuleDependenciesTest` ran active (`tests=1, skipped=0`, `failures=0`) — the new edge is acyclic.
  - `PackageDependencyGraphTest` ran active (`tests=4, skipped=0`, `failures=0`).

#### Tests
`ToolRiskClassTest` (4), `RetryPolicyTest` (12: backoff progression, `maxDelay` cap, constant multiplier, jitter reproducibility over 6 × 500 real draws, `isRetryAllowed` bounded by `maxAttempts`, out-of-range jitter, `nextAttemptTime` from an injected instant, value equality, invalid construction), `JsonSchemaTest` (6: round-trip, unmodifiable root, mutation isolation, null-root rejection), `ToolDescriptorTest` (10: carries schema + risk, blank-identity rejection, null risk/scope/retry rejection, missing schema rejection, non-positive timeout rejection, non-mutating scopes copy, value equality).

#### Docs
`docs/devlog/sprint-08-tool-registry.md` — this devlog section; `CHANGELOG.md` `[Unreleased] → Added`.
