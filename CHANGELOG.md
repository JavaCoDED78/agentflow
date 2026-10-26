# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- **Workflow graph JSON Schema (`S05-03`).**
  [`contracts/workflow-graph/workflow-graph.json`](../contracts/workflow-graph/workflow-graph.json)
  is the new machine-readable contract for `graph_json` (`ADR-005`, domain glossary "Workflow DSL /
  graph"): a v1 graph is `{schemaVersion, nodes, edges}` with `schemaVersion` pinned to `1.0.0`, an
  ordered list of `1..200` typed nodes (`{id, type, config?, input?, output?}`) and `0..1000`
  execution-order `{from, to}` edges. The `type` is the closed glossary set
  (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`, `FINALIZE`), the
  node `id` must match `^[a-zA-Z][a-zA-Z0-9_-]*$` (≤128) and `config` is free-form untrusted data.
  The schema enforces everything portable JSON Schema can; the topology rules that cannot be
  expressed portably (unique ids, no dangling/self-referencing/duplicate edges, acyclicity,
  input-reference linkage) are left to the `workflowdefinition` DSL validator (`S05-05`, `S05-06`,
  `S05-07`) and documented as such. [`contracts/workflow-graph/README.md`](../contracts/workflow-graph/README.md)
  explains the schema-vs-validator boundary, the chosen limits and the exact `jsonschema` check.
  Fixtures accompany it: [`valid/`](../contracts/workflow-graph/valid) (a six-node linear pipeline
  covering every node type and a branching DAG that exercises `input`/`output` references) and
  [`invalid/`](../contracts/workflow-graph/invalid) (wrong/missing `schemaVersion`, unknown node
  type, malformed edge, bad id pattern, malformed/object input reference and a 201-node
  over-limit graph); each structurally-valid-but-topology-invalid case lives in
  [`topology/`](../contracts/workflow-graph/topology) for the DSL validator. Verified by the
  external `jsonschema` validator (Draft 2020-12): every `valid/` fixture passes and every
  `invalid/` fixture fails — the `S05-03` acceptance criterion ("a graph can be checked with an
  external schema validator"). Documentation-only change, no build gate.


- **Step state machine (`S05-02`).**
  `docs/architecture/step-state-machine.md` is the new reference for the step-level
  state machine of the `workflowexecution` module.  It defines nine step states: the eight in the `S05-02` task enumeration
  (`PENDING`, `READY`, `RUNNING`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`, `SKIPPED`, `CANCELLED`
  — anchored verbatim to `docs/domain-glossary.md` §5) plus a step-level
  `WAITING_FOR_APPROVAL`; marks the four terminal states (`SUCCEEDED`, `FAILED`, `SKIPPED`,
  `CANCELLED`), and gives a Mermaid `stateDiagram-v2` plus a transition table whose every
  row names the triggering **event/command** and the **guard condition**
  (`step.schedulable` once upstream dependencies `SUCCEEDED`, `run.started`/`step.started`
  lease claim, `step.succeeded`, `step.failed`/`run.retry-due` for retry, the failed
  predecessor `step.skipped` cascade, the run `cancel`/`run.cancelled` steps). It then
  documents the run-state aggregation the task asks for: the run terminal states are
  **derived** from a fold over the step-state multiset — a run is `SUCCEEDED` only when
  every step is `SUCCEEDED`, `FAILED` on a terminal step failure or cascade skip, and
  `CANCELLED` once all steps drained — reconciled with the run transition table
  (`run-state-machine.md`, `S05-01`, guards 7/8/13). The acceptance criterion — the order
  of duplicate events does not change the final aggregation result — is proved by
  construction: aggregation is an idempotent, order-independent fold over the committed
  step states read from PostgreSQL (`ADR-002`), with events deduplicated by `event_id` in
  the outbox/inbox (`ADR-003`) and step transitions applied through the single transition
  service (`S05-11`), so a duplicate event cannot flip a terminal state.  The doc also reconciles its nine-state set with the `S05-02` enumeration: the glossary §5
  and plan §8.1 list a step-level `WAITING_FOR_APPROVAL`, which this spec keeps — it is the
  step-level half of the run-level approval pause (`S05-01`, where the lease is released, so
  the step must leave `RUNNING`). The approval pause is modelled once, at the step level, and
  synchronized with the run level; no ADR is required (no contract/invariant change). The
  `⚠ planned (S07-17)` `durable-execution.md` note stays
  for lease/fencing/recovery; `docs/README.md` and the `run-state-machine.md` index now
  point at the new doc. No code changed (the enum is `S05-10`); verification is by link
  integrity (`git diff --check`, every relative in-document link resolves to a present,
  on-topic destination) and a diff review — a documentation-only task, no build gate.


- **Run state machine (`S05-01`).**
  `docs/architecture/run-state-machine.md` is the new reference for the run-level
  state machine of the `workflowexecution` module. It defines the eight run states
  (`QUEUED`, `RUNNING`, `WAITING_FOR_APPROVAL`, `RETRY_WAIT`, `SUCCEEDED`, `FAILED`,
  `CANCEL_REQUESTED`, `CANCELLED` — anchored verbatim to `docs/domain-glossary.md`
  §5), marks the three terminal states (`SUCCEEDED`, `FAILED`, `CANCELLED`), and
  gives a Mermaid `stateDiagram-v2` plus a transition table whose every row names the
  triggering **event/command** and the **guard condition** (`run.started` lease claim,
  `step.approval-needed`, `run.approved`/`run.rejected` for the approval gate,
  `step.failed`/`run.retry-due` for retry, `run.finished`/`run.failed` for the
  terminal outcomes, the four `run.cancel` commands and the one-phase `run.cancelled`).
  Cancellation is modelled as a two-phase, one-way path
  (`… → CANCEL_REQUESTED → CANCELLED`); `WAITING_FOR_APPROVAL` can end in either resume
  (`run.approved`) or failure (`run.rejected`), because a rejected approval must not
  execute its side effect. The doc also records that transitions are applied through
  the single transition service (conditional update + append-only `workflow_events` in
  one transaction, `ADR-002`…`ADR-004`, `S05-11`), reconciled with step-state
  aggregation (`S05-02`), fenced by the lease, and that the Java `WorkflowRunState`
  enum implementing the table lives in `S05-10`. The `⚠ planned (S07-17)` note on
  `durable-execution.md` is trimmed to lease/fencing/recovery (the state machine now
  lives here); `docs/domain-glossary.md` §5 links to the new doc. No code changed
  (the enum is `S05-10`); verification is by link integrity (`git diff --check`, every
  relative in-document link resolves to a present, on-topic destination) and a diff
  review — a documentation-only task, no build gate.

- **Authentication/authorization guide (S04-15).**
  [`docs/security/authentication-authorization.md`](docs/security/authentication-authorization.md) is
  the new authn/authz reference. It ties the Sprint 4 pieces into one picture — the OAuth 2.0
  Resource Server (`S04-03`), the claims-to-authorities mapper and the closed permission catalogue
  (`S04-04`), the `tenant_id` claim (`S04-05`), the tenant scope (`S04-07`) and the endpoint access
  matrix (`S04-08`) — and describes the `401`/`403`/`404` masking policy (`S04-09`) and the
  actuator restrictions (`S04-11`). It covers the two local flows (machine-to-machine
  `agentflow-service` / Client Credentials and the public `agentflow-web` browser client /
  Authorization Code + PKCE), the claims a token must carry, what the Resource Server validates
  (signature, issuer, audience `agentflow-api`, expiry), the catalogue ids, tenant resolution and
  the actuator allow-list, and links to the executable local-login walkthrough
  [`docs/runbooks/local-auth.md`](docs/runbooks/local-auth.md) (`S04-14`) and the
  [`tenant-claim.md`](docs/security/tenant-claim.md) / [`permissions.md`](docs/security/permissions.md)
  / [`response-policy.md`](docs/security/response-policy.md) guides. No secrets are in the
  examples. It also notes a **follow-up**: the realm currently assigns realm roles
  (`workflow:approve`, `knowledge:read`, `tenant:admin`) that the deny-by-default mapper drops until
  the realm converges on the closed `Permission` catalogue. Config fix alongside: the local issuer
  default in `application-local.yml` was `http://localhost:8443/…` — corrected to
  `http://localhost:8083/…` to match the Keycloak compose port (`KC_HTTP_PORT`), the
  `application-local.yml` `.env` and `local-auth.md`. `docs/README.md` now points to the new doc
  (authn/authz removed from the ⚠ `planned` list; the secrets-policy doc stays ⚠ `planned` for
  `S11`). Verified by link integrity (`git diff --check`, every relative in-document link resolves to
  a present, on-topic destination) and a diff review — documentation-only change, no build gate.
  Local login/runbook verification is the `S04-14` runbook's (Keycloak `identity` profile +
  PostgreSQL), which this guide points to rather than re-running.

- **Local authentication runbook (S04-14).** [`docs/runbooks/local-auth.md`](docs/runbooks/local-auth.md)
  is the new developer guide for logging in locally against the secured `/api/v1` API with a real
  Keycloak token. It covers both local login flows — the machine-to-machine `agentflow-service`
  (Client Credentials, `curl` only) and the public `agentflow-web` browser client (Authorization
  Code + PKCE), shows the exact `curl`/token commands **without real secrets**, and explains the two
  things that most often fail locally: the token **issuer** (`AGENTFLOW_KEYCLOAK_ISSUER_URI`) and the
  token **audience** (`agentflow-api`). It documents exactly what the Resource Server validates
  (signature, issuer, audience, expiry) and maps each failure to an HTTP status
  (`401`/`403`/`404`), including the masking policy (S04-09). Verified locally: Keycloak is
  `healthy`, OIDC discovery returns `HTTP 200`, a Client Credentials request for
  `agentflow-service` issues a token, and decoding it confirms the payload carries `iss`, `sub`,
  `azp=agentflow-service`, `scope=agentflow` and **`aud=["agentflow-api"]`** — S04-03 now stamps the
  `agentflow-api` audience into the token via the realm `fixed-audience` mapper, so the Resource
  Server's `/api/v1` audience check passes. A `401` on that check happens only if the realm was not
  re-imported after the change (S04-14). The runbook links to
  `docs/security/tenant-claim.md`, `permissions.md` and `response-policy.md`; `S04-15`
  (`docs/security/authentication-authorization.md`) links back to it. Documentation-only change —
  verified by link integrity (`git diff --check`, every relative link in the repo resolves).

- **Cross-tenant isolation at the secured HTTP boundary (S04-13).** Tenant isolation (invariant 1 /
  `ADR-007`) is now verified through the real, bearer-token `SecurityFilterChain`
  (`SecurityConfiguration.securityFilterChain`) — the `workflow:read` / `workflow:write` least-
  privilege gates (`S04-08`) and the `404` masking policy (`S04-09`) together, on a live
  {@code secured-test} server rather than at the service layer alone. Two new tests in
  `SecurityFilterChainTest.crossTenantReadIsIndistinguishableFromMissingThroughTheSecuredChain`
  and
  {@code SecurityFilterChainTest.bodyTenantSpoofingIsNotScopedThroughTheSecuredChain} drive an
  {@code AuthorizationCodeExchange} flow and {@code HttpExchange} through the chain: a valid
  {@code workflow:read} caller reading another tenant's {@code {workflowId}} returns {@code 404}
  (indistinguishable from a missing resource), and a {@code workflow:write} caller whose request
  body carries a different {@code tenant_id} is scoped to the verified tenant, not the spoofed one
  (the create is recorded under {@code configuredTenant} and the {@code spoofedTenant} path is
  never taken). The stubbed {@code WorkflowDefinitionService} mock is a {@code @Primary} bean in
  {@code TestKeycloak}, and {@code Mockito.reset} in {@code @BeforeEach} clears stubs and interaction
  counts so the singleton mock is shared cleanly across the class. {@code ./gradlew check} is
  green (unit, architecture, and spotless/Google-Java-Format gates).

- **Audit on workflow definition mutations (S04-12).** Every create of a workflow definition now
  records exactly one row in the append-only, tenant-scoped `audit_events` trail the invariant
  (`docs/security/tenant-claim.md`, invariant 3) and `ADR-010` demand. The `workflowdefinition`
  module records the mutation through the single public `audit` API (`AuditService.record`) — it
  does not write the table itself, which would violate `audit`'s ownership of `audit_events` and the
  module boundary (only `AuditService`'s root public API plus the `AuditEvent`/`AuditAction`/
  `AuditResourceType` value types are imported; `audit` stays the sole owner, the new module-map
  edge `workflowdefinition → audit` mirrors `toolexecution → audit`). The insert is **transactional**
  (`WorkflowDefinitionService.createDefinition`, annotated `@Transactional`): the `AuditAction.CREATE`
  event for the `WORKFLOW_DEFINITION` resource commits in the same statement as the definition
  insert, so a definition is never created without its audit trail and a failed audit write rolls the
  definition back with it — satisfying invariant 3 directly for this mutation. The event links only
  the caller's `actor` subject, never a credential or token (`metadata_redacted` holds the redacted
  text, per the `V3_3_5` migration and the "no secrets" invariant); an async outbox variant (`
  AuditService.recordOutbox`) exists but was rejected (S04-12): the mutation is already transactional,
  so async delivery adds delivery/retry/dedup complexity for no gain. Verification:
  `./gradlew check` is green — unit plus the architecture gates (`ArchitectureBoundaryTest.ALLOWED` +
  `NoCyclicModuleDependenciesTest` and the module-map, which now include the `workflowdefinition →
  audit` edge) — and `AuditServiceTest` / `AuditRepositoryTest` exercise the audit write path. S04-13…
  S04-15 audit the other definition mutations (update/activate) through the same API.

