# Changelog



# Archived — Added (≤ 0.9.3)



## S03-12 — A read endpoint for a run's status (`GET /api/v1/runs/{runId}`).



- **A read endpoint for a run's status (`GET /api/v1/runs/{runId}`).** The controller's `getRun` delegates to {@link RunService#getRun}, which returns the tenant-scoped status snapshot ({@code id}, the pinned version, the current {@code status}, the execution {@code attempt} and the {@code created_at} time) as a {@link RunDto}. The read goes through the tenant-scoped {@code WorkflowRunRepository#findRun} (invariant 1): a run id of another tenant is indistinguishable from a missing run and is a {@code 404}, never a leak, and the call is unavailable ({@code 503}) when no database is configured. The controller reads the tenant from the same {@code agentflow.local.tenant-id} configuration as the create endpoint (the security-context threading is a follow-up ADR, consistent with the create endpoint); the OpenAPI contract and this changelog are updated together with the endpoint (S03-12/S03-16).



## ADR-012 — Local-skeleton tenant default recorded as a documented tenant-isolation bypass (`ADR-012`).



- **Local-skeleton tenant default recorded as a documented tenant-isolation bypass (`ADR-012`).**
  The `tenantId` the `workflowdefinition` (`WorkflowDefinitionController`) and `workflowexecution`
  (`WorkflowRunController`) REST controllers read from
  `@Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}")` is a **temporary,
  documented tenant-isolation bypass**: the tenant comes from configuration, not from the verified
  security context (invariant 1 / `ADR-007`), so it does not yet satisfy the invariant — but it is
  *not* a body/query spoof (a caller cannot supply another tenant's `tenantId`; the read is still
  `(tenant_id, id)`-scoped in SQL), and it is the zero UUID by default so a misconfigured local
  stack fails closed on an unknown tenant. It was introduced in `S03-13`/`S03-14` because the
  `workflowdefinition` → `identity` dependency edge did not yet exist (a new module edge, an ADR);
  the permissive `test`-profile auth bypass removed in `S04-10` is a **separate** concern. The ADR
  makes the bypass visible, scoped it to local/dev, and **mandates removal** as soon as that edge
  lands: both controllers resolve the tenant from the verified `TenantContext` (`identity`) and the
  `@Value` defaults are deleted — that edge is its own ADR, which supersedes `ADR-012`. **Docs:** the
  new [`docs/adr/ADR-012-local-tenant-default-bypass.md`](docs/adr/ADR-012-local-tenant-default-bypass.md);
  the ADR register ([`docs/adr/README.md`](docs/adr/README.md), `ADR-012` row added). **Verification:
  documentation-only — there is no `./gradlew check` gate for a docs change.** Link integrity
  verified: `git diff --check` passes and every relative in-document link resolves to a present,
  on-topic destination (the cited `ADR-007`, `S03-13`/`S03-14`/`S04-05`/`S04-10`, the `TenantContext`,
  the controller Javadoc text, and the successor edge-ADR follow-up). The bypass is confirmed live in
  source (`WorkflowDefinitionController`, `WorkflowRunController`) and no `workflowdefinition` →
  `identity` import edge exists yet. **No** Java, contract, schema, migration, dependency, or
  module-boundary change — the ADR records the existing code and tracks the planned fix; it changes
  nothing.



## S05-17 — Workflow DSL v1 documentation (`S05-17`).



- **Workflow DSL v1 documentation (`S05-17`).** `docs/workflows/dsl-v1.md` is the human-readable
  companion to the machine-readable contract [`contracts/workflow-graph/workflow-graph.json`](contracts/workflow-graph/workflow-graph.json)
  (JSON Schema, draft 2020-12, `$id` `.../workflow-graph/1.0.0.json`): the top-level shape
  (`schemaVersion` const `"1.0.0"`, `nodes`, `edges`), the closed node-type set
  (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`, `FINALIZE`) owned
  by `WorkflowNodeTypeRegistry` (S05-07, invariant 7 — the type is compared, never executed), the
  node/edge/input/output fields, the validation rules with the **exact** `nodes[i]` / `edges[j] field
  'to'` / `$` / `schemaVersion` JSON paths and rejection messages captured from the real validator
  (`WorkflowGraphValidator#collectIssues`), and both canonical valid examples plus the invalid
  counter-examples (unknown type, cycle, dangling/self-reference/duplicate edge, duplicate node id,
  wrong `schemaVersion`). **Acceptance criterion met — every example passes the current validator:**
  the DSL validator is a pure domain gate in `workflowdefinition`, so §8 verifies a graph through the
  project's own suites, not a hand-rolled driver. **Verification:** the four affected suites pass —
  `WorkflowGraphParserTest` (21), `WorkflowGraphValidatorTest` (20), `WorkflowGraphTopologyTest`
  (1), `GraphValidationServiceTest` (4) — run via
  `./gradlew :apps:agentflow-app:test --tests <suite>` (46 tests, 0 failures, 0 skipped). The `⚠
  planned (S05)` marker in `docs/README.md` (both the tree and the "Document type → location" table)
  is removed, and this file is the new `docs/workflows/` entry point. **No** dependency, code,
  contract or schema change.



## S05-16 — Run transition concurrency integration suite (`S05-16`).



- **Run transition concurrency integration suite (`S05-16`).** The `workflowexecution` module's single transition service — {@link com.example.agentflow.workflowexecution.application.RunStateMachineService} (S05-11) — is now exercised against a real PostgreSQL under contention, the S05-16 criterion: *two transactions move the same object at the same time, and the race must finish by the documented rule so a silent lost update is impossible and the append-only history gets exactly one row*. {@code WorkflowTransitionConcurrencyIT} ({@code workflowexecution.persistence}, Testcontainers on `pgvector/pgvector:0.8.6-pg18`, the same image as Compose — never {@code H2} per {@code AGENTS.md}) releases {@code parallelism} transactions that genuinely overlap — {@code race(2)} with two transactions and {@code race(4)} with four — each {@code transitionRun} call running in its own {@link org.springframework.transaction.support.TransactionTemplate}-backed transaction over its own connection (a {@code DriverManagerDataSource} makes a fresh physical connection per {@code getConnection}, so the writers cannot share one and genuinely contend on the row). The suite asserts the outcome invariant that no scheduling can violate: at most one transition ever commits (the conditional {@code UPDATE ... WHERE status = 'QUEUED' AND version = 0} matches the row at most once), the winning state ({@code RUNNING}) at the bumped {@code version} is the row's final state (no silent lost update), and exactly one {@code workflow_events} row is appended (the append-only history, {@code S05-09}; invariants 3 and 4) — so the assertions stay deterministic under any thread interleaving instead of pinning a specific rejection code. The optimistic-lock {@code version} gate enforces the rule: {@code runTransition} reads the current {@code version}, the conditional update re-checks {@code version = :expectedVersion}, a zero-rows outcome is mapped to a {@link com.example.agentflow.common.error.ConflictException} surfaced as HTTP 409, and a transition against the already-committed {@code RUNNING} state is refused as a forbidden 400 — so neither the optimistic-lock conflict nor the domain-state gate lets two writers both succeed, and one of two competing updates never silently overwrites the other. Verification: {@code ./gradlew :apps:agentflow-app:check} is green (unit + architecture + formatting), and the S05-16 suite passes on a real PostgreSQL — exactly one winner and exactly one history row per run for both {@code race(2)} and {@code race(4)}. No dependency, contract or schema change.



## S05-15 — Transition-table unit tests for the run/step state machine (`S05-15`).



- **Transition-table unit tests for the run/step state machine (`S05-15`).** The `workflowexecution` module gains {@code TransitionTableServiceTest} ({@code workflowexecution.application}), the table-driven half of the state-machine suite that closes the S05-15 criterion — *every transition the transition tables document has a test, through the single boundary that mutates state*. The pure enums already matched the tables for every ordered pair ({@code WorkflowStateMachineTest}, S05-10) and {@code RunStateMachineServiceTest} (S05-11) exercised representative transitions; this suite drives <em>every</em> documented edge through {@code RunStateMachineService#transitionRun} / {@code transitionStep}, keyed by the trigger event named in {@code run-state-machine.md} (S05-01, 13 guards) and {@code step-state-machine.md} (S05-02, 15 edges): an allowed edge returns {@code to} and appends exactly one {@code workflow_events} row with the {@code prior=&gt;new} payload (run: {@code "prior=X new=Y"}; step: {@code "step=node prior=X new=Y"}), and a forbidden edge (a terminal state refusing any move, or a no-edge pair) is a permanent {@code 400} with nothing appended (invariant 4). The named behaviours from the task are asserted as multi-step sequences through the service — {@code SUCCEEDED/FAILED/CANCELLED} absorbing, the {@code RUNNING -&gt; RETRY_WAIT -&gt; RUNNING} retry round-trip, the two-phase {@code QUEUED/RUNNING -&gt; CANCEL_REQUESTED -&gt; CANCELLED} run cancel and the {@code WAITING_FOR_APPROVAL} approval pause/resume, plus the step equivalents. DB-free: the two repositories and {@link WorkflowEventService} are mocked (the same convention as {@code RunStateMachineServiceTest}); a stubbed {@code JdbcTemplate} never runs SQL, so the real-PostgreSQL integration suite is out of scope here, exactly as in S05-10. **No** dependency, contract or schema change. **Verification:** {@code ./gradlew :apps:agentflow-app:check} is green — the new {@code TransitionTableServiceTest} (50 tests, all pass) plus the existing suite, the architecture gates ({@code ArchitectureBoundaryTest} and {@code NoCyclicModuleDependenciesTest}, both confirmed running with {@code tests=1 skipped=0} in the JUnit XML, so they are not inert), {@code spotless} formatting (Google Java Format / LF / 2-space), and the OpenAPI contract gate (no API change). {@code ./gradlew :apps:agentflow-app:integrationTest} is out of scope for this pure unit-test task (no code/contract/schema change).



## S05-14 — Activate a workflow version — immutable after activation (`S05-14`).



- **Activate a workflow version — immutable after activation (`S05-14`).** The `workflowdefinition` module gains the single, atomic boundary through which a DRAFT version becomes immutable — the reference spec's {@code POST /api/v1/workflows/{workflowId}/versions/{version}/activate} (the create/activate pair the glossary §4 names). {@link WorkflowVersionService#activateVersion(UUID, UUID, UUID, String)}: (1) reads the version through the real tenant-scoped lookup ({@code get}), so a foreign tenant's version or a version under the wrong parent workflow is a {@code 404 not_found}, indistinguishable from a missing one (invariant 1); (2) applies the conditional {@code DRAFT → ACTIVATED} transition guarded by the pinned {@code checksum} — the version leaves {@code DRAFT} only through this transition (invariant 4); and (3) records exactly one {@code ACTIVATE} audit row through {@link AuditService} in the same call. The transition is <b>idempotent</b>: a second activation of an already-activated version returns that same immutable version without an error and without a second audit row — a client that retries after a lost response gets a clean success, never a {@code 409} and never a duplicate entry (the S05-14 idempotency criterion). A tampered checksum (or a competing writer that flips the row between the read and the conditional update — the zero-row outcome) is a permanent conflict: {@code activateVersion} turns it into a {@code ResponseStatusException(CONFLICT)} inline, exactly as {@code createVersion} maps its {@code InvalidGraphException} to a {@code 400}, and the common {@code GlobalExceptionHandler} renders it as HTTP 409 {@code conflict}. **Bug fixed in this task:** {@link WorkflowVersionService#activateVersion} used to throw the domain {@code ImmutableVersionException} that {@code S05-12} documents as "surfaced 409", but <b>no layer mapped it</b> — the owning application layer is responsible for turning a domain exception into the right HTTP status (root {@code AGENTS.md}), so the missing mapping was a latent {@code S05-12} defect that would have surfaced as HTTP 500. The mapping is now done at that boundary and the now-unused domain exception is removed (it was only referenced here). **Tests:** {@code WorkflowVersionServiceTest#activate_a_version_with_a_tampered_checksum_is_mapped_to_a_409_conflict} (DB-free — asserts the mapped {@code 409}); three new {@code WorkflowDefinitionControllerTest} cases (the endpoint maps the immutable version to {@code 200}, a conflict to {@code 409}, and a cross-tenant/missing version to {@code 404}, through the real {@code GlobalExceptionHandler}); and the new {@code WorkflowVersionServiceIT} PostgreSQL suite (real {@code pgvector/pgvector:0.8.6-pg18}, Testcontainers — the same image as Compose, never {@code H2} per {@code AGENTS.md}) proving the real database and real transaction: activation commits the transition <em>and</em> appends exactly one audit row (atomic, invariant 3), a repeat is idempotent without a second audit row, and a cross-tenant or wrong-parent lookup is refused by the real {@code WHERE tenant_id = ?} as {@code 404}. **Docs:** the activate endpoint is documented in {@code docs/domain-glossary.md} §4 (the create/validate/activate/read API surface). Verified: {@code ./gradlew :apps:agentflow-app:check} green (unit + architecture + formatting + OpenAPI contract gate), {@code ./gradlew :apps:agentflow-app:integrationTest} green (real PostgreSQL, 4 suites).



## S05-13 — Read-only workflow-graph validation endpoint (`S05-13`).



- **Read-only workflow-graph validation endpoint (`S05-13`).** The `workflowdefinition` module exposes the preview the reference spec requires: {@code POST /api/v1/workflows/{workflowId}/validate} returns a {@link GraphValidationResult} ({@code workflowdefinition.application}, named in {@code docs/architecture/module-api.md}) — {@code record GraphValidationResult(boolean valid, List<Violation> violations)} where each {@code GraphValidationViolation} is {@code (path, message)} (ADR-004-style structured errors with a JSON path). The endpoint is a read-only pre-check with **no side effects**: it never creates a version or an audit row, it is the preview that precedes {@code POST /api/v1/workflows/{workflowId}/versions}, so an invalid graph fails fast with the exact problem before a version is pinned. A single backend gate serves both entry points (invariant 7 — the {@code graph_json} body is untrusted *data*, never executed): {@link WorkflowGraphParser#collectIssues(String)} gathers every problem as {@code GraphValidationIssue} (so {@code valid=false} lists all of them), while {@code WorkflowGraphParser#parse()} throws the first one as {@code InvalidGraphException} → HTTP 400, and {@link WorkflowGraphValidator#collectIssues(WorkflowGraph)} collects domain violations (unknown node type, dangling edge, cycle) with JSON paths ({@code "nodes[i]"} for a node, {@code "edges[i] field 'to'"} for a bad edge, {@code "$"} for a whole-graph cycle). A missing/foreign definition is a {@code 404}, indistinguishable from a missing id (invariant 1); a non-{@code UUID} path or no database is {@code 400}/{@code 503} (S01-02). **Tests:** {@code GraphValidationServiceTest} (DB-free — {@code WorkflowDefinitionRepository} mocked, the real {@code WorkflowGraphParser}/{@code WorkflowGraphValidator} run end to end): {@code valid} graph → 200 with an empty violation list, an unknown node type → 400 with the {@code "nodes[i]"} path, a dangling edge → 400 with {@code "edges[0] field 'to'"}, an unknown/cross-tenant id → 404, and no database → 503; {@code WorkflowGraphValidatorTest}/{@code WorkflowGraphParserTest} assert the path literals and that a valid graph yields zero issues. **Contract:** {@code contracts/openapi/openapi.json} regenerated (new path + {@code GraphValidationResult}/{@code Violation} schemas, 62 insertions, 0 deletions — deterministic diff) via {@code ./gradlew generateOpenApiContract}; the compatibility gate is green. **Security:** {@code /api/v1/v3/api-docs} is reachable without a token on the secured chain (mirroring the {@code /actuator/{health,info}} probes) so the contract generator and the Swagger UI — both documented as default-profile local tools — work, see `### Changed`. Verification: {@code ./gradlew :apps:agentflow-app:check} is green (unit suite 244 tests, architecture gates, {@code spotless}, contract gate).

- **Integration suite for the workflow-graph validation path.** {@code GraphValidationServiceIT} runs {@link com.example.agentflow.workflowdefinition.application.GraphValidationService#validateGraph(UUID, UUID, String)} over a real {@code pgvector/pgvector:0.8.6-pg18} (Testcontainers, the same image as Compose — never {@code H2} per {@code AGENTS.md}) instead of a mocked repository. This is the layer the DB-free {@code GraphValidationServiceTest} cannot cover: a stubbed {@code JdbcTemplate} / repository never <em>runs</em> the SQL, so it can only prove the code <em>emits</em> a tenant-scoped predicate. This suite proves the database <em>enforces</em> it — a {@code validate} of a valid graph commits nothing (the endpoint is read-only, S05-13, so the definition count is unchanged after the call), an unknown node type returns the structured {@code "nodes[0]"} violation produced by the real parser over real rows, and a cross-tenant id is refused by the real {@code WHERE id = ? AND tenant_id = ?} as a 404 (invariant 1). Verification: {@code ./gradlew :apps:agentflow-app:integrationTest} is green (29 tests, 0 failures across all suites).

### Changed

- **Workflow-definition reads now project the optimistic-lock {@code version} column.** {@code WorkflowDefinition.findByTenantAndId} and {@code listByTenant} selected every {@code workflow_definitions} column except {@code version}, even though the mapper reads {@code rs.getInt("version")} and the column had already been added by {@code V3_3_4__add_workflow_definitions_version.sql} — so any read of a definition against a real database failed with {@code 'version not found in this ResultSet'}, a bug the DB-free suite never surfaced (a stubbed template returns a canned row without running the {@code RowMapper}). The two SELECTs now include {@code version}, matching {@code updateMetadata}'s optimistic-lock {@code version = version + 1} path, the {@code WorkflowDefinition.version()} carried in {@code WorkflowDefinitionDto} and the domain model. The fix was written while adding the {@code GraphValidationServiceIT} suite above: it is the first suite to exercise the read path against a real Postgres. Verification: {@code ./gradlew clean check} and {@code :apps:agentflow-app:integrationTest} are green.
