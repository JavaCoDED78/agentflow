# Changelog


# Sprint 8 — Tool registry


## S08-04 — Output-schema validation (S08-04).

### What changed
The very same dependency-free [`SchemaValidator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/SchemaValidator.java) (`toolexecution.domain`) now serves its second role: it validates the tool's <em>result</em> against its machine-readable {@link ToolDescriptor#outputSchema()} (the same {@link JsonSchema} draft 2020-12 root) <em>after</em> the tool runs and <em>before</em> the step is marked SUCCEEDED — so a result that does not conform to the contract is never accepted, forwarded to the next step, or checkpointed. The engine is schema-agnostic (it validates any parsed value against any parsed schema), so the input (S08-03) and output (S08-04) gates share one implementation, one {@link SchemaValidationException}, and one set of protective payload limits, a maximum node count and a maximum nesting depth. It distinguishes a <b>schema mismatch</b> — a produced result that fails the output schema, a deterministic contract failure — from a <b>transient execution error</b> the tool itself raises before producing a result: the output gate only runs on a produced result, so a transient failure short-circuits on the tool call and is classified separately by the retry classifier. On the first violation it throws a [`SchemaValidationException`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/SchemaValidationException.java) with the same stable `reason()` code (`type-mismatch`, `missing-required`, `unknown-property`, `payload-too-large`, `payload-too-deep`) the S08-03 engine uses. It reads no time, no randomness, no Spring or I/O — a pure, testable value (invariant 10). The class/method Javadoc of `SchemaValidator` and `SchemaValidationException` is updated to describe both roles.

### Why
The S08-04 plan task ("validate tool result before passing to the next step; limit size; distinguish schema mismatch and transient execution error") had no implementation yet. It closes the acceptance criterion: an invalid output is not marked as a successful step result. The engine already closed S08-03 with the very same code — the input gate validates {@code ToolDescriptor#inputSchema()} before the call — so the output gate reuses it rather than duplicating a schema engine (nothing a full schema engine would add only for novelty). The after-call gate belongs, conceptually, to the tool executor (the `TOOL_CALL` {@code StepHandler} / {@code ToolExecutionService} listed as the `toolexecution` public service in `docs/architecture/module-api.md`): the handler resolves the descriptor's {@code outputSchema}, validates the parsed result, and only returns {@code StepResult.succeeded(result)} when it conforms. That executor does not exist yet (built in S08-05–S08-08), so — exactly as S08-03 delivered the engine and documented the before-call gate for the executor to apply — S08-04 delivers the engine's output role, proves it, and documents the after-call gate for the later handler. The distinction from a transient error is inherent: the engine only runs on a produced result, and a transient tool failure (a provider timeout, a thrown error) never reaches it, so the two failures are different exception types the executor classifies differently.

### Verification
- Unit, DB-free, no model/Kafka/system clock: `SchemaValidatorTest` (27 tests — 6 new for S08-04) — a tool result matching its output schema is accepted; a type-mismatch, a missing required field and an unknown property (strict output contract) and an oversized result are all rejected with `type-mismatch` / `missing-required` / `unknown-property` / `payload-too-large` <em>before the step is marked succeeded</em> (the acceptance criterion, asserted via an {@code OutputResultGate} harness that runs the tool, validates the result, and only records SUCCEEDED on a conforming result); a tool that fails transiently (throws before producing a result) is reported as a transient error (`IllegalStateException`) and <em>not</em> a `SchemaValidationException`, proving the schema-mismatch / transient-error distinction.
- `:apps:agentflow-app:check` green — compilation, unit, architecture gate (package/module — the change adds no new dependency edge), spotless formatting (fixed with `spotlessApply`), and OpenAPI contract (`OpenApiContractCheckTest`); the new `domain` value adds no controller, so the contract does not drift. The architecture gates ran active, confirmed from the JUnit XML (`ArchitectureBoundaryTest`: `tests=1, skipped=0, failures=0`; `NoCyclicModuleDependenciesTest`: `tests=1, skipped=0, failures=0`; `PackageDependencyGraphTest`: `tests=4, skipped=0, failures=0`) — so the gate is inert-free, not silently skipped.

### Docs
- `CHANGELOG.md` (Unreleased → Added, newest-first).
- This file.
- `docs/architecture/module-api.md` — already lists `ToolExecutionService` (permission/budget check → invoke → JSON Schema result validation) as the `toolexecution` public service; the result-validation responsibility is S08-04 and is unchanged.
---

## S08-03 — Input-schema validation (S08-03).

### What changed
A pure, dependency-free [`SchemaValidator`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/SchemaValidator.java) (`toolexecution.domain`) that validates a parsed JSON value — the tool-call arguments — against the tool's machine-readable {@link ToolDescriptor#inputSchema()} (a {@link JsonSchema} draft 2020-12 root held as an immutable `Map` tree), so an invalid payload is blocked <em>before any tool side effect</em>. It is bound to a schema with `SchemaValidator.of(JsonSchema)` and injectable limits (defaults `DEFAULT_MAX_NODES = 1_000`, `DEFAULT_MAX_DEPTH = 20`). It validates the subset of JSON Schema a tool call needs: {@code type} matching for every JSON value type (object, string, integer, number, boolean, array, null — an {@code integer} schema accepts an integral value, including an integral double such as {@code 2.0}, but not a fractional {@code 2.5} or the string {@code "5"}), {@code required} fields, {@code properties} typing, {@code items} typing for arrays, and {@code additionalProperties: false} (the "strict contract" that rejects unknown properties; the default — absent or `true` — allows them), plus two protective payload limits, a maximum node count and a maximum nesting depth. On the first violation it throws a [`SchemaValidationException`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/domain/SchemaValidationException.java) carrying a stable {@code reason()} code (`type-mismatch`, `missing-required`, `unknown-property`, `payload-too-large`, `payload-too-deep`) so the executor can classify the failure without parsing the message. It reads no time, no randomness, no Spring or I/O — it walks the already-parsed `Map`/`List` tree exactly as `JsonSchema` holds its schema as a parsed tree — so it is a pure, testable value.

### Why
The S08-03 plan task ("validate types and required fields before the call; limit payload size/depth; in strict contracts reject unknown properties") had no implementation yet. This closes the acceptance criterion: an invalid payload is blocked before any side effect. It lives in `domain` (not `application`) because schema validation is a pure, dependency-free value that reads neither time nor randomness (invariant 10), so it is testable in isolation and has no I/O; `JsonSchema` and `ToolDescriptor` already live in `domain`, and `ToolDescriptor.inputSchema()` is exactly what `SchemaValidator` validates against. The registry (S08-02) is what hands `SchemaValidator` its schema — the planner/permission layers read a tool's schema **through** the descriptor — so validation stays close to the contract it validates, without coupling to the application port. The stable `reason()` is deliberate: the tool executor classifies the failure (S08-13) without string parsing.

### Verification
- Unit, DB-free, no model/Kafka/system clock: `SchemaValidatorTest` (21 tests) — `type` matching per JSON value (object/string/integer/number/boolean/array/null; integral `2.0` accepted by `integer`, `2.5` and `"5"` rejected), `required` fields (missing, blank-string, wrong type), `properties` typing, `items` typing for arrays, nested objects, strict-contract `additionalProperties: false` rejects an unknown property while the default allows it, payload node-count limit (`payload-too-large`), nesting-depth limit (`payload-too-deep`), injected limits, and the stable `reason()` code (`missing-required`); a recording executor proves a valid input reaches the tool while an invalid one is blocked before the call.
- `:apps:agentflow-app:check` green — compilation, unit, architecture gate (package/module), spotless formatting, and OpenAPI contract (`OpenApiContractCheckTest`); the new `domain` value adds no controller, so the contract does not drift.

### Docs
- `CHANGELOG.md` (Unreleased → Added, newest-first).
- This file.

---

## S08-02 — The deterministic tool registry (S08-02).

### What changed
An in-memory, deterministic [`ToolRegistry`](../../apps/agentflow-app/src/main/java/com/example/agentflow/toolexecution/application/ToolRegistry.java) (`toolexecution.application`), the single source of *which tools are registered*. The platform registers the built-in tool descriptors once at startup (via the `toolRegistry` `@Bean`, `config/ToolRegistryConfiguration.java`); the Planner chooses a tool **through** it and every permission check reads a tool's risk class and required scopes **through** it. A tool is therefore never callable unless it is registered.

Built around:
- `ToolRegistry` — `register(descriptor)`, `resolve(name, version)` → `Optional<ToolDescriptor>` (empty for an unknown pair), `containsTool`, `all()` (unmodifiable snapshot), `size`. Thread-safe (`ConcurrentMap`, atomic `putIfAbsent`). Holds descriptors **only** (metadata); the executors are registered separately by name in S08-05/S08-06/S08-08.
- `ToolKey` — the package-private `(name, version)` value key (record). The dedup key is the pair the Planner references, **not** the descriptor `id` (which stays a stable unique identifier on the descriptor for audit/persistence).
- `DuplicateToolRegistrationException` — extends `IllegalStateException`; a startup/config error, thrown by `register` on a duplicate `(name, version)`.
- `BuiltInTools` — the factory that seeds `calculator` (S08-05) and `report` (S08-06) descriptors at startup.

### Why
The S08-02 plan task ("register adapters at startup; reject duplicate ID/version; forbid dynamic registration by the LLM/user; deterministic registry") had no implementation yet. This closes the acceptance criteria:
- **Unknown tool is never called.** `resolve` returns `Optional.empty()` for any pair never registered; the planner/permission layers treat that as "not callable", so an unknown or never-registered tool cannot be invoked.
- **Duplicate registration clearly breaks startup.** A duplicate `(name, version)` throws `DuplicateToolRegistrationException` *from the `@Bean` factory method*, so the Spring context fails to start — a mis-wired registry is a configuration error that is visible, not silently kept (one of two tools).
- **No dynamic registration by the LLM/user.** There is **no HTTP endpoint**; registration is a platform operation at startup only, an invariant (`AGENTS.md` "Forbidden": dynamic tool registration by the LLM or a user).
- **Recovery.** The registry is in-memory with no external dependency (no DB), so it is safe in every profile (API role, DB-less skeleton). It holds no secrets, no prompt, no `tenantId`/`runId`; its `all()` snapshot is metric/log-safe.

### Verification
- Unit, DB-free, no model/Kafka/system clock: `ToolRegistryTest` (7 tests) — resolve by `(name, version)`; unknown **name** / wrong **version** / blank **version** all resolve empty (unknown → never called); duplicate `(name, version)` rejected (first descriptor still stands); two distinct versions are distinct tools; `all()` is an unmodifiable snapshot; `null` descriptor rejected; 16 threads registering concurrent distinct keys do not lose entries (and concurrent `resolve` never throws).
- Lightweight Spring-context test (no server, no DB, no Kafka): `ToolRegistryStartupTest` — the real `ToolRegistryConfiguration` boots the context seeded with the built-in tools and resolves them while an unknown one resolves empty; a duplicate registration from the `@Bean` factory fails context startup with `DuplicateToolRegistrationException` as the root cause.
- `./gradlew :apps:agentflow-app:check` green — compilation, unit, architecture gate (package/module), spotless formatting, and OpenAPI contract (`OpenApiContractCheckTest`); the new bean adds no controller, so the contract does not drift. `AgentflowApplicationTests` (`@SpringBootTest`, default profile) still loads the full context with the new bean.

### Docs
- `CHANGELOG.md` (Unreleased → Added, newest-first).
- This file.
- `docs/architecture/module-api.md` / `docs/architecture/module-map.md` — `toolexecution.application` public surface (`ToolRegistry`, `DuplicateToolRegistrationException`, `BuiltInTools`).

---

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