- **Restricted actuator endpoints (S04-11).** Management diagnostics are now behind a bearer
  token. The secured `SecurityFilterChain` (`SecurityConfiguration.securityFilterChain`) keeps only
  {@code /actuator/health}, {@code /actuator/health/**} and {@code /actuator/info} reachable without
  a token — for the liveness/readiness/orchestrator probes — and applies {@code
  anyRequest().authenticated()} to every other request, so a management endpoint can never be
  reached anonymously. Combined with the {@code management.endpoints.web.exposure.include}
  allow-list that leaves only {@code health} and {@code info} registered (S01-18), the sensitive
  {@code env}/{@code configprops}/{@code beans}/{@code mappings} endpoints are never reachable
  anonymously: the secured chain rejects an unauthenticated request with 401 (S04-11), and a
  verified caller meets a 404 because the endpoint is not registered. This closes the
  S04-11 acceptance criterion (sensitive endpoint not accessible anonymously) and is verified
  through the HTTP boundary on the {@code secured-test} profile in
  `SecurityFilterChainTest.sensitiveManagementEndpointsAreNotReachableAnonymously`;
  {@code ./gradlew :apps:agentflow-app:test} is green.

- **403/404 masking policy (S04-09).** The security response policy is now written down as a
  single, authoritative statement in [`docs/security/response-policy.md`](docs/security/response-policy.md):
  a resource that exists but belongs to another tenant is **indistinguishable** from a resource
  that does not exist — the response is always `404 not_found`, never `403`, so an attacker can
  never probe a guessed UUID and learn that another tenant owns a resource with that id (invariant
  1 / `ADR-007`). The policy makes the capability/resource distinction explicit: a `403`
  (`authorization`, `tool_policy_denial`) signals a *capability* problem — the caller lacks the
  scope for the *action* and the security layer denies before any resource is looked up — while a
  `404` signals a *resource* problem — a verified caller with the capability requested a resource
  that is not `(tenant_id, resource_id)`-scoped to them. The `tenant_id` comes only from the
  verified security context, so spoofing it cannot turn a `404` into a `403`. The write path does
  not leak either: a conditional update (`updateMetadata`) takes a `current` that the tenant-scoped
  read already returned and re-checks `tenant_id` in its `WHERE`, so a zero-rows outcome surfaces
  as a `409 conflict` — but the write can only start from a `current` the read already proved, so
  a foreign resource can never reach that path. The global error handler (`S03-11`) renders every
  `404` as the permanent `not_found`, so the status, `error_class`/`code` and `retryable` flag are
  identical for foreign and missing. This formalises the masking behaviour first implemented in
  `S04-07` (the service maps an empty tenant-scoped lookup to `404 not_found`; the repository
  scopes every lookup by `tenant_id` in SQL). It complements — does not duplicate — the error
  taxonomy (`docs/architecture/error-taxonomy.md`, `S02-10`, the canonical `error_class` set) and
  the permission catalogue (`docs/security/permissions.md`, `S04-04`, which decides the `403`
  capability side). Cross-referenced from `error-taxonomy.md`, `permissions.md` and the `docs/`
  index; the `⚠ planned (S11)` badge in the security index is left for the still-planned
  authn/authz + secrets policy. Verification: `WorkflowDefinitionControllerTest`
  (`get_crossTenantId_returnsNotFound_identicalToMissing`) asserts a foreign-tenant read returns
  `404 not_found` — the HTTP-boundary half of the acceptance criterion, alongside the existing
  `get_missingDefinition_returnsNotFound` (both indistinguishable) and the service-level
  `WorkflowDefinitionServiceTest.getDefinition_crossTenantIdReturnsNotFound`;
  `./gradlew :apps:agentflow-app:test` is green.

- **Least-privilege endpoint access (S04-08).** Every authenticated `/api/v1` action is now
  gated on the single permission it needs instead of the coarse `anyRequest().authenticated()`.
  The mapping lives in one place — the endpoint access matrix (`@Component` `AccessMatrix`,
  `identity/application/authorization/AccessMatrix`, next to the `Permission` catalogue in the
  `identity` module) — where each `EndpointRule` is an HTTP `method` + Ant `pathPattern` under
  `/api/v1` and the one `Permission` the `hasAuthority` check verifies.
  `com.example.agentflow.config.SecurityConfiguration`
  (`SecurityConfiguration.securityFilterChain`) consumes the matrix, iterating its rules
  (`authorize.requestMatchers(method, pattern).hasAuthority(requiredPermission.id())`) and closing
  the rest with `anyRequest().authenticated()` — default-deny. This enforces least privilege (a
  read needs only `workflow:read`, a create only `workflow:write`), keeps user operations
  (`workflow:*`, `document:*`, `run:*`) in a group distinct from a reserved operator group
  (`ops:dlq`), and makes the matrix the single source of truth (no ad-hoc `permitAll`/
  `hasAuthority` in the chain). The `secured-test` profile keeps the secured chain active with the
  in-memory test `JwtDecoder`. Verification: `./gradlew :apps:agentflow-app:test` is green —
  `AccessMatrixTest` (the matrix contract: least-privilege mapping, catalogue ids, separated user
  operations) and the S04-08 additions to `SecurityFilterChainTest` (allowed/denied role-endpoint
  pairs through the HTTP boundary).

- **Tenant-scoped access (S04-07).** Every read and write against a tenant-scoped
  resource is scoped by the pair `(tenant_id, resource_id)` — the `tenant_id` is a
  predicate in the SQL itself (`workflowdefinition.persistence.WorkflowDefinitionRepository`),
  never a post-filter and never derived from the guessed `id` alone. A foreign tenant's
  resource is therefore indistinguishable from a missing one: `getDefinition` returns a
  permanent `404 not_found` (never a `403`), and a conditional update (`updateMetadata`)
  re-checks `tenant_id` in its `WHERE`, so a write scoped to the wrong tenant affects zero
  rows and surfaces as a `409 conflict` rather than a silent no-op. Guessing another
  tenant's UUID gives neither read nor write (invariant 1 / `ADR-007`). This sprint closes
  the write half of the acceptance criterion with the DB-free negative test
  `WorkflowDefinitionRepositoryTest.updateMetadata_foreignTenant_affects_zero_rows` (the
  cross-tenant read denial is already covered by the service and repository tests). Child
  resources inherit the same rule: their lookups are scoped by the owning tenant too — the
  parent's tenant is the trust anchor, a child id is never an authorization on its own —
  documented in [`docs/architecture/api-conventions.md`](docs/architecture/api-conventions.md).
  Verification: `./gradlew :apps:agentflow-app:test` is green.

- **TenantContext abstraction (S04-06).** The tenant is now threaded through the
  application as one immutable value — {@code TenantContext}
  ({@code identity/application/TenantContext}) — carrying only {@code tenantId}
  (a {@code UUID}) and the verified {@code actor}, so the tenant is a single,
  explicit method argument instead of something each entry point derives itself.
  {@code TenantContextResolver}
  ({@code identity/application/TenantContextResolver}, a {@code @Component}) is the
  single place the trusted tenant is pulled out of the security context: it reads
  the tenant from the <em>verified</em> OAuth 2.0 JWT the Resource Server (S04-03)
  has already validated (signature, issuer, audience, expiry) and turns the
  {@code Authentication} principal into a {@code TenantContext}, so this is the one
  place {@code tenant_id} comes from for a request. Because it is a plain
  immutable value threaded as an argument — never a {@code ThreadLocal} or any
  shared, per-request state — parallel requests from different tenants cannot mix
  context (invariant 1 / the S04-06 acceptance criterion). Async workers
  (workflow/ingestion roles) have no HTTP {@code SecurityContext}: the resolver
  offers {@code resolveFromStoredTenant(UUID, actor)} for the worker path, where
  the tenant is the {@code tenant_id} of the stored run re-read from PostgreSQL
  (the source of truth, ADR-002) and re-checked against the run actually being
  processed; the {@code Kafka} envelope's {@code tenantId} is routing/correlation
  only, never trusted. {@code WorkflowDefinitionController} (S03-13) is left
  untouched — it reads the tenant from configuration until {@code S04-10} moves it
  to the verified context — and its local default keeps working. Denials use
  {@code ResponseStatusException} with {@code ErrorClass.AUTHENTICATION} → {@code
  401}. Unit-tested in {@code TenantContextResolverTest}: the happy path from a
  verified JWT, denial without a membership claim, denial of a non-UUID
  {@code tenant_id}, resolution from the {@code SecurityContext} principal, denial
  when the principal is not a verified JWT, the worker path from the stored run,
  and the acceptance criterion — {@code parallelRequestsFromDifferentTenantsDoNotMixContext},
  which resolves many tenants concurrently through one singleton resolver and
  asserts each returns its own tenant. {@code docs/security/tenant-claim.md}
  already documents this resolver as the concrete realisation of S04-06.
  {@code ./gradlew check} is green (unit + architecture boundary gate + formatting).

- **Trusted tenant claim (S04-05).** The tenant in an authenticated request is carried by the
  `tenant_id` Keycloak claim — a mapped claim present only when the user has a tenant membership —
  and is read **only** from the OAuth 2.0 JWT the Resource Server (S04-03) has already verified
  (signature, issuer, audience, expiry). `TenantClaimExtractor`
  (`identity/application/TenantClaimExtractor`) returns `Optional<UUID>`: a verified token without a
  well-formed `tenant_id` claim (no membership, or a non-UUID id) resolves to **no tenant** and is
  denied, never defaulted to a fallback. `tenant_id` is **never** taken from the request body,
  query or Kafka header (invariant 1 / `ADR-007`); the temporary `${agentflow.local.tenant-id}`
  local-skeleton default (S03-13/S03-14) remains a tenant-isolation bypass: threading the tenant
  from the verified context needs a new `workflowdefinition` → `identity` edge (an ADR), so it is a
  documented follow-up rather than an S04-10 change. The extraction primitive
  seeds the `TenantContext` (S04-06) that threads the tenant through the application and re-checks
  it against PostgreSQL for async workers. Acceptance ("substituting another tenant's id does not
  change the access scope") is enforced by construction; the `S04-13` HTTP-boundary suite is the
  HTTP-level proof, and the primitive is unit-tested in `TenantClaimExtractorTest`. Documentation: the tenant
  claim contract at [`docs/security/tenant-claim.md`](docs/security/tenant-claim.md); the
  `Tenant claim` term added to [`docs/domain-glossary.md`](docs/domain-glossary.md).
  `./gradlew :apps:agentflow-app:test` is green (see below).

- **Permission catalogue (S04-04).** The AgentFlow permission catalogue is now a
  single, closed enum — `com.example.agentflow.identity.domain.Permission` — the one source
  of truth for the `authorization` capability string a verified caller must hold. Each
  constant is one permission; its `id()` is the shared token so three consumers agree
  on what a permission *is*: the claims-to-authorities mapper grants an authority only
  when its value is a catalogue id (deny by default), the endpoint access matrix (S04-08)
  maps every `/api/v1` action to the id(s) it requires, and the Keycloak realm (S04-01)
  assigns these ids as client roles. S04-04 canonical set: `workflow:read`,
  `workflow:write`, `workflow:activate`, `document:read`, `document:write`,
  `run:execute`, `run:approve`, `ops:dlq`. The catalogue is intentionally **closed**:
  a role claim that is not one of these ids is ignored by the mapper and denied by the
  matrix, so an issued role outside the catalogue cannot silently grant a capability;
  extend it here only (`Permission#all()`), never with an ad-hoc string. The lookup
  API (`ids()`, `fromId()`, `exists()`, `all()`) backs the mapper and the matrix. The
  central claims-to-authorities mapping is `ClaimsToAuthoritiesMapper` (the `identity`
  module's owner of "claims → permissions mapping", per AGENTS.md and the plan): it is
  the **single** place that turns the verified JWT's role claims into Spring Security
  `GrantedAuthority` values, wired as a `@Component` bean into the `JwtAuthenticationConverter`
  that the secured `SecurityFilterChain` (S04-03) uses — so every entry point (HTTP
  controllers, Kafka listeners, scheduled jobs) derives the principal's authorities from
  the same catalogue and controllers never parse the JWT themselves (S04-04 acceptance).
  The mapping is deny-by-default (only a catalogue id is granted), idempotent (the same
  id always maps to the same authority string, duplicates collapse) and reads the token
  only after the Resource Server has verified it; it never derives the **tenant** (that
  comes from the trusted security context only, S04-05/06). It handles both Keycloak
  role-claim shapes — client-scoped roles under `resource_access → clientId → {roles:
  [...]}` and a flat top-level realm `roles` claim — decoding the arrays via `Iterable`
  (gson `JsonArray`, not `List`). Verification: `PermissionTest` (unit) asserts the
  catalogue ids, `fromId`/`exists`, idempotency and that the ids are stable/unique;
  `ClaimsToAuthoritiesMapperTest` (unit) asserts that verified role claims map to the
  matching authorities, unknown claims are dropped, the Keycloak `resource_access` and
  realm `roles` shapes both resolve, and duplicate claims collapse. `./gradlew
  :apps:agentflow-app:test --tests "com.example.agentflow.identity.*"` is green
  (5/5 for the mapper); the `SecurityFilterChainTest` context-load suite passes (the
  mapper is now a real bean the secured chain wires in). Documentation: the new focused
  catalogue at [`docs/security/permissions.md`](docs/security/permissions.md);
  `docs/architecture/module-api.md` and `docs/domain-glossary.md` updated to the
  implemented `Permission` catalogue (they previously named a different `PermissionScope`
  id set — aligned here with the plan's S04-04 list). `./gradlew :apps:agentflow-app:check`
  is green (unit + architecture gates + formatting + OpenAPI contract gate).

- **Spring Security Resource Server (S04-03).** The `/api/v1` surface is now
  secured by an OAuth 2.0 Resource Server that validates the JWT access token
  before any controller runs: signature (via the standard `NimbusJwtDecoder`,
  built from the issuer's OIDC `jwk_set_uri`, resolved lazily on first decode so
  the context still boots without a reachable Keycloak), issuer, audience and
  expiry. A missing, expired, tampered or mis-issued token, or a wrong
  issuer/audience, is rejected with `401 Unauthorized` at the security layer. Two
  `SecurityFilterChain`s are split by profile: the **secured** chain on every
  non-test profile (default, local, kubernetes) plus the `secured-test` profile,
  requiring a bearer JWT for everything except `/actuator/health` and
  `/actuator/info` (so liveness/readiness probes keep working); a **permissive**
  chain on the `test` profile, so the many existing tests that exercise
  controllers without authenticating keep working — it never runs on the
  kubernetes/production profile (S04-10). The `bearerAuth` (JWT) security scheme
  is documented in the OpenAPI contract (`OpenApiConfig` → `contracts/openapi/`),
  which stays compatible (`OpenApiContractCheckTest`). The trusted issuer/audience
  come from typed, validated `@ConfigurationProperties` (`AgentFlowProperties.security`)
  bound from `agentflow.security.*`: `issuer-uri` has no default and is mandatory
  in the kubernetes profile (a missing `AGENTFLOW_KEYCLOAK_ISSUER_URI` fails boot
  loudly instead of validating against the base placeholder), supplied from the
  environment (`AGENTFLOW_KEYCLOAK_ISSUER_URI` in the local `.env`) for local and
  kubernetes. Verification: the acceptance criterion is proved through the HTTP
  boundary by `SecurityFilterChainTest` (`missingTokenIsRejected`,
  `expiredTokenIsRejected`, `tokenSignedByAnotherKeyIsRejected`,
  `wrongIssuerIsRejected`, `wrongAudienceIsRejected` all assert `401`;
  `validTokenPassesTheSecurityLayer` proves a valid token reaches the dispatcher
  as a `404` for the missing route); `AgentFlowPropertiesStartupTest` asserts the
  mandatory security values. `./gradlew :apps:agentflow-app:check` is green (unit
  + architecture gates + formatting + OpenAPI contract gate).

- **Split user and machine-to-machine clients (S04-02).** The single
  `agentflow-api` client (S04-01), which mixed user login, service accounts and the
  password grant, is split into three purpose-built clients in
  [`infra/compose/keycloak/realm-agentflow.json`](infra/compose/keycloak/realm-agentflow.json):
  **`agentflow-web`** — a *public* SPA client using only the Authorization Code flow
  + PKCE (`publicClient`, `standardFlowEnabled`, no secret); **`agentflow-service`** —
  a *confidential* machine-to-machine client using only Client Credentials
  (`serviceAccountsEnabled`, no standard flow) whose secret is the
  `${AGENTFLOW_SERVICE_CLIENT_SECRET}` placeholder, resolved from the container
  environment at `--import-realm` time so it never lives in Git; and
  **`agentflow-api`** — the resource server / OIDC audience (`aud=agentflow-api`, the
  `cloak.audience` value in the root `.env.example`), which issues no tokens and so
  needs no login flow, service accounts or secret. `directAccessGrantsEnabled`
  (password grant) is disabled on all three AgentFlow clients. `agentflow-web`'s
  `redirectUris`/`webOrigins` are limited to `http://localhost:3000` (update for the
  production SPA); `agentflow-service`'s audience/claims wiring is a later task
  (S04-03). The secret is injected by Compose
  (`infra/compose/docker-compose.yml`) from a local `.env`, with a clearly local-only
  default. Verified: `docker compose --profile identity config` passes; Keycloak
  (`quay.io/keycloak/keycloak:26.8.1`) imported the realm and reached `healthy`; the
  Admin API shows the three clients with the correct grant flags; a Client Credentials
  request for `agentflow-service` issues a token, a wrong secret returns `401`, and
  no client carries a committed secret (the old hardcoded secret was removed). Realm import is declarative from Git (Keycloak assigns client ids at
  import time). New reference [`infra/compose/keycloak/README.md`](infra/compose/keycloak/README.md)
  documents the model; `infra/compose/README.md` links to it.

- **Keycloak realm: client scope and OIDC mappers (S04-01).** The declarative
  realm in [`infra/compose/keycloak/realm-agentflow.json`](infra/compose/keycloak/realm-agentflow.json)
  is now a complete, import-ready model instead of just a realm with clients
  and roles. It defines a single default client scope, `agentflow`, attached to
  the `agentflow-api` client via
  `defaultClientScopes: ["agentflow"]`, carrying three `oidc-usermodel-protocol-mapper`
  protocol mappers — `preferred-username` (claim `preferred_username`), `email`
  (claim `email`) and `full-name` (claim `name`). Realm roles remain the Sprint
  1 set (`workflow:read`, `workflow:write`, `workflow:approve`, `knowledge:read`,
  `tenant:admin`); the permission catalogue for Sprint 4 is a separate task
  (`S04-04`). Idempotency was verified empirically: two clean Keycloak 26.8.1
  runs with `--import-realm` (fresh volumes) produced an identical declarative
  model — same client scope, same three mappers, same `agentflow-api` client and
  same five roles — confirmed by diffing the admin API response for both runs
  (`diff` empty). The realm already imports cleanly on every start; this change
  only adds the scope/mapper model on top of it.

- **API and persistence conventions — developer guide (`S03-17`).**
  `docs/architecture/api-conventions.md` is the new developer reference for the
  `/api/v1` REST API and the PostgreSQL it reads from. It documents the
  workflow-definition endpoints (`POST` and `GET /api/v1/workflows`,
  `GET /api/v1/workflows/{workflowId}`), pagination (`page`/`size` query params,
  a clamped page size, the total-order `created_at DESC, id DESC` stable ordering
  and the `WorkflowDefinitionPageDto` page shape), RFC 9457 Problem Details
  through the global error handler (`S03-11`), the optimistic-lock `version` →
  `409 Conflict` round-trip (`S03-10`) and the tenant-scoped `(tenant_id, id)`
  lookup that reports a foreign-tenant id as a permanent `404 not_found`
  (invariant 1), and the procedure for changing the OpenAPI contract (regenerate
  via `./gradlew generateOpenApiContract`, the `OpenApiContractCheckTest` gate
  that fails on breaking changes, and the baseline-approval flow). Anonymized
  create/list/get/error request-response examples match
  [`contracts/openapi/openapi.json`](contracts/openapi/openapi.json). The
  migration process is already covered by
  [`docs/architecture/database-migrations.md`](docs/architecture/database-migrations.md)
  (`S03-02`), so this guide links it instead of duplicating it. The root
  `README.md` gains `API`, `Database`, `Migrations` and `Tests` sections pointing
  at the reference. Both index trees (`docs/README.md`,
  `docs/architecture/README.md`) list the new file and stay clear of a stale
  `⚠ planned` marker. Documentation-only change — verified by integrity
  (`git diff --check`, every relative in-document link resolved against the
  repo) — no build gate for a documentation-only task.

- **OpenAPI contract gate (S03-16).** A committed baseline
  [`contracts/openapi/openapi.json`](contracts/openapi/openapi.json) is now compared against the
  live spec on every `./gradlew check`, so a contract drift fails the build instead of slipping
  through. The classifier `OpenApiCompatibility`
  (`apps/agentflow-app/src/test/java/com/example/agentflow/openapi/OpenApiCompatibility.java`)
  classifies every change as *breaking* or *compatible*: breaking changes are a removed path or
  HTTP method, a removed required property, a property `type` change, or a removed `enum` value;
  everything else (added path/method, added property, added response) is reported but does not fail.
  `OpenApiContractCheckTest`
  (`apps/agentflow-app/src/test/java/com/example/agentflow/openapi/OpenApiContractCheckTest.java`)
  boots the app, fetches the freshly generated `/api/v1/v3/api-docs`, and fails when
  `report.breakingChanges()` is non-empty — this is the acceptance criterion ("unintentional
  removal of a required field breaks CI"): the unit test `removingARequiredPropertyIsBreaking`
  proves a removed required property is flagged, and a live end-to-end removal of the `name`
  request field is caught by the gate. Regeneration stays a deliberate, deterministic action:
  `./gradlew generateOpenApiContract` runs
  `OpenApiContractTool` (`apps/agentflow-app/src/main/java/com/example/agentflow/tooling/OpenApiContractTool.java`)
  on the default profile (no database required — the DataSource is excluded in `application.yml`),
  waits for the live `/api/v1/v3/api-docs`, fetches the description and writes a *normalized* copy
  (the volatile ephemeral-port `servers` entry is dropped, so the committed baseline is byte-for-byte
  reproducible and a diff ever shows only a real change). `build.gradle.kts` passes
  `-Dopenapi.contract.baseline=<contracts/openapi/openapi.json>` to the `test` task, so `check` runs
  the gate; `contracts/openapi/README.md` documents generation and the baseline-approval flow.
  Verified: `./gradlew check` is green (formatting, unit, integration, architecture, gate); all
  7 `OpenApiCompatibility` unit tests pass.

- **PostgreSQL migration integration suite (S03-15).** Two Testcontainers-backed suites in
  `apps/agentflow-app/src/integrationTest/java/com/example/agentflow/` exercise the real
  `pgvector/pgvector` migrations (S03-03…S03-10) on a real PostgreSQL instead of H2, satisfying the
  S03-15 acceptance ("PostgreSQL, not H2 as a substitute for PostgreSQL-specific behaviour"):
  `MigrationsTest` (8 tests) starts an empty `pgvector/pgvector:0.8.6-pg18` container (the same
  image the Compose `core` profile pins), runs the versioned Flyway migrations, and asserts the
  resulting schema — tables exist, `timestamptz`/UUID/`NUMERIC` types, `unique (tenant_id, name)`
  and `(workflow_id, version)` constraints, `NOT NULL` on `tenant_id`/`graph_json`, and the
  `workflow_definitions.tenant_id` → `tenants` and
  `workflow_definition_versions.workflow_id` → `workflow_definitions` foreign keys. It then runs a
  data-driven acceptance case that inserts valid rows and confirms every negative constraint
  (unique / NOT NULL / foreign key) rejects the intended violation; each mid-run insert is rolled
  back. `MigrationsStartupTest` (1 test) boots the Spring context on the already-migrated schema to
  prove the migrations leave the database in a runnable state.
  Added `org.testcontainers:testcontainers-postgresql` and
  `org.testcontainers:testcontainers-junit-jupiter` (2.0.5, BOM-managed by the Spring Boot BOM's
  Testcontainers import; the 2.0.5 module coordinate is `testcontainers-postgresql`, the 1.x
  `postgresql` name no longer resolves) as `testImplementation`; the `gradle.lockfile` is
  regenerated to lock them and their transitive Testcontainers deps.
  ⚠ Tiering (S02-13): these are the first Testcontainers suites and are the `integrationTest` full
  suite, separate from the fast `check` gate. `build.gradle.kts` now declares the `integrationTest`
  source set (shares the `test` classpath) and the `integrationTest` task run with
  `./gradlew integrationTest`; `check` stays the fast unit + ArchUnit gate. The `gradle.lockfile`
  is regenerated to lock the Testcontainers graph that this source set pulls in.

- **List / get workflow definition endpoints (S03-14).** Two read endpoints round out the
  {@code workflowdefinition} API: {@code GET /api/v1/workflows} returns a tenant's definitions as
  one paginated page ({@code page}, {@code size} query params; the response carries {@code page},
  {@code size}, {@code totalElements}, {@code totalPages}, {@code hasNext}) and {@code GET
  /api/v1/workflows/{workflowId}} returns a single definition (a cross-tenant id is a {@code 404}
  {@code not_found}, never a {@code 403}). Both read the {@code tenantId} from the trusted security
  context (the S03-13 local-skeleton {@code @Value} default is a tenant-isolation bypass left as a
  follow-up — threading it from the verified context needs a new `workflowdefinition` → `identity`
  edge, an ADR) and filter by
  {@code tenant_id} in the SQL itself (invariant 1). {@code WorkflowDefinitionService} gains
  {@code listDefinitions(tenantId, page, requestedSize)} — clamps {@code size} to a configured
  maximum ({@code MAX_PAGE_SIZE = 100}) so one call cannot pull the whole collection, rejects a
  negative page or a non-positive size with {@code 400}, and reports a missing database as
  {@code 503} — and {@code getDefinition(tenantId, workflowId)}. The list is ordered by a *total*
  order, {@code created_at DESC, id DESC} (the {@code (tenant_id, created_at)} index,
  {@code id} as the unique tie-breaker) so every page is stable; the repository gains
  {@code WorkflowDefinitionRepository.listByTenant} and {@code countByTenant}. The page is a new
  module public type, {@code WorkflowDefinitionPageDto} ({@code items[]}, {@code page}, {@code
  size}, {@code totalElements}, {@code totalPages}, {@code hasNext}, {@code hasPrevious}). Tests are
  DB-free, matching the existing suite's mock conventions: {@code WorkflowDefinitionRepositoryTest}
  asserts the tenant-bound, ordered, `LIMIT/OFFSET` SQL; {@code WorkflowDefinitionServiceTest}
  covers the effective/clamped size, the {@code 400}/{@code 503} boundaries and the cross-tenant
  {@code 404}; {@code WorkflowDefinitionControllerTest} covers the {@code 200} list/get paths and a
  {@code 400} for a non-UUID id. All green with {@code ./gradlew check} (unit + architecture +
  formatting).
  [`contracts/openapi/openapi.json`](contracts/openapi/openapi.json) is regenerated to include the
  new paths and the {@code WorkflowDefinitionPageDto} schema (generated deterministically from the
  running application; never edited by hand). {@code docs/architecture/module-api.md},
  {@code docs/domain-glossary.md} and {@code docs/architecture/engineering-conventions.md} are
  updated for the new list endpoint and its pagination/tenant-isolation convention.

- **Create workflow definition endpoint (S03-13).** `POST /api/v1/workflows` builds a
  DRAFT definition and returns {@code 201 Created} with the {@code workflowdefinition} DTO; a
  blank {@code name} fails bean validation and is rendered as a {@code 400} validation Problem
  Details by the global error handler.
  [`contracts/openapi/openapi.json`](contracts/openapi/openapi.json) is regenerated to include the
  new path (generated deterministically from the running application; never edited by hand).

- **Create workflow definition endpoint (S03-13).** `WorkflowDefinitionController`
  (`com.example.agentflow.workflowdefinition.web`, `@RestController` on `/api/v1/workflows`)
  validates the {@code CreateWorkflowDefinitionRequest} ({@code name} {@code @NotBlank}, optional
  {@code description}) with {@code @Valid}, builds the draft through the application service, and
  returns {@code 201 Created} with a {@code WorkflowDefinitionDto} (camelCase: {@code id},
  {@code tenantId}, {@code name}, {@code description}, {@code status}, {@code version},
  {@code createdBy}, {@code createdAt}). The {@code tenantId} is read from application
  configuration (`@Value("${agentflow.local.tenant-id:...}")`, defaulting to the zero UUID), not
  from the request body (invariant 1 — the tenant is never derived from input); this is a
  temporary local-skeleton tenant-isolation bypass left as a follow-up — threading the tenant from
  the verified context needs a new `workflowdefinition` → `identity` edge (an ADR).
  `WorkflowDefinitionService.createDefinition(tenantId, actor, name,
  description)` owns the use case: it builds the {@code WorkflowDefinition.draft} with the
  caller-supplied tenant and records it through the tenant-scoped {@code WorkflowDefinitionRepository}.
  The repository bean is only present when a {@code DataSource} exists, so the dependency is
  {@code @Autowired(required = false)} to keep the application bootable without a database (S01-02);
  a missing database yields {@code 503}, and a duplicate {@code name} (the {@code (tenant_id, name)}
  unique constraint, ADR-009) yields {@code 409 conflict}. The DTO is a module public type
  (`WorkflowDefinitionDto`, `com.example.agentflow.workflowdefinition.application`); the controller
  compiles-dependency-only on {@code workflowdefinition} + {@code agentcatalog}, so the architecture
  gate stays green. Tests: {@code WorkflowDefinitionServiceTest} (Mockito — draft build with the
  correct tenant/version/status, {@code 409} on the unique constraint, {@code 503} without a
  database) and {@code WorkflowDefinitionControllerTest} (MockMvc `standaloneSetup` with a mocked
  service, the real {@code GlobalExceptionHandler} via `setControllerAdvice`, and a real Jakarta
  validator via `setValidator` — {@code 201} with the draft DTO and {@code 400 validation} for a
  blank {@code name}). All green with `./gradlew check` (unit + architecture + formatting).
  `application.yml` / the profile configs do not yet set {@code agentflow.local.*}; the defaults are
  sufficient for the skeleton until S04-10.

- **OpenAPI contract (S03-12).** The `apps/agentflow-app` artifact now ships a machine-readable
  OpenAPI description for the REST API. The `springdoc-openapi-starter-webmvc-ui` starter is added to
  `apps/agentflow-app/build.gradle.kts` (`implementation(libs.springdoc.openapi.starter.webmvc.ui)`;
  version from the catalog) — compatible with the Boot 4.1.1 / Spring Framework 7 / Jackson 3 stack
  (no BOM override). `OpenApiConfig` (package-private, `com.example.agentflow.config`) exposes an
  `OpenAPI` bean `agentFlowOpenApi` that builds the description deterministically: `AgentFlow API` /
  `v1`, the tenant-only-from-verified-security-context note, and the `bearerAuth` HTTP bearer (JWT)
  scheme (`type: http`, `scheme: bearer`, `bearerFormat: JWT`) that stands in for the OIDC resource
  server added in S04. swagger-core `2.2.55` relocated the model classes into subpackages
  (`io.swagger.v3.oas.models.info.Info`,
  `io.swagger.v3.oas.models.security.SecurityScheme` / `SecurityRequirement`), so the config imports
  the relocated types and the fluent setters are unchanged. springdoc serves the description at
  `/api/v1/v3/api-docs` (Swagger UI enabled per profile) and the spec is regenerated and checked in
  under [`contracts/openapi/openapi.json`](contracts/openapi/openapi.json);
  [`contracts/openapi/README.md`](contracts/openapi/README.md) documents that it is generated
  deterministically and that breaking changes need a baseline update (the CI gate is `S03-16`).
  `OpenApiContractTest` (a `@SpringBootTest` context-load test) asserts the model is generated, the
  metadata is documented (`v1`), the `bearerAuth` scheme is an HTTP bearer and the global security
  requirement references it — all green with `./gradlew check` (unit + architecture + formatting).
  `application.yml` records the springdoc config and the DataSource exclusion; `application-kubernetes.yml`
  disables Swagger UI in production; `docs/architecture/module-api.md` drops its `⚠ planned (S03-12)`
  marker for `contracts/openapi/`.

- **Global error handler (`S03-11`).** The twelve modules now route every thrown
  exception through one cross-cutting `@RestControllerAdvice`,
  `com.example.agentflow.common.error.GlobalExceptionHandler`, so a client always
  gets a stable RFC 9457 `ProblemDetails` envelope (`type`, `title`, `status`,
  `error_class`, `detail`, `instance`, `correlation_id`, `retryable`) with no
  stack trace or internals leaked. It classifies by the `common`-owned
  `ErrorClass` taxonomy: field/parameter validation and an unreadable JSON body
  become `400 validation`; a `ResponseStatusException` is re-classified to its
  canonical class (a `404` is the permanent `not_found` problem, a bare `403`
  resolves to `authorization`, a `409` to `conflict`); a request that matched no
  route (`NoResourceFoundException`, or its `NoHandlerFoundException` superclass)
  is likewise a permanent `404 not_found`, never misreported as a 500 by the
  catch-all; and anything else is a `500 internal` catch-all — the full stack
  trace goes to the structured log (with the correlation id in the MDC) but the
  client sees a static message. Domain exceptions (`ConflictException`,
  `InvalidGraphException`, …) stay in their owning modules; the owning
  application layer maps them to a `ResponseStatusException` with the right
  status, which the handler renders here — keeping the handler in `common`
  (cross-cutting packages own only shared primitives) instead of importing every
  module's domain exceptions. The `correlation_id` reuses the trace id when
  tracing is wired (S13) and otherwise allocates a fresh UUID. Verification is a
  real-server test, `GlobalExceptionHandlerTest`, exercised over an
  `@SpringBootTest(RANDOM_PORT)` with a throwaway stub controller that only
  throws Spring types (no domain import, so the `common` architecture gate stays
  green): it asserts the raw JSON envelope for validation, not-found, conflict
  and internal — the correct `error_class`, `status`, `retryable=false`,
  `type=about:blank`, the request `instance`, a UUID `correlation_id`, that a
  blank-field validation lists the field, that malformed JSON maps to validation,
  and that the internal error never leaks its raw message. Confirmed the
  Jackson 3.x / `tools.jackson` serialization emits the taxonomy's `snake_case`
  keys. All 53 tests and `./gradlew check` (unit + architecture + formatting)
  are green.

- **Optimistic locking on workflow definitions (`S03-10`).** The mutable `workflow_definitions`
  row (migration `V3_3_4`, `workflowdefinition`-owned) now carries an application-managed
  `version INTEGER NOT NULL DEFAULT 0` optimistic-lock counter — kept distinct from the monotonic
  business `version` that addresses an immutable `workflow_definition_versions` row
  (`database-conventions.md`, *Optimistic versioning*). The single `workflowdefinition` store
  (`WorkflowDefinitionRepository`, ADR-009) turns `updateMetadata` into a conditional update that
  also checks `AND version = :expectedVersion` and bumps `version = version + 1` in the same
  statement; a zero-rows outcome (missing, cross-tenant, wrong status, or stale version — another
  writer changed the row since it was read) is mapped to `409 Conflict` via `ConflictException`, so
  one of two competing updates never silently overwrites the other (the S03-10 acceptance
  criterion). The `WorkflowDefinition` value model gains the `version` field (a fresh draft starts
  at 0, matching the `DEFAULT 0`), and `WorkflowDefinitionMapper` reads it back. Tests are
  DB-free (mocked `JdbcTemplate` / `ResultSet`), matching the current suite: the mapper round-trips
  `version`, the happy path bumps it, and a stale version is rejected as a conflict. A
  real-PostgreSQL integration test for the optimistic-lock round-trip (and the `V3_3_4` migration)
  is added in `S03-15` (the migration-test sprint, which owns the real-PostgreSQL suite); no H2 is
  used as a stand-in (`AGENTS.md`). `docs/domain-glossary.md` records the new `version` column.
  Verified with `./gradlew check` on JDK 25 and by applying `V3_3_4` against a live PostgreSQL.

- **JSONB graph mapping (`S03-09`).** The JSONB `graph_json` column of
  `workflow_definition_versions` (migration `V3_3_2`, `workflowdefinition`-owned)
  now round-trips through Spring Data JDBC as a declarative typed DAG. New domain
  value objects `WorkflowGraph` / `WorkflowNode` and `InvalidGraphException`;
  `WorkflowGraphValidator` is the structural gate that rejects an invalid payload
  *before* it is serialized or an active version is saved — the graph must hold
  at least one node, every node a non-blank id and type, and every type must be
  one of the glossary's allowed node types
  (`RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`,
  `FINALIZE`), anchored verbatim to the domain glossary. Deep DAG validation
  (cycles, connectivity) stays with the DSL validator (`S09`).
  `WorkflowGraphCodec` is the single owner of the JSONB round-trip and enforces
  payload-size bounds: `serializeForStorage(graph, maxBytes)` rejects an
  oversized or misconfigured (`< 1`) graph with `InvalidGraphException`. The
  limit is configured through `AgentFlowProperties.Graph.maxBytes`
  (`agentflow.workflow.graph.max-bytes`, env `AGENTFLOW_WORKFLOW_GRAPH_MAX_BYTES`,
  default 1 MiB, `@Min(1)`), registered via `@EnableConfigurationProperties` —
  never hard-coded. The `WorkflowVersionRowMapper` reads the `graph_json` text
  inline and parses it through the codec; `org.springframework.jdbc.core.ColumnMapper`
  was removed in Spring Framework 7, so the graph column is parsed here rather
  than via a `ColumnMapper` (the codec owns the round-trip). Tests are DB-free
  (mocked `ResultSet` / `JdbcTemplate`), matching the current suite: the codec
  round-trip preserves meaningful fields (node id/type/config), oversized payloads
  are rejected, the validator rejects unknown/blank graph shapes, and the
  config is validated. A full Testcontainers integration test for this mapper's
  JSONB round-trip is added in `S03-15` (the migration test sprint), which owns
  the real-PostgreSQL suite; no H2 is used as a stand-in (`AGENTS.md`).
  Verified with `./gradlew check` on JDK 25.

- **Database conventions (`S03-06`).**
  `docs/architecture/database-conventions.md` is the new short reference for how
  every schema table is written — so the first migration already matches the
  style and adding a new table needs no new convention. It fixes four things:
  **`timestamptz`** timestamps (UTC at rest, `Instant` in Java, never
  `timestamp without time zone`); a **unified audit-timestamp set** — an
  immutable `created_at` on every table plus `updated_at` only where a row is
  mutable and tracked, with no ad-hoc timestamp names; **constraint/index
  naming** — composite business uniques as `<table>_<cols>` (matching the
  existing `workflow_definitions_tenant_name` /
  `workflow_definition_versions_workflow_version`), implicit PK/FK/unique
  defaults, lookups as `<table>_<cols>_idx` (one per real path, not per column);
  and **optimistic versioning** — an application-managed `version` counter for
  mutable rows (conditional update → `409 Conflict`) that is explicitly kept
  distinct from the monotonic business `version` that addresses an immutable
  workflow version. It also resolves the stale `database.md` / `S03-16`
  placeholder that `docs/README.md`, the architecture index and the
  `migrations/README.md` pointed at: that path did not exist (`S03-16` is the
  OpenAPI gate), so the two markdown trees and the migrations README now link the
  real `database-conventions.md` (`S03-06`) and its `⚠ planned` markers are
  removed, and the `Related` sections of `database-migrations.md` and
  `engineering-conventions.md` point at it as a live link. Anchored to the domain
  glossary (canonical column names), `engineering-conventions.md` (identifier /
  time format) and `ADR-002`…`ADR-004`. Documentation-only change — verified by
  integrity (`git diff --check`, every in-document link resolved against the
  repo) — no build gate for a documentation-only task.

- **Workflow definition versions table (`S03-05`).**
  `apps/agentflow-app/src/main/resources/migrations/V3_3_2__create_workflow_definition_versions.sql`
  is the third owning-module migration — version `3.3.2`, ordered after
  `workflow_definitions` (`3.3.1`). It creates the `workflow_definition_versions`
  table, the `workflowdefinition`-owned store of one immutable row per workflow
  definition version (glossary §4, plan §8.1): a `UUID` primary key `id`, a
  `workflow_id` foreign key to `workflow_definitions.id`, the monotonic
  `version`, the `graph_json` JSONB graph of typed steps, its `checksum`, the
  `status` activation metadata (`'DRAFT'` until activated, then immutable —
  `S05-12`), the nullable `created_by` actor and a `timestamptz` `created_at`.
  `unique (workflow_id, version)` is enforced by PostgreSQL, so every version is
  addressable unambiguously and is never overwritten by another version; tenant
  isolation is **not** stored here (`ADR-007`) — it flows through the parent's
  `tenant_id`, so another tenant's version is indistinguishable from a missing
  one. (The plan's `S03-05` prose names the parent `definition_id` and lists a
  separate `schema version`; the canonical column per the domain glossary, plan
  §8.1 and the module map is `workflow_id` with no separate schema-version
  column, so this migration follows the canonical model.) The migration is
  forward-only, idempotent (`IF NOT EXISTS`) and tagged with its owning module
  (`workflowdefinition`) and task (`S03-05`) in a table `COMMENT`. It applies as
  the one controlled step (`./gradlew flywayMigrate`, never on startup —
  `spring.flyway.enabled=false`, `ADR-008`) and is verified in this change
  against a real local PostgreSQL 18.6: `./gradlew flywayMigrate` brings the
  schema to `v3.3.2`; a direct inspection confirms the columns, the `UNIQUE
  (workflow_id, version)` and `workflow_id` → `workflow_definitions` FK
  constraints, that a duplicate `(workflow_id, version)` is rejected (the
  `S03-05` acceptance criterion), that a non-existent `workflow_id` is rejected
  by the FK and that a valid insert succeeds; the two stored versions keep the
  `DRAFT` default. No Java build gate applies to SQL migrations; verification is
  the integrity check (`git diff --check`) plus the real `flywayMigrate` apply
  and the live PostgreSQL inspection above.

- **Workflow definitions table (`S03-04`).**
  `apps/agentflow-app/src/main/resources/migrations/V3_3_1__create_workflow_definitions.sql`
  is the second owning-module migration — version `3.3.1`, ordered after the
  `tenants` table (`3.3.0`). It creates the `workflow_definitions` table, the
  `workflowdefinition`-owned tenant-scoped container of a workflow definition
  (glossary §4, plan §8.1): a `UUID` primary key `id` that is the definition's
  stable identity across its versions, a `tenant_id` foreign key to `tenants`
  that scopes every row to one isolated organization (tenant isolation,
  `ADR-007`), the tenant-scoped business `name`, free-text `description`, a
  lifecycle `status` (`'ACTIVE'` by default) and `created_by` actor that the
  application layer manages, and a `timestamptz` `created_at`. `unique
  (tenant_id, name)` is enforced by PostgreSQL, so a tenant cannot register two
  definitions with the same name; versions live in a separate
  `workflow_definition_versions` table. The migration is forward-only,
  idempotent (`IF NOT EXISTS`) and tagged with its owning module (`workflowdefinition`)
  and task (`S03-04`) in a table `COMMENT`. It applies as the one controlled step
  (`./gradlew flywayMigrate`, never on startup — `spring.flyway.enabled=false`,
  `ADR-008`) and is verified in this change the same way as `tenants`: a real
  `./gradlew flywayMigrate` brings the schema to `v3.3.1` on the local
  PostgreSQL 18.6, and a direct inspection confirms the columns, the `UNIQUE
  (tenant_id, name)` and `tenant_id` → `tenants` FK constraints, that a
  definition cannot be inserted without `tenant_id`/`name`/a valid `tenant_id`
  (the `S03-04` acceptance criterion), and that a valid insert succeeds.
  `docs/domain-glossary.md` is updated to list the fuller column set so the
  canonical reference matches the schema. Verified by integrity (`git diff --check`),
  a real `flywayMigrate` apply and a live-PG inspection — SQL migrations have no
  Java build gate.

- **Tenants table (`S03-03`).** `apps/agentflow-app/src/main/resources/migrations/V3_3_0__create_tenants.sql`
  is the first owning-module migration. It creates the `tenants` table — the single
  `identity`-owned table (glossary §1, plan §8.1) — with a `UUID` primary key `id`,
  a unique `slug` (the human-facing tenant identifier), an administrator-only
  `status` (`'ACTIVE'` by default) and a `timestamptz` `created_at`, matching the
  glossary's canonical four-column shape exactly (no `name`, no `updated_at`).
  `slug` uniqueness is enforced by PostgreSQL (`unique slug`, plan §8.1); `status`
  changes are an administrator-only action (enforced in the application layer). The
  migration is forward-only, idempotent (`IF NOT EXISTS`) and tagged with its owning
  module (`identity`) and task (`S03-03`) in a table `COMMENT`. It applies as the one
  controlled step (`./gradlew flywayMigrate`, never on startup — `spring.flyway.enabled=false`,
  ADR-008) and is applied to a fresh PostgreSQL with its constraints verified by the
  migration integration suite (`S03-15`). The illustrative example in
  [`docs/architecture/database-migrations.md`](docs/architecture/database-migrations.md)
  (`S03-02`) is corrected to match the glossary: its `name` column becomes the unique
  `slug`. Documentation and migration change together — verified by integrity
  (`git diff --check`) and a real `flywayMigrate` apply; SQL migrations have no Java
  build gate.

  version catalog, with `flyway-database-postgresql` for PostgreSQL 18 support) is
  the single migration tool. Migrations live in `classpath:migrations` as
  forward-only SQL, applied by the new `./gradlew flywayMigrate` task (Flyway Java
  API, connection from `FLYWAY_*` env vars falling back to the Compose `local`
  defaults) rather than on application startup — `spring.flyway.enabled=false`.
  Verified against PostgreSQL 18.6; documented in
  [`docs/adr/ADR-008`](docs/adr/ADR-008-database-migrations.md).

- **Database migration rules (`S03-02`).** `docs/architecture/database-migrations.md`
  is the new rules and single process for changing the PostgreSQL schema. It fixes
  how every migration is authored, versioned, verified and applied: **forward-only**
  (never edit, reorder or delete an applied migration — Flyway's checksum check
  enforces this), **expand / contract** for any incompatible change so a running
  service is not broken by one step, **forward-fix** for a defect in an applied
  migration instead of editing it, **one owning module per migration** (name + SQL
  `COMMENT`), idempotent `IF NOT EXISTS` guards, `V<ver>__<desc>.sql` versioning and
  the one controlled apply step (`./gradlew flywayMigrate`, later a Helm Job —
  never on startup, never concurrently). Rollback is a new forward migration or a
  backup restore, not `flyway rollback`. It complements
  [`ADR-008`](docs/adr/ADR-008-database-migrations.md) (the Flyway tool choice) and
  precedes `database-conventions.md` (`S03-06`). The `⚠ planned (S03-16)`
  `database.md` placeholder in `docs/README.md` and `docs/architecture/README.md` is
  replaced by the two plan-named files (`database-migrations.md` now,
  `database-conventions.md` planned) — the index pointed at a merged file under the
  wrong task (S03-16 is the OpenAPI gate). Documentation-only change — verified by
  integrity (`git diff --check`, link resolution against the repo) — no build gate
  for a documentation-only task.

- **Architecture index (`S02-15`).** `docs/architecture/README.md` is the new
  navigation page for the architecture documentation. It links the documents that
  exist today (C4 Level 1 *system context*, C4 Level 2 *module map*, public module
  API, domain glossary, error taxonomy, engineering conventions, dependency
  management, ADR register, threat model), marks the ones that do not yet exist
  with a `⚠ planned` badge so the index never carries a broken link, and records
  the current architectural constraints (ADR-001…004, tenant isolation, untrusted
  planner, error classification, build integrity) and the list of deferred/open
  decisions (base package, execution SPI — now resolved in the module map, owners
  of unnamed data/logic, permission catalogue, persistence approach + migration
  tool). The module names and external systems in these documents are verified
  against the build: the twelve modules plus `common` live under
  `apps/agentflow-app/src/main/java/com/example/agentflow/<module>/` and match the
  base package `com.example.agentflow` (`AgentflowApplication`). The parent
  [`docs/README.md`](docs/README.md) index now points at this architecture
  index instead of `dependency-management.md`. Documentation-only change —
  verified by integrity (`git diff --check`, link resolution against the repo) —
  no build gate for a documentation-only task.
- **ADR register and decision lifecycle (`S02-16`).** `docs/adr/README.md` is now
  the navigational ADR register: a table of every decision (ID, title, date, task,
  status) plus a full description of the four statuses (`Proposed`, `Accepted`,
  `Superseded`, `Deprecated`), the decision lifecycle (`Proposed` → `Accepted` →
  `Superseded`/`Deprecated`), and a "marking superseded decisions" procedure
  (never edit an accepted ADR in place; a successor's `Supersedes` points at the
  old record, which is kept and marked `Superseded`). The register is the single
  source of truth for which decisions exist and their status, and it links from /
  to the architecture index (`docs/architecture/README.md`) where the standing
  constraints reference `ADR-001` and the consistency ADRs (`ADR-002`…`ADR-004`).
  Documentation-only change — verified by integrity (`git diff --check`, link
  resolution against the repo) — no build gate for a documentation-only task.
- **PR architecture review checklist (`S02-14`).** The PR template
  (`.github/PULL_REQUEST_TEMPLATE.md`) gains a dedicated **Architecture review**
  section that replaces the previous single `Risks / ADR` block. Every PR now
  self-checks the architectural dimensions that matter for this repo: **Module
  ownership** (stay within the owning module's tables, repositories and internal
  packages, use the public API + published events, no new dependency cycle),
  **Tenant scope** (`(tenant_id, resource_id)` scoped reads/writes/searches, a
  foreign tenant's resource is indistinguishable from a missing one, tenant from
  verified context not the request body), **Retries & reliability** (transient vs.
  permanent classification, bounded exponential backoff/jitter, external calls
  persisted before the call and treated as at-least-once not exactly-once),
  **Side effects** (idempotent or lease/epoch-fenced calls, cancellation on a safe
  boundary, outbox/inbox and dedup respected), **Audit** (append-only actor/action/
  outcome without secrets), **Telemetry** (no secrets, full prompts/documents or
  high-cardinality labels in metrics/logs) and an **Integrity** gate, plus an
  explicit **ADR required?** question for a new module dependency, BOM bypass,
  persistence/migration change, contract change or invariant change. The checklist
  applies to code, config, migrations and contract changes, references
  `docs/architecture/module-map.md` and the sprint gate, and is documented in
  `CONTRIBUTING.md`'s review-before-PR guidance. Documentation-only change to the
  contribution workflow — no code, contracts or the `docs/README.md` tree changed.
  Verified by integrity (`git diff --check`, diff review) — no build gate for a
  PR-template change.
- **ADR template (`S02-12`).** `docs/adr/000-template.md` is the copy-paste starting
  point for any new architecture decision record: the header metadata (`Status`,
  `Date`, `Task`, `Supersedes`/`Superseded by`), the mandatory `Context` →
  `Decision` → `Alternatives considered` → `Consequences` sections (matching the real
  ADRs `ADR-001`…`ADR-004`), the rules for *when an ADR is needed* (new module
  dependency, BOM bypass, persistence/migration change, new node type or risk class,
  abandoning an invariant), and the register steps (next free `ADR-NNN`, row in
  `docs/adr/README.md`, link from the architecture index). Statuses follow
  `docs/adr/README.md`: `Proposed` → `Accepted`; a superseded ADR is kept and linked
  to its successor. `docs/adr/README.md` points to the template; `000-template.md` is
  the only file in `docs/adr/` that is not itself an ADR. Verified by integrity (every
  relative link resolves, `git diff --check`) — no build gate for a documentation-only
  task.
- **Error taxonomy (`S02-10`).** `docs/architecture/error-taxonomy.md` is the new
  canonical set of error classes every module classifies by; each has a stable
  `error_class` identifier, an HTTP mapping (RFC 9457 Problem Details), a retry
  decision, and an example. Classes: `validation` (400), `authentication` (401),
  `authorization` (403), `conflict` (409), `transient_infra` (503),
  `provider_rate_limit` (429), `provider_timeout` (504), `tool_policy_denial`
  (403), `cancellation` (202, not an error), `internal` (500 catch-all), plus the
  `404`/`code: not_found` mapping for missing and foreign-tenant resources. Retry
  applies only to the transient classes (bounded exponential backoff + jitter,
  `S07-10`); permanent classes end as `FAILED` / client error / DLQ and never
  retry; ambiguous provider-timeout outcomes are recorded, never claimed
  exactly-once (`ADR-003`). The identifiers are the contract shared by the global
  error handler, the worker retry classifier (`S07-11`), and the `error_class`
  metric label; the taxonomy is owned by `common`. `docs/README.md` links to it
  and its `⚠ planned (S02-10)` marker is removed. Verified by integrity (link
  resolution, `git diff --check`) — no build gate for a documentation-only task.
- **Time and identifier conventions (`S02-11`).**
  `docs/architecture/engineering-conventions.md` is the new engineering
  conventions for identifiers and time: **identifiers** are `UUID`, `UUIDv7` by
  default for generated IDs (time-sortable, index-friendly) and `UUIDv4` only
  where ordering must be avoided; no sequential/integer business PKs (guessable
  → enumeration); foreign keys are `<resource>_id` in `snake_case`. **Time** is
  UTC everywhere, stored as PostgreSQL `timestamptz` (never `timestamp without
  time zone`), expressed in Java as `Instant`/`OffsetDateTime` (never
  `java.util.Date`/`Calendar`/`LocalDateTime`, never `System.currentTimeMillis()`),
  and always produced through the injected `Clock`. The injectable `Clock` is the
  `S02-11` acceptance criterion: production uses a `Clock.systemUTC()` bean and
  time-dependent tests override it with `Clock.fixed(Instant, UTC)` (a
  lease/recovery fencing test is shown), so a lease, retry or recovery boundary
  is deterministic. **Naming** is layered: `snake_case` plural tables and
  `snake_case` columns matching the glossary verbatim, `camelCase` API fields
  (`runId` vs `run_id`), `UPPER_SNAKE_CASE` enums in DB / `snake_case` in JSON,
  and the Prometheus-safe `snake_case` `error_class` (`S02-10`). Money stays
  `NUMERIC`/`BigDecimal`. The doc anchors to the glossary (canonical names), the
  consistency ADRs (`ADR-002`…`ADR-004`) and invariant 10, distinguishes its
  scope from the planned `docs/architecture/database.md` (tables/migrations,
  `S03-16`), and links only to documents that exist. `docs/README.md` links to
  it in the architecture tree and the entry is `N/A` for any behaviour change.
  Verified by integrity (every relative link resolves, `git diff --check`) — no
  build gate for a documentation-only task.
- **Module boundary architecture gate (`S02-08`).** A JUnit 5 test,
  `ArchitectureBoundaryTest`, runs `@ArchTest` rules that forbid every compile-time
  edge in `docs/architecture/module-map.md` that the table does not permit. Each of
  the twelve modules contributes one sub-rule
  (`noClasses().that().resideInAPackage(<module>).should().dependOnClassesThat()
  .resideInAnyPackage(<all other modules minus self minus allowed>)`); they are
  combined into a single `CompositeArchRule` so one introduced violation anywhere
  fails `./gradlew check`. External dependencies (Spring, Jackson, `jakarta`, the
  database, Kafka) are not module edges and are therefore not forbidden. `common`
  is excluded from the forbidden set the same way it is in the map (shared
  primitives only). Verified against the acceptance criterion: an artificial
  `common -> audit` import fails the build with a precise message
  (`Field <common.X.t> has type <audit.Y> ... module common may only
  compile-depend on its own package plus []`), and the clean skeleton passes.
  ArchUnit `1.5.1` is pinned in the version catalog
  (`gradle/libs.versions.toml`) and pulled through
  `testImplementation(libs.archunit.junit5)` — it is not part of the Spring Boot
  4.1.1 BOM, so this does not bypass it. IMPORTANT: every `@ArchTest` class must be
  annotated `@AnalyzeClasses`; ArchUnit 1.5.x otherwise refuses the class and
  silently runs zero tests (a false pass). This is recorded in the class Javadoc
  so the annotation is never dropped.
- **No-cycles architecture gate (`S02-09`).** The twelve modules of the modular
  monolith must compile-depend on each other in one direction only: a compile-time
  cycle would make the ownership table and the sanctioned feedback path (the
  execution `StepHandler` SPI) meaningless. The gate has two parts. `PackageDependencyGraph`
  (a pure, dependency-free utility in `src/test/java/com/example/agentflow/architecture/`)
  enumerates every elementary cycle in the module graph; `PackageDependencyGraphTest`
  feeds it a specifically created cycle and asserts it is reported (the `S02-09`
  acceptance criterion) and that a self-loop is not treated as a cycle.
  `NoCyclicModuleDependenciesTest` imports the application packages with ArchUnit,
  derives the real module -> module edges from the bytecode, and asserts the graph
  is acyclic at `./gradlew check` time. The gate is a plain JUnit test (not an
  `@ArchTest`) because it has to aggregate the class graph before asserting, and it
  asserts the analysed class set is non-empty so a failed import cannot pass the
  gate with zero analysed packages (a false pass). Verified: the clean skeleton
  passes; a deliberately introduced `config` -> `observability` -> `config` cycle is
  reported by the detector and fails the test; the cycle is then reverted so the
  application stays runnable. The allowed direction of dependencies is documented in
  `docs/architecture/module-map.md` (`docs/README.md` architecture section) and the
  execution SPI is the only feedback path, which is what keeps the graph acyclic.
  No production code changed; ArchUnit `1.5.1` (pinned in the version catalog,
  `S02-08`) is reused, so no dependency is added.
- **Architecture gate wired into `check` (`S02-13`).** The module-boundary (S02-08) and
  no-cycles (S02-09) architecture gates now run as part of the standard Gradle `check`
  lifecycle, so a declared module boundary fails `./gradlew check` on every PR. The gates
  live in `apps/agentflow-app/src/test/java/com/example/agentflow/`: `ArchitectureBoundaryTest`
  (`@AnalyzeClasses` + `@ArchTest` — the annotation is mandatory on every ArchUnit 1.5.x
  class, otherwise the engine silently runs zero tests and the build passes without checking
  anything) enforces the `module -> module` edges in `docs/architecture/module-map.md`, and
  `NoCyclicModuleDependenciesTest` (with the dependency-free `PackageDependencyGraph`)
  verifies the module graph is acyclic. `test` is the fast gate - unit tests plus both
  architecture gates - and `check` depends on it (plus `spotlessCheck` and `verifyWrapper`),
  so the gate cannot be skipped; `failOnNoDiscoveredTests = true` turns a future zero-test
  discovery into a build failure instead of a silent pass. Verified against the acceptance
  criterion: a temporary `common -> observability` import fails `./gradlew check` with a
  precise message (`Field <common.TmpBoundaryViolation.properties> has type
  <observability.LoggingContextProperties>`) and reverting restores a green build. The test
  task structure is two tiers - the fast `check` (unit + architecture + formatting, no
  Docker) on every PR and the full `integrationTest` (Testcontainers on the real
  PostgreSQL/Kafka/Redis images, added in S03+). No new dependency; ArchUnit `1.5.1`
  (pinned in the version catalog, S02-08) is reused.
- **Package-by-feature module skeleton (`S02-07`).** The twelve bounded contexts of the modular
  monolith now each own a package under `apps/agentflow-app/src/main/java/com/example/agentflow/<module>/`
  (`identity`, `agentcatalog`, `workflowdefinition`, `workflowexecution`, `toolexecution`,
  `knowledgebase`, `ingestion`, `ai`, `usage`, `audit`, `messaging`, plus the cross-cutting
  `observability`). Each carries a `package-info.java` declaring the module's public-API boundary
  (application services and permitted DTO/ID/enum types — everything else per
  `docs/architecture/module-api.md`) and an `AGENTS.md` naming the single owner, the table it owns,
  and the internal packages (`persistence` / `web` / `worker` / `domain`) another module must not
  import. The `common` package is scoped to shared primitives only (IDs, `Clock`, the error taxonomy,
  redaction) — no business logic, so it is not an escape route for business rules. The S01-19
  structured-logging code that lived in `com.example.agentflow.logging` is consolidated into
  `observability` (the `logback-spring.xml` `<valueMasker>` reference is updated accordingly). This
  satisfies the `S02-07` acceptance criterion — every function has an explicit module owner — and
  leaves `./gradlew check` green. Boundaries are enforced by the architecture gate (`S02-08` /
  `S02-09`).
- **Consistency model ADRs (`S02-06`).** The durable-execution consistency model is documented as three
  architecture decision records — `docs/adr/ADR-002-postgresql-source-of-truth.md` (PostgreSQL is the
  single store of record; Kafka delivers and Redis caches, so losing either must not lose a run),
  `docs/adr/ADR-003-at-least-once-idempotency.md` (at-least-once delivery plus inbox/`event_id`
  idempotency; an external LLM/tool call is never exactly-once and its ambiguous outcome is marked in
  usage/history), and `docs/adr/ADR-004-transactional-outbox.md` (state and the `outbox_events` record
  write in one PostgreSQL transaction, published by a separate idempotent poller, with no shared
  PostgreSQL/Kafka transaction). Together they satisfy the `S02-06` acceptance criterion: the documents
  make no unjustified end-to-end exactly-once claim. They expand the plan §3 condensed descriptions of
  `ADR-002`…`ADR-004` (Context, Decision, Alternatives, Consequences), keep `docs/domain-glossary.md`'s
  reference to `ADR-003` valid, and the `adr/` register and the `adr/` tree in `docs/README.md` now
  point at them.
- **Modular monolith first ADR (`S02-05`).** `docs/adr/ADR-001-modular-monolith.md` records the
  deployment/topology decision: all twelve bounded contexts live in one Spring Boot artifact
  (`apps/agentflow-app`) and one repository with explicit public APIs between contexts, enforced
  by the automated architecture gate (`./gradlew check`, `S02-08` / `S02-09` / `S02-13`); the
  topology is one image, several roles, split physically only when a measured need (divergent
  resource profile, independent scaling, a distinct security boundary or an independent failure
  domain) appears. It captures the Context, Alternatives, Decision and Consequences the `S02-05`
  acceptance criterion requires, compares microservices-first and a flat monolith, and pins the
  split triggers plus the "the API Deployment must not run consumers; worker Deployments must not
  expose controllers as a public entrypoint" constraint from the plan §4.1. The ADR register
  (`docs/adr/README.md`) and the `adr/` tree in `docs/README.md` now point at it.
- **Module public API map (`S02-04`).** `docs/architecture/module-api.md` is the
  new public-API boundary for every bounded context. For each module it lists the
  public application services (use cases), the published event types (`agentflow.*
  .vN`), the permitted DTOs / IDs / enums, and the internal packages
  (`persistence` / `web` / `worker` / infra) that other modules must not import.
  It answers the `S02-04` acceptance criterion — another module reaches a target
  module **only** through an application service, a permitted public type or a
  published event type, never by importing repositories or entities — and is the
  "how to reach" counterpart to the module map (`docs/architecture/module-map.md`,
  `S02-03`) and the canonical identifiers in the domain glossary (`S02-01`). The
  `docs/README.md` documentation index links to it.
  single source of truth for terms: it defines the core concepts (tenant, agent /
  agent profile, tool, workflow definition vs. workflow version, run vs. step /
  attempt, event/envelope/outbox/inbox, knowledge base/document/chunk, citation,
  approval, usage record/budget/quota, audit event) and pins canonical
  identifiers to the target data model (`S03`), `/api/v1` API (`S03-12`),
  run/step state machine (`S05`), Kafka/event contract (`S06`) and AI
  orchestration (`S09`). A "do-not-use" alias section keeps the names that drift
  most easily (run, step, event, agent, tool, workflow, document, chunk,
  approval, usage) distinct across tables, DTOs and the API, satisfying the
  S02-01 acceptance criterion of one concept — one name. `docs/README.md` links
  to the glossary and its `⚠ planned (S02-01)` marker was removed.

- **Contribution and changelog rules (`S01-22`).** `CONTRIBUTING.md` now defines
  the branch, PR, test, documentation and secrets rules for contributors: one
  task per PR (`S##-##`), build through the Gradle wrapper (`./gradlew check`,
  `integrationTest`, `bootRun`), Testcontainers for DB/Kafka/Redis (no H2),
  negative/failure/recovery and tenant-isolation tests, "documentation as code",
  executable examples, and a hard rule that real credentials, API keys, JWTs and
  internal tokens never go into the repository or examples. The PR template now
  checks that `docs/`, `CHANGELOG.md` and `contracts/` are evaluated or marked
  `N/A`. `CHANGELOG.md` keeps the `[Unreleased]` skeleton with all four categories
  (`Added` / `Changed` / `Fixed` / `Security`), following Keep a Changelog; a
  first bootstrap entry is recorded under `Added`. The `⚠ planned` markers for
  these documents in `docs/README.md` were removed once they existed.

- **Documentation index and conventions (`S01-21`).** `docs/README.md` is now the
  documentation index: it shows where every topic lives, links the current
  documents (`docs/AGENTS.md`, `docs/product-scope.md`, `docs/adr/`,
  `docs/architecture/`, `docs/runbooks/`, `docs/threat-model/`, and the
  `contracts/` family) and marks future sections with a `⚠ planned` badge so the
  index stays truthful while the backlog is being filled out. A *document type →
  location* table tells a new author where each document belongs, and the
  *One source of truth / Documentation as code / Examples are executable*
  principles are restated so the index does not duplicate the rulebook in
  `docs/AGENTS.md`. `docs/AGENTS.md` gains a **Conventions** section that makes
  the Mermaid diagram rules, the examples-and-commands rules (verifiable
  commands, `curl` matching OpenAPI, no secrets), and the PR-documentation-currency
  rules (docs + contracts change with the code, remove `planned` markers, real
  PASS/FAIL/not-run verification) explicit. The root `README.md` points to the new
  index and to `contracts/`. No content was copied between documents.
- **Java 25 toolchain (`S01-05`).** The Gradle build pins the JDK 25 toolchain
  for every subproject, forces UTF-8 source encoding and a fixed compile
  release (`options.release = 25`), so the build never silently uses the
  host/system JDK. `gradle.properties` disables on-demand JDK downloads, so a
  missing or incompatible JDK fails fast and explicitly (offline CI).
- **Version catalog (`S01-06`).** Plugin and dependency versions are centralized in
  `gradle/libs.versions.toml`; modules reference them via the `libs` accessor instead
  of pinning versions inline, so one library cannot drift across build files.
  Spring-managed dependencies go through the Spring Boot and Spring AI BOMs
  (`spring-boot-dependencies:4.1.1`, `spring-ai-bom:2.0.1`).
  `pluginManagement`/`dependencyResolutionManagement` in `settings.gradle.kts` point at
  the Gradle Plugin Portal and Maven Central. Verified with `./gradlew help` and by
  resolving both BOMs (and applying the Boot plugin) against the real catalog.

- **Gradle reproducibility (S01-09).** The build now pins the wrapper checksum
  (`gradle/wrapper/gradle-wrapper.sha256`, checked by the `verifyWrapper` task on
  every invocation) and enables dependency locking (`lockAllConfigurations()`),
  committing `apps/agentflow-app/gradle.lockfile` (the main app dependency lock) plus
  the placeholder locks `settings-gradle.lockfile` and `apps/gradle.lockfile`; plugin
  versions are pinned explicitly in `pluginManagement`, so a build against a different
  graph fails instead of drifting. Update a dependency by
  editing the version catalog and regenerating the locks with
  `./gradlew --write-locks generateLockFiles`, then keep `./gradlew check` green.

- **Spring AI BOM compatibility (`S01-08`).** The Spring AI BOM
  (`spring-ai-bom:2.0.1`) is now consumed by `apps/agentflow-app`
  (`implementation(platform(libs.spring.ai.bom))`). Both BOMs import together with
  no forced overrides: Spring Framework resolves uniformly to `7.0.9` and Jackson
  to `3.1.5` (`tools.jackson`), so the dependency graph is predictable and
  reproducible. Manual overrides of Spring Framework/Jackson/Security remain
  forbidden without an ADR. A runtime test
  (`SpringAiBomCompatibilityTest`) exercises the BOM-managed `ObjectMapper`. See
  `docs/architecture/dependency-management.md` and the saved
  `docs/architecture/dependency-insight.txt`. Known follow-up (not a blocker): no
  `JavaTimeModule` on the runtime classpath, so `LocalDate` does not round-trip —
  a Jackson 3.x ecosystem question, out of scope for this task.

- **Java formatter / static check (`S01-10`).** Java sources are standardized with
  one formatter — Google Java Format, applied through the Spotless Gradle plugin
  (`com.diffplug.spotless:8.10.4`), pinned in the root `plugins {}` block. The
  `spotlessCheck` task is part of `./gradlew check`, so a formatting violation
  fails the build; `./gradlew spotlessApply` reformats every Java source under
  `src/`. Line endings, trailing whitespace and final newlines follow
  `.editorconfig` (LF). Existing skeleton files were reformatted to the standard.

- **Repository secret hygiene (`S01-11`).** Real credentials and local env files
  are not tracked. `.gitignore` excludes `.env`/`.env.*` (only `!.env.example`
  is committed), Java keytool artifacts (`*.keystore`, `*.p12`, `*.jks`) and
  local Compose data volumes / `docker-compose*.override.yml`. `.editorconfig`
  already enforces LF endings, UTF-8, trailing-whitespace trimming and final
  newlines. A `.env.example` template with `CHANGEME` placeholders and localhost
  defaults is committed for local use (real secrets stay in the environment or a
  secret store). A scan of every text blob in the commit history (43 files)
  against AWS / GitHub / Google / Stripe / Slack key patterns, PEM private keys
  key`/`token`/`password` assignments found no secret-like values.

- **PostgreSQL Compose service (`S01-12`).** `infra/compose/docker-compose.yml`
  starts the core profile's PostgreSQL on the pinned image
  `pgvector/pgvector:0.8.6-pg18` (pgvector 0.8.6 on PostgreSQL 18 — the same
  pgvector image Testcontainers uses). It exposes a named volume
  (`agentflow-pgdata`), a healthcheck (`pg_isready`), a `core` profile so the
  minimum stack starts without the other services, and env-driven config with
  local-only defaults (`POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB`/
  `POSTGRES_PORT`) overridable through a git-ignored `.env`. Note: PostgreSQL 18
  requires the data mount at `/var/lib/postgresql` (not
  `/var/lib/postgresql/data`); see the compose file comment. Verified: `docker
  compose config` passes, the service reaches `healthy`, and test data plus the
  `vector` extension survive a container restart through the named volume.

- **Redis Compose service (`S01-13`).** `infra/compose/docker-compose.yml` starts
  Redis 8 (Open Source) on the pinned image `redis:8.10.2` (latest stable `8.10.x`
  patch). Redis is a cache / short-lived coordination store, never a source of
  truth: workflow state stays in PostgreSQL, so losing Redis must not lose a run.
  It runs in the `core` profile alongside PostgreSQL, exposes a healthcheck
  (`redis-cli ping`), and persists local development data through an append-only
  file (AOF, `appendfsync everysec`) on the `agentflow-redis` named volume so it
  survives restarts. No auth is configured on the trusted local network;
  production (Kubernetes) must enable `requirepass` from a Secret, and the
  password is never hardcoded in app code. Verified: `docker compose ... up
  --profile core` brings the service to `healthy`, `redis-cli ping` returns
  `PONG`, and a set key survives a container restart through the AOF; `docker
  compose ... down` tears it down cleanly.

**- Kafka Compose service (S01-14).** `infra/compose/docker-compose.yml` starts a
single combined KRaft Kafka node (broker + controller) on the pinned image
`apache/kafka:4.3.1`, which is KRaft by default — no Zookeeper. It exposes an
`INTERNAL` listener (`0.0.0.0:9092`, inter-broker) and a `CONTROLLER` listener
(`:9093`, required by KRaft), advertises `INTERNAL://kafka:9092` so compose peers
resolve the broker by the `kafka` network alias, and sets the single-node quorum
voter to `1@localhost:9093`. It runs in the `messaging` profile with
`replication-factor`/`min.insync.replicas` at 1, a named volume
(`agentflow-kafka`) and a broker-level healthcheck (`kafka-broker-api-versions
--bootstrap-server localhost:9092`, which does not depend on compose DNS). KRaft
needs a cluster id to format its Raft log on first start; the service fails fast
unless `KAFKA_CLUSTER_ID` is provided (documented in `.env.example` and the
compose README). Verified: `docker compose --profile messaging up -d` brings the
service to `healthy` in ~10s; a producer (`acks=all`) publishes a message and a
consumer on the same network reads it back (`LOG-END-OFFSET=1`,
`Processed a total of 1 messages`).

- **Garage object storage service (`S01-15`).** `infra/compose/docker-compose.yml`
  now starts **Garage** (`dxflrs/garage:v2.4.1`, AGPL-3.0) — a free, open source,
  S3-compatible object store — instead of the upstream MinIO AIStor, whose
  pre-built images require a single-node SUBNET license to unlock S3. It runs in
  the `core` profile and auto-bootstraps a single-node cluster on first start
  (`--single-node` applies the layout, `--default-bucket` creates the `agentflow`
  bucket and a default S3 key from the environment); the layout, bucket and key
  persist across restarts. The S3 API is on `:3900` (configurable via
  `GARAGE_S3_API_PORT`). The RPC secret is injected via `GARAGE_RPC_SECRET` (not
  committed); the app's S3 client (`minio.access-key` / `minio.secret-key` in
  `.env`) must match the Garage root key. Verified: auto-bootstrap, an S3
  round-trip (list/put/get, payloads match by SHA-256) and S3 working again after
  a container restart with the same credentials. The trade-off is recorded in
  `docs/adr/s3-object-storage-garage.md`.

- **Compose profiles (S01-16).** `infra/compose/docker-compose.yml` now groups
  every service into six profiles — `core`, `messaging`, `identity`, `ai-local`,
  `observability`, `full` — so the minimum viable stack (`core`) starts without
  the rest. `full` reuses each service's definition by listing `full` in that
  service's `profiles` array (Compose cannot nest profiles). The observability
  profile runs `grafana/otel-lgtm` (the all-in-one OpenTelemetry Collector /
  Prometheus / Loki / Tempo / Grafana backend) instead of a five-container stack:
  the Loki exporter is absent from `otel/opentelemetry-collector-contrib` and
  `grafana/loki` exposes no OTLP ingest, so the all-in-one image keeps all three
  stores working (Grafana exposes all three as datasources). The image is pinned
  by digest; data persists on the `agentflow-observability` named volume; the
  Grafana admin password is local-only by default. Verified: `docker compose
  --profile X config` passes for all six profiles and the observability profile
  comes up `healthy` with all component endpoints responding.

- **Safe health endpoints (`S01-18`).** Spring Boot Actuator is wired into
  `apps/agentflow-app` (`spring-boot-starter-actuator`, BOM-managed, no pinned
  version) for minimal runtime diagnostics. Only `health` and `info` are exposed
  over HTTP and `liveness`/`readiness` are defined as dedicated health groups for
  the orchestrator; every other endpoint (`env`, `configprops`, `beans`,
  `mappings`, `loggers`, dumps) is outside the exposure allow-list, so it answers
  `404` anonymously. The secured security chain requires a bearer token for every management
  endpoint other than the health/info probes used by the orchestrator (S04-11). See
  `apps/agentflow-app/src/main/resources/application.yml`.

- **Standardized JSON logging (`S01-19`).** The app now emits structured JSON on
  stdout through
  `apps/agentflow-app/src/main/resources/logback-spring.xml`, satisfying the
  AGENTS.md requirement that logs carry `traceId`/`spanId` and `service`/`environment`
  and never leak secrets. Every event is rendered by
  `net.logstash.logback:logstash-logback-encoder:9.0` (the Jackson 3 / `tools.jackson`
  line — the only version compatible with the Boot 4.1.1 / Jackson 3.1.5 graph; the
  previously pinned `8.0` does not run on Jackson 3). The encoder writes the MDC keys
  (`service`/`environment`, and `traceId`/`spanId`/`correlationId` once S13 wires
  tracing), and a custom
  `com.example.agentflow.logging.SecretRedactingValueMasker` (registered through the
  encoder's `<valueMasker>`) redacts secret-like values before serialization —
  `Bearer`/`bearer` tokens, `api_key`/`token`/`password`/`secret` pairs and AWS access
  key IDs — so nothing reaches stdout, Loki, disk or the Actuator. A
  `com.example.agentflow.logging.LoggingContextProperties` bean seeds `service`/`environment`
  into the MDC on the main thread at startup. The `local` profile sets
  `com.example.agentflow` to `DEBUG` (see `application-local.yml`) while the default
  stays at `INFO`. Verified end-to-end: `LoggingJsonFormatTest` exercises the same
  encoder/decorator/masker as production and asserts parseable JSON carrying MDC and
  masked secrets, and a `bootRun` under `local` shows JSON with `service`/`environment`
  and `secret=***` in the output. Follow-up: `traceId`/`spanId` MDC fields are added
  when OTel is wired (S13).

- **Quickstart and smoke test (`S01-20`).** The root `README.md` gains a quickstart —
  prerequisites, starting/stopping the base stack, a ports table, environment
  variables and troubleshooting — so a developer without verbal instructions can
  start the stack. `scripts/smoke-local.sh` (S01-20) is the Sprint 1 smoke test:
  it starts a Compose profile (default `core`: PostgreSQL + pgvector, Redis,
  Garage), waits for every service to become `healthy` through its compose
  healthcheck, prints a per-service summary and tears the stack down, exiting
  non-zero if any service is not healthy. `messaging`/`full` need a one-time
  `KAFKA_CLUSTER_ID`, which the local `infra/compose/.env` provides. Verified:
  `docker compose config` passes for all six profiles and the smoke test passes
  for `core` and `full` (all services reach `healthy`).

- **System context diagram (`S02-02`).** `docs/architecture/system-context.md` is
  the new C4 Level 1 *system context* of AgentFlow. It shows every external actor
  and system — Client (the domain analyst via browser/CLI), Keycloak (IdP/OIDC),
  the cloud Model provider (OpenAI-compatible ChatModel), Garage/S3 object
  storage and allowlisted External tools — around the trusted AgentFlow boundary,
  plus the direction of trust for each flow and where secrets and documents
  cross it: Client input is untrusted and the tenant comes only from the verified
  Keycloak JWT; the Model is an untrusted planner; document payloads leave the
  DB as a tenant-scoped ref + `SHA-256` checksum; tool secrets come from secret
  management. The diagram is Mermaid inside Markdown (per
  `docs/AGENTS.md`), rendered with `flowchart` (a viewer-safe type), and
  syntax-validated with `mermaid.parse`. `docs/README.md` links to it from the
  architecture section.

- **Bounded-context module map (`S02-03`).** `docs/architecture/module-map.md` is
  the new C4 Level 2 of AgentFlow. It decomposes the modular monolith into its
  twelve bounded contexts (`identity`, `agentcatalog`, `workflowdefinition`,
  `workflowexecution`, `toolexecution`, `knowledgebase`, `ingestion`, `ai`,
  `usage`, `audit`, `messaging`, `observability`), records each module's
  responsibility, fixes the **single owner of every table** (anchored to the
  glossary's concept → owner table, §13) and the **single owner of every
  invariant** (tenant context, tenant scope, state machine, lease/fencing,
  allowlist, at-least-once delivery, budget, audit, error taxonomy, metric
  labels), and locks the **only allowed `module -> module` dependency edges**
  plus the execution `StepHandler` SPI that keeps the graph acyclic (`S02-09`).
  The table is the ground truth the architecture test checks and the authority
  for table ownership in migrations. `docs/README.md` links to it from the
  architecture section and its `⚠ planned (S02-03)` marker was removed.

- **Tenant-query indexes (`S03-07`).**
  `apps/agentflow-app/src/main/resources/migrations/V3_3_3__index_tenant_queries.sql`
  (version `3.3.3`, after `workflow_definition_versions` `3.3.2`) adds a single
  forward-only, idempotent (`IF NOT EXISTS`) index —
  `workflow_definitions_tenant_created_at_idx (tenant_id, created_at)` — on the
  `workflowdefinition`-owned `workflow_definitions` table. It is the *only*
  secondary index in the minimal `S03` set: every other real lookup path is
  already served by a primary key or a unique-constraint index
  (glossary §4, plan §8.1). Verified with `EXPLAIN (ANALYZE, BUFFERS)` on
  representative data (`pgvector/pgvector:0.8.6-pg18`, the same image as
  Compose/Testcontainers): without the index the tenant-list
  `WHERE tenant_id = ? ORDER BY created_at LIMIT ?` is a `Seq Scan` (filter on
  `tenant_id`) followed by an explicit `Sort` (cost ≈ 63.7, ~0.31 ms); with it
  the planner does an ordered `Index Scan Backward` with no Sort node
  (~7× fewer buffers), and the 20 returned rows are confirmed strictly
  descending by `created_at`. `workflow_definition_versions` needs no separate
  index — its `unique (workflow_id, version)` index has `workflow_id` as its
  leading column, so it backs both the point lookup and the version-ordered
  list for free. The expected query set and this before/after are recorded in
  `docs/architecture/database-conventions.md` (the "Verified index set"
  subsection, `S03-07`). Applies as the one controlled step
  (`./gradlew flywayMigrate`, never on startup — `ADR-008`).

- **Single persistence approach — Spring Data JDBC (`S03-08`).** ADR-009 fixes
  `spring-boot-starter-jdbc` as the one persistence approach for the whole
  monolith (explicit, tenant-filtered SQL through `JdbcTemplate`, conditional
  update → `409`, lease/fencing, JSONB/pgvector). Wiring the starter in exposed a
  boot-time gotcha: Spring Boot's DataSource auto-configuration does **not**
  no-op when no `spring.datasource.*` is set — it eagerly builds a `HikariDataSource`
  and fails with *"Failed to determine a suitable driver class"*, which broke the
  "boots without a database" guarantee (`S01-02`) for every non-`local` profile. The
  fix is to exclude `DataSourceAutoConfiguration` in the base
  (`application.yml`, `spring.autoconfigure.exclude`) so the skeleton still boots
  without a database, and re-enable it in the `local` profile
  (`spring.autoconfigure.exclude: []`) where the `DataSource` is wired from
  `spring.datasource.*` supplied by the environment (git-ignored `.env`) — nothing
  here points at a database. The first owning-module store is the
  `workflowdefinition` layer: an immutable `WorkflowDefinition` record, a
  `WorkflowDefinitionMapper`, and a tenant-scoped `WorkflowDefinitionRepository`
  whose reads are always `(tenant_id, id)`-filtered and whose conditional update
  surfaces a zero-rows change as a conflict. Verified: `./gradlew check` green
  (unit + architecture + formatting), and both directions at runtime — `base`
  boots without a database; `local` boots with a real `DataSource` (HikariCP
  connects lazily, so no live Postgres is needed for the smoke boot) and fails
  fast with the driver-class error when the env datasource is absent.

### Fixed

- **`bootRun` resolves `runtimeClasspath` (dependency-lock gap closed, S04-14 follow-up).** The
  `apps/agentflow-app/gradle.lockfile` pinned the security stack (`nimbus-jose-jwt`, the
  `spring-security-*` suite and the `spring-boot-security` /
  `spring-boot-starter-oauth2-resource-server` starters) only for `compileClasspath` /
  `testCompileClasspath` / `testRuntimeClasspath`. Because the app's lock resolution hard-fails
  when a resolved version is missing from the lock, `./gradlew bootRun` aborted with
  `"not part of the dependency lock state"` even though the build and the `check` gate passed —
  `check` never resolves `runtimeClasspath`, so the gap was invisible there. Regenerated the lock
  with `./gradlew generateLockFiles --write-locks`; the diff adds `runtimeClasspath` to exactly
  those 12 entries and is idempotent (a second regeneration produces no diff). Verified: the app
  boots with `-Dspring.profiles.active=local`, `Tomcat started on port 8080`, secured endpoint
  returns `401` without a token and `403` with a valid `agentflow-service` m2m token (passes the
  Resource Server signature/issuer/audience checks, denied by the AccessMatrix for the missing
  `workflow:read` role) — matching the runbook's documented results.

### Changed

- No breaking or behavior changes yet. This skeleton is established in
  `S01-22`; add entries here for notable changes that modify existing behavior
  (see `CONTRIBUTING.md`).

### Fixed

- **`jwtDecoder` no longer uses the deprecated Nimbus `RemoteJWKSet` (S04-03).** The
  original S04-03 implementation built the standard `NimbusJwtDecoder` with the deprecated
  Nimbus `RemoteJWKSet` (class-level `@Deprecated`), which triggered `[deprecation]`
  warnings under `-Xlint:deprecation` and was scheduled for removal. The decoder is now
  built with the non-deprecated `NimbusJwtDecoder.withJwkSource(...)` API: a small
  `JWKSource<SecurityContext>` resolves the issuer's OIDC discovery document and fetches
  `jwk_set_uri` only on the first `select(...)` (never at startup), so the context still
  boots without a reachable Keycloak — the lazy-OIDC property is preserved — and the bean
  is still a `NimbusJwtDecoder`. `RemoteJWKSet` is gone from the codebase (zero
  `[deprecation]` warnings remain from this file). Verification: `./gradlew :apps:agentflow-app:check` is green
  (unit + architecture gates + formatting + OpenAPI); `RemoteJWKSet` produces no
  deprecation warnings; and the context-startup tests on the `default`/`kubernetes`/`local`
  profiles (which boot without Keycloak) pass — `productionJwtDecoderBuildsWithLocalIssuer`
  additionally asserts the production bean is a `NimbusJwtDecoder`. The `JsonNode.asText()`
  deprecations noted here were removed later in the S04-03 follow-up (see the entry above). The
  key lookup itself (JWK set from `jwk_set_uri`, with a `jwks_uri` fallback for Keycloak) is a
  later fix — see "JWT decoder resolves the JWK set via `jwks_uri`" below.
- **JWT decoder resolves the JWK set via `jwks_uri` when discovery omits `jwk_set_uri`.** The
  `default`/`local`/`kubernetes` secured chain builds the `NimbusJwtDecoder` JWK source from the
  issuer's OIDC discovery document (`SecurityConfiguration.issuerJwkSource` →
  `jwkSetFromIssuer`), reading the JWK set URL from the `jwk_set_uri` field. Keycloak 26.8.1 —
  the realm used locally and Keycloak's own `master` realm — exposes the JWK set under the legacy
  `jwks_uri` discovery field and not the OIDC-standard `jwk_set_uri`, so `getURI(metadata,
  "jwk_set_uri")` returned `null` and the decoder rejected every token with `401 invalid_token`
  *before* the issuer and audience checks ever ran. That is what produced the opaque local `401`
  on `/api/v1` despite a well-formed `aud` claim: the failure was the key-resolution naming gap,
  not the audience. The decoder now reads `jwk_set_uri` first and falls back to `jwks_uri` (both
  point at the same `/protocol/openid-connect/certs` endpoint) so an issuer is never rejected for a
  field-name difference; if discovery exposes neither, resolution fails with a clear message. No
  validation is weakened — the signature, issuer, audience and expiry checks are unchanged, only
  the key lookup is now tolerant of both conventions. Verified locally: the secured chain on the
  `default` profile now verifies a real `agentflow-service` Client Credentials token (whose
  `aud=agentflow-api`) past the security layer to a `403` (missing `workflow:read`), and still
  rejects an unauthenticated request with `401`. The `jwks_uri` fallback is a regression unit test
  (`SecurityConfigurationJwkResolutionTest`), which drives `jwkSetFromIssuer` against a local HTTP
  server covering the `jwk_set_uri` path, the `jwks_uri` fallback, and the neither-present case.
- **No deprecated `JsonNode.asText()` in the build (S04-03 follow-up).** The standard
  Jackson 3.1.5 `JsonNode.asText()` is a deprecated alias — its Javadoc says `@deprecated
  Use asString() instead`, and `asText()` is implemented as `return asString()` — which left
  18 `[deprecation]` warnings under `-Xlint:deprecation`: 13 in `GlobalExceptionHandlerTest`
  and 5 in `OpenApiCompatibility`. All calls were replaced with the non-deprecated
  `asString()` (the method `asText()` already delegates to), so there is no behavior change:
  the RFC 9457 envelope assertions and the breaking/compatible classification read the same
  string values. Verification: a fresh `compileJava`/`compileTestJava` under `-Xlint:deprecation`
  reports zero `[deprecation]` warnings, `GlobalExceptionHandlerTest` passes 5/5, and
  `OpenApiContractCheckTest` passes — no semantic change to either.
- **Typed config binds under the default profile (`S01-17`).** The `agentflow.app.*`
  configuration was declared with a nested `agentflow.app.name` key, but the Java
  model is `@ConfigurationProperties(prefix = "agentflow")` with a flat `appName`
  field, which binds from `agentflow.app-name` / `agentflow.appName` only. The nested
  key never bound, so `app.name` came back as `null` under the default profile and the
  Spring context failed to start. All `application*.yml` profiles now use the flat
  `agentflow.app-name` key; the `@NotBlank` validation message and Javadoc were
  corrected to match. The startup-failure test now asserts on the real
  `ConfigurationPropertiesBindException` reported by `ApplicationContextRunner` (a
  missing required value fails context startup via `@Validated` binding), and
  `./gradlew clean check` is green.
- **`apps/agentflow-app` test task runs (S01-07).** The Boot 4.1.1 BOM manages JUnit
  to the 6.0.x line and `spring-boot-test` is compiled against the JUnit Platform
  6.0 `Store` API, so forcing the JUnit 5 line broke Spring context startup
  (`NoSuchMethodError`) and the platform discovered zero tests. We keep the
  BOM-managed JUnit 6.0.3 and add `junit-platform-launcher` (also BOM-managed) to
  the test runtime classpath. The `test` task now compiles and runs the
  `AgentflowApplicationTests` context-load test. `failOnNoDiscoveredTests = true`
  makes a future zero-test discovery fail the build instead of passing silently.

### Security

- **Local auth bypass removed (S04-10).** The permissive {@code test}-profile
  {@code SecurityFilterChain} — the temporary local mock-auth that let the many controller tests
  run without a verified JWT — is no longer part of the production artifact. It moved to a
  test-only config
  ({@code apps/agentflow-app/src/test/java/com/example/agentflow/security/TestOnlySecurityConfiguration.java},
  {@code @Profile("test")}), so the permissive chain can never ship in the jar. On the {@code test}
  profile the secured chain ({@code @Profile("!test")}) is absent, so exactly one chain is
  registered; on every other profile the secured chain is the only one. Even a misconfigured
  deployment that activates the {@code test} profile can no longer open the API: the
  production/kubernetes profile never starts with a permissive principal (the S04-10 acceptance
  criterion). The {@code jwtDecoder} / {@code jwtAuthenticationConverter} beans and the {@code
  secured-test} profile (the in-memory-key secured chain) are unchanged; {@code ./gradlew
  :apps:agentflow-app:test} is green — the {@code test}-profile suites ({@code
  ConfigurationTestProfileTest}, {@code HealthEndpointsTest}, {@code
  GlobalExceptionHandlerTest}) still boot the permissive chain, and {@code
  SecurityFilterChainTest} still runs the secured chain on {@code secured-test}. The
  {@code ${agentflow.local.tenant-id}} local default (S03-13/S03-14) is a *separate*
  tenant-isolation bypass that this sprint deliberately did not remove (threading the tenant from
  the verified context needs a new `workflowdefinition` → `identity` edge, an ADR) and is tracked
  as a follow-up.
- Security-relevant changes (for example the repo
  secret hygiene, `S01-11`) are recorded under `Added`; dedicated security
  entries use this section going forward (see `CONTRIBUTING.md`).
