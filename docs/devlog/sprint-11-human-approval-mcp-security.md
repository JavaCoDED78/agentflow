# Sprint 11 — human approval, MCP and security of agent actions

## Contents

- [S11-01](#s11-01-tool-risk-classification-a-closed-deny-by-default-vocabulary-owned-by-the-catalogue) — tool risk classification policy (S11-01)
- [S11-02](#s11-02-durable-approvals-schema) — durable approvals schema (S11-02)
- [S11-03](#s11-03-durable-approval-pause) — the durable approval pause (WAITING_FOR_APPROVAL): the worker parks a run at a HUMAN_APPROVAL boundary and releases the worker lease (S11-03)
- [S11-04](#s11-04-the-approve-endpoint) — the approve endpoint: the human decision on a HUMAN_APPROVAL pause, recorded atomically with its audit row (S11-04)
- [S11-05](#s11-05--reject-and-the-expiry-lifecycle-the-full-approval-lifecycle) — reject and the expiry lifecycle: the resume/refusal completion, the two outbox events, and the poll that never steals a paused run (S11-05)
- [S11-06](#s11-06--the-payload-bound-approval-the-fingerprint-re-validated-at-the-decision) — the payload-bound approval: the canonical fingerprint of the exact arguments re-validated at the decision (S11-06)
- [S11-07](#s11-07--mcp-transport-streamable-http-verified-against-the-stable-spring-ai-20x-line) — MCP transport: Streamable-HTTP on the stable Spring AI 2.0.x line, with the client/server compatibility matrix (S11-07)
- [S11-08](#s11-08--the-isolated-mcp-demo-server-agentflow-mcp-lab) — the isolated MCP demo server: agentflow-mcp-lab, a separate artifact with one read-only fixture tool and no path to the business database (S11-08)
- [S11-09](#s11-09--the-mcp-client-adapter-an-allowlist-the-model-cannot-leave) — the MCP client adapter: an allowlist the model cannot leave (S11-09)

---

## S11-09 — the MCP client adapter: an allowlist the model cannot leave

**Task (P1):** implement the MCP client adapter that calls configured MCP servers only for
tools in the allowlist; the model must not be able to call targets outside the allowlist
configuration.

**Deliverable:** `toolexecution.mcp` — `McpServerPolicy`, `McpAllowlist`,
`McpClientAdapter`, `McpAccessDeniedException` — plus `config.McpClientConfiguration` and the
`agentflow.mcp.*` property group. The main app gains the BOM-managed
`spring-ai-starter-mcp-client` dependency.

### What changed

- **The two-sided seam.** A server is reachable only when *both* sides name it: the Spring AI
  connection map (`spring.ai.mcp.client.streamable-http.connections.<name>.url`) owns
  transport, the new `agentflow.mcp.servers.<name>` group owns authorization — the exact tool
  names, the per-call `timeout-seconds` (default 10) and the `max-response-bytes` result
  bound (default 64 KiB; an MCP server is untrusted input, so its answer is bounded like an
  upload). An entry with an empty `tools` list fails startup (`@NotEmpty`); the map defaults
  to empty, so the unconfigured app has no MCP surface at all and the skeleton boots
  unchanged (S01-02 — verified by the full-boot `AgentflowApplicationTests` with the starter
  on the classpath).
- **Fail-closed invocation.** `McpClientAdapter.invoke(server, tool, argumentsJson)` refuses
  in order, before any avoidable I/O: unknown server (`UNKNOWN_SERVER` — not allowlisted, or
  allowlisted but not connected), tool outside the allowlist (`TOOL_NOT_ALLOWLISTED` —
  refused without even listing capabilities), tool the server does not advertise
  (`TOOL_NOT_ADVERTISED` — the catalogue is fetched once per server and cached; only the
  intersection of operator policy and server advertisement is callable). Arguments must be a
  JSON object and validate against the server's advertised input schema through the existing
  `SchemaValidator`/`JsonSchema` machinery (a tool advertising no schema accepts any JSON
  object — the MCP contract). The call runs on the shared tool-call pool under the per-server
  timeout via `ToolCallTimeout` (ambiguous outcome + released thread, invariant 6); a result
  above `max-response-bytes` is refused (`RESPONSE_LIMIT_EXCEEDED`) and an `isError` result
  surfaces as `ToolExecutionError`.
- **No target from the untrusted side.** The planner only *proposes* a `(server, tool)` pair
  (ADR-006, invariant 7); the adapter decides from the operator's configuration alone. There
  is no path that takes a server or tool name from the model, a document, or a server
  response and calls it — that is the S11-09 acceptance, tested deny-path first.
- **Wiring without new edges.** `McpClientConfiguration` builds the allowlist bean, a
  `McpClientCustomizer` names each connection's client info after its connection name (the
  adapter's deterministic client → policy mapping) and applies the per-server transport
  request timeout, and the adapter bean maps the starter-managed `McpSyncClient`s by
  connection name. Everything is DataSource-free and Kafka-free — safe in every profile.
  The adapter is not yet reachable from a workflow: exposing allowlisted MCP tools as
  `ToolDescriptor`s and routing `TOOL_CALL` steps to it is **S11-10**, which also owns the
  per-connection URL SSRF guard.

### Why

- **Policy separate from transport.** The connection map alone would be an allowlist of
  *hosts*; the planner must not turn "this host is reachable" into "every tool on this host
  is callable". Naming the tools in `agentflow.mcp.*` keeps the authorization decision in the
  operator's hands and versionable, while URLs stay where the starter expects them.
- **Deny is the default state, not a flag.** An empty map is a valid allowlist (no MCP
  surface), and every refusal is a closed `Reason` vocabulary the future `TOOL_CALL` handler
  can classify without parsing messages — the same bounded-outcome style as the rest of
  `toolexecution`.

### Verification

```bash
./gradlew :apps:agentflow-app:test --tests '*toolexecution.mcp*' --tests '*McpClientConfigurationTest' --rerun-tasks
./gradlew spotlessApply check --rerun-tasks
```

`BUILD SUCCESSFUL`, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`, fresh reports
in this session): `McpServerPolicyTest` **6** (canonical-constructor validation: blank
server, empty/blank tool names, non-positive timeout and response bound), `McpAllowlistTest`
**5** (deny-by-default resolution, empty allowlist valid, key/policy mismatch refused),
`McpClientAdapterTest` **16** — deny paths first: unknown server refused with
`verifyNoInteractions`, unallowlisted tool refused without listing, unadvertised tool
refused without calling, allowlisted-but-unconnected refused, malformed/non-object JSON
refused before the call, schema-violating arguments refused before the call; then the happy
path with the exact request capture, the once-per-server capability cache, the per-server
timeout (ambiguous outcome), the response-size bound, `isError` classification, structured
content rendered as JSON, and the reachability seam; `McpClientConfigurationTest` **6**
(binding with defaults and explicit bounds, empty `tools` failing validation, the client →
connection-name mapping with generic type preserved, the customizer tolerating connections
outside the allowlist). The full `./gradlew check` gate — unit tests of both artifacts +
architecture boundaries + formatting (`spotless`) + the wrapper-integrity verify — green,
including the full-boot `AgentflowApplicationTests` (S01-02 with the MCP starter on the
classpath) and `ArchitectureBoundaryTest` (no new module edge — `toolexecution` owns the
adapter; `config` composes it).

### Docs

- [`docs/ai/mcp.md`](../ai/mcp.md) — the client section is now the real adapter: the
  two-sided seam, the property contract, the six-step invocation path, the test map.
- `docs/adr/ADR-019-mcp-streamable-http-transport.md` — implementation note (the allowlist
  lands as specified).
- The devlog (this file) and `CHANGELOG.md`.

---

## S11-08 — the isolated MCP demo server: agentflow-mcp-lab

**Task (P1):** create a separate process/service with one or two safe read-only tools; give it no direct DB credentials from the main application.

**Deliverable:** `apps/agentflow-mcp-lab` — a separate Spring Boot artifact running the Streamable-HTTP MCP server from ADR-019, exposing one read-only demo tool, `ticket_lookup`.

### What changed

- **A new Gradle subproject and Spring Boot artifact** (`apps/agentflow-mcp-lab/`, main class `com.example.agentflow.mcplab.McpLabApplication`, registered in `settings.gradle.kts`). It is deliberately not a package of `agentflow-app`: the lab is a separate deployable with its own lifecycle, which is exactly the case the repository rules allow a separate module for.
- **The ADR-019 coordinates, no more, no less:** `spring-boot-starter-web` (Servlet stack) + `spring-ai-starter-mcp-server-webmvc` (BOM-managed 2.0.1), `spring.ai.mcp.server.protocol=STREAMABLE`, endpoint `/mcp`, port 8081. The catalog gains one entry (`spring-ai-starter-mcp-server-webmvc`); nothing is pinned inline; `generateLockFiles --write-locks` adds the lab's `gradle.lockfile`.
- **One safe, read-only tool — isolation by absence.** `LabTicketLookupService` answers `ticket_lookup` from a fixed in-memory fixture (`TKT-1001` open $49.99 / `TKT-1002` on hold $129.00 / `TKT-1003` closed $42.50 — the same fictitious story as the monolith's in-process `ticket.lookup`, S08-07, but self-contained: the lab shares no code and no state with `agentflow-app`). There is no JDBC starter, no Flyway, no PostgreSQL driver on the lab classpath, so the business database is unreachable from the process at all — a credential cannot leak in through autoconfiguration because there is nothing to autoconfigure it into. An unknown id is a first-class `NOT_FOUND` answer in one stable schema, never an exception across the wire; the MCP tool name is `ticket_lookup` because the MCP specification restricts tool names to letters, digits, `_` and `-` — the in-process dot name cannot cross the wire.

### Why

- **Separate artifact over a profile of the monolith.** The S11-08 acceptance is isolation: the lab must run independently and never see the business database. A profile of `agentflow-app` would share the artifact's classpath (the PostgreSQL driver, Flyway) and its configuration surface — isolation would rest on convention. A separate artifact makes it structural: the forbidden things are not present.
- **One tool, deliberately minimal.** The plan allows one or two; one deterministic read-only tool demonstrates the full MCP surface (advertise → call → typed answer) without widening the attack surface the allowlist (S11-09) and SSRF guard (S11-10) will police.

### Verification

```bash
./gradlew :apps:agentflow-mcp-lab:test --rerun-tasks
./gradlew spotlessApply check --rerun-tasks
```

`BUILD SUCCESSFUL`, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`, fresh reports in this session): `LabTicketLookupServiceTest` **6** (exact fixture values for every ticket, `NOT_FOUND` as a first-class answer, exact/case-sensitive matching, the null/blank contract guards, repeated-lookup stability), `McpLabApplicationTest` **2** (the context boots and advertises exactly one tool named `ticket_lookup`; `getBeanNamesForType(DataSource.class)` is empty — the no-datasource isolation acceptance), `McpLabEndpointTest` **1** (boots on a random port and performs a real MCP `initialize` handshake over Streamable-HTTP with the JDK `HttpClient` — `200` and the body carries the server identity `agentflow-mcp-lab`). The full `./gradlew check` gate — unit tests of both artifacts + formatting (`spotless`) + the wrapper-integrity verify — green.

### Documentation

- `docs/ai/mcp.md` — the demo-server section is now the real module: actual build file, application.yml, run/test commands, the `ticket_lookup` naming note.
- `apps/README.md` and the root `AGENTS.md` repository layout — the lab's PLANNED marker replaced with the implemented entry.
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`; this devlog section (newest-first, above S11-07).

### Risks / follow-up

- No Compose service or Helm chart for the lab yet — it runs via `bootRun`; packaging/deployment lands where the demo needs it (not required by the S11-08 acceptance, which the independent-boot test proves).
- S11-09 wires the monolith's MCP client to this server through the connection allowlist; S11-10 adds the SSRF guard over the connection URLs.

---

## S11-07 — MCP transport: Streamable-HTTP, verified against the stable Spring AI 2.0.x line

**Task (P1):** verify the current MCP API/transport against the stable Spring AI 2.0.x documentation; choose one transport for the demo; fix protocol/client/server compatibility.

**Deliverable:** `ADR-019` (the transport and compatibility decision) and [`docs/ai/mcp.md`](../ai/mcp.md) — the verified version matrix and the configuration the demo server (S11-08) and the client (S11-09) build on.

### What changed

- **Nothing in code — this is the research-and-fix task Sprint 11's MCP work stands on.** Its output is a decision and a configuration contract, both verified against published artifacts rather than tutorials.
- **One transport: Streamable-HTTP.** The demo server (`agentflow-mcp-lab`, S11-08) gets `spring-ai-starter-mcp-server-webmvc` with `spring.ai.mcp.server.protocol=STREAMABLE`; the main app (S11-09) gets `spring-ai-starter-mcp-client` (JDK `HttpClient`-based) and is a client only — `spring.ai.mcp.server.enabled=false`, no server starter on its classpath.
- **Verified facts, dated 2026-11-15.** Spring AI 2.0.1 is the stable line and deprecates the SSE MCP server since 2.0.0 ("use STREAMABLE instead"); `spring-ai-mcp:2.0.1` depends on `io.modelcontextprotocol.sdk:mcp:2.0.0` (read from the published POM), and SDK 2.0.0 tracks MCP spec revision 2025-11-25 and deprecates SSE in favor of Streamable HTTP; the `mcp-spring-webmvc`/`mcp-spring-webflux` artifacts moved from the `io.modelcontextprotocol.sdk` group to `org.springframework.ai` in Spring AI 2.0; the `spring-ai-bom:2.0.1` manages every MCP starter at the BOM version, so no inline pins exist or are needed.
- **Compatibility is derived, not configured.** Both ends ride the same BOM → `spring-ai-mcp` → SDK chain, so a connection always pairs identical SDK versions and one protocol revision. Neither app pins or imports `io.modelcontextprotocol.*` directly — the ADR-019 alternatives table records why (the 2.0 group-id relocation makes direct SDK pinning a maintenance hazard, and it would bypass BOM discipline, S01-06).
- **The connection map is the allowlist seam.** `spring.ai.mcp.client.streamable-http.connections` in `docs/ai/mcp.md` names exactly one host — the lab — and S11-09 refuses anything outside it.

### Why

- The MCP ecosystem moved twice in a year: SSE → Streamable HTTP (spec 2025-03-26), then SDK 2.0's relocation of the Spring transports. Sprint 11's acceptance — *the decision must not rest on a deprecated API or an experimental transport* — eliminates SSE (deprecated in both Spring AI 2.0.0 and SDK 2.0.0), the WebFlux starters (the stack forbids WebFlux), the SDK 2.2.x "modern" 2026-07-28 preview (its own changelog calls the API unstable until 3.0), and STDIO (the lab is a network service by design). Streamable-HTTP is the only option left standing — and it is the documented default.
- Fixing this *before* S11-08/S11-09 is the cheap seam: a transport change after the lab and the client adapter exist would rewrite both instead of a document.

### Verification

Documentation-only change — the verification is link integrity plus reproducible artifact checks, run and inspected here:

```bash
# BOM manages every MCP starter at the BOM version
curl -s https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom \
  | grep -o 'spring-ai-starter-mcp-[a-z-]*' | sort -u
# -> spring-ai-starter-mcp-client / -client-webflux / -server / -server-webflux / -server-webmvc

# the exact SDK chain the BOM resolves
curl -s https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-mcp/2.0.1/spring-ai-mcp-2.0.1.pom \
  | grep -A3 'modelcontextprotocol'
# -> io.modelcontextprotocol.sdk:mcp:2.0.0 (compile)
```

Both commands run against Maven Central on 2026-11-15 with the recorded output (also printed in [`docs/ai/mcp.md`](../ai/mcp.md)); the Spring AI 2.0.1 reference (MCP server boot starter page — "SSE WebMVC deprecated since 2.0.0, use STREAMABLE instead") and the MCP Java SDK changelog (2.0.0 "Streamable HTTP first: SSE transports deprecated") carry the deprecation facts. `git diff --check` clean; no build gate applies to a docs-only change (none claimed).

### Documentation

- [`docs/adr/ADR-019-mcp-streamable-http-transport.md`](../adr/ADR-019-mcp-streamable-http-transport.md) — new, `Accepted`, registered in the ADR register.
- [`docs/ai/mcp.md`](../ai/mcp.md) — new MCP integration & configuration guide (version matrix, server/client coordinates, allowlist seam, verification commands).
- `docs/README.md` (the ADR tree through `ADR-019` and the `ai/` entry), `docs/adr/README.md` (register row), `docs/ai/README.md` (Docs list).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`; this devlog section (newest-first, above S11-06).

### Risks / follow-up

- The Spring AI docs recommend the WebFlux client for production deployments; ADR-019 accepts the JDK-HttpClient client as the deliberate cost of the no-WebFlux stack rule, at demo scale.
- A Spring AI version bump must re-verify this ADR's facts (SSE deprecation status, SDK line, group ids) as part of the bump's compatibility check — recorded as an ADR-019 follow-up.
- S11-08 builds the lab on these coordinates; S11-09 wires the client behind the allowlist; S11-10 adds the SSRF guard for the connection URLs.

---

## S11-06 — the payload-bound approval: the fingerprint re-validated at the decision

**Task (P0):** bind an approval to an immutable payload — compute the fingerprint of the exact arguments / tool / version; re-check the fingerprint at execution; when the parameters change, require a new approval.

**Deliverable:** the payload binding — a canonical, deterministic fingerprint over the exact gated config, stored on the `approvals` row at the pause and re-validated against the run's pinned graph at the decision, so an approved set of arguments can never execute a different one.

### What changed

- **One canonical fingerprint algorithm.** `workflowexecution.domain.ApprovalPayloadFingerprint` — a pure, DB-free component hashing the lowercase-hex SHA-256 of the canonical JSON `{"step": …, "config": …}`: map keys sorted lexicographically at every depth, fixed RFC 8259 string escaping, plain-JSON value types only (null / boolean / string / number / object / array — anything else is a contract `IllegalArgumentException`). It replaces the S11-03 ad-hoc `key=value` concatenation, which hashed `Map.toString()` for nested argument maps — an iteration order that is unspecified, so two logically identical argument sets could digest differently. The worker's `pauseForApproval` now pins this digest; a hand-rolled canonicalizer (rather than a Jackson feature flag) keeps the algorithm byte-stable across library bumps — a fingerprint must never drift.
- **The binding read back.** `ApprovalRow` gains `payloadFingerprint` (`ApprovalRepository`'s read column set and row mapper carry `payload_fingerprint` — the insert path always wrote it) so the decision path can see what the request was pinned to.
- **The re-validation at the decision — the only seam where approve and execute are one atomic step.** `ApprovalService#approve` gains the S11-06 guard after the expiry/step guards: it loads the run's pinned workflow version through the existing `workflowexecution -> workflowdefinition` public edge (`WorkflowRunRepository#findRun` + `WorkflowVersionService#loadGraph` — the same pair the worker loads graphs through, no new compile edge), recomputes the digest for the gated step and compares it with the stored one. A mismatch, a gated step missing from the pinned graph, or an unverifiable (missing) graph refuses the decision with `409` and writes nothing — no decision row, no audit row, no run/step transition, no outbox event. The run stays parked in `WAITING_FOR_APPROVAL`: the stale request must be rejected or expired and a new approval requested. Reject and expiry carry no such check on purpose — they refuse the side effect, so a drifted binding creates no risk there, and an unguarded reject is how an operator retires a stale request.
- **The two halves pinned to one algorithm.** `WorkflowWorkerTest`'s restart acceptance captures the fingerprint the pause stores and asserts it equals `ApprovalPayloadFingerprint.compute(...)` — the digest the worker pins and the digest the decision re-validates cannot drift apart.
- **The contract says so.** `ApprovalController`'s `409` description and `@Operation` text document the payload-fingerprint case; `contracts/openapi/openapi.json` regenerated.

### Why

- **The S11-05 design decision stands.** The human decision is the gate step's whole execution: there is no later handler invocation at which a second check could run. The decision *is* the moment the approved argument set is authorized to execute, which makes it the right — and only — seam for the re-check. Reversing the instant-`SUCCEEDED` completion to re-drive the gate would have reopened the S11-05 re-pause races for no runtime gain: the pinned graph is immutable (invariant 13), so within one run the payload cannot legitimately change between pause and decision.
- **What the guard actually catches** is the illegitimate change: a digest stored under an older algorithm (the very refinement this task lands), a corrupted or tampered row, a run pinned to a graph that no longer holds the gated step, or a future dynamic-payload resolution — in every case the fail-closed answer is the same: no decision, no side effect, a human-visible `409`.
- **A digest, never the payload (invariant 8).** The arguments stay inside the pinned graph; only the hash rides the `approvals` row, and the `ApprovalDecisionDto` still carries no fingerprint preimage.

### Verification

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.workflowexecution.domain.ApprovalPayloadFingerprintTest' \
  --tests 'com.example.agentflow.workflowexecution.application.ApprovalServiceTest' \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalRepositoryTest' \
  --tests 'com.example.agentflow.workflowexecution.application.WorkflowWorkerTest' \
  --rerun-tasks
```

`BUILD SUCCESSFUL`, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`): `ApprovalPayloadFingerprintTest` **11** (insertion-order stability including nested argument maps, tamper sensitivity, the exact canonical-JSON digest, string escaping, list order vs. sorted map keys, number canonicalization, the contract guards), `ApprovalServiceTest` **30** (the S11-04/S11-05 suite plus: the approve path consults the pinned graph before deciding; changed gated parameters → `409` with no decision/audit/transition/outbox writes; the gated step gone from the pinned graph → `409`; a missing pinned graph → `409`), `ApprovalRepositoryTest` **13** (the read model now carries `payload_fingerprint`), `WorkflowWorkerTest` **15** (the pause pins exactly the digest the component computes). `:apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL` — unit + architecture gates (`ArchitectureBoundaryTest` `tests="1"`, `PackageDependencyGraphTest` `tests="4"`, `NoCyclicModuleDependenciesTest` `tests="1"` — no new compile edge, the `workflowexecution -> workflowdefinition` arrow already existed) + no-cycles + formatting (`spotless`) + the OpenAPI contract gate green against the regenerated `contracts/openapi/openapi.json`.

Integration on `pgvector/pgvector:0.8.6-pg18`: `ApprovalDecisionWriteIT` re-run green — the stored `payload_fingerprint` reads back verbatim on the decided row.

### Documentation

- `contracts/openapi/openapi.json` — regenerated (the `409` wording and the operation description now name the payload-fingerprint re-validation).
- `docs/architecture/module-api.md` — the `ApprovalService` entry: the payload re-validation replaces the "lands in S11-06" forward reference.
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`; this devlog section (newest-first, above S11-05).
- The S11-04 / S11-05 follow-up notes that deferred the binding to S11-06 now point here.

### Risks / follow-up

- An approval created before this change carries the old-format digest, so the first decision after the deploy re-validates against the new algorithm and can `409` on a drifted binding — the intended fail-closed behaviour for an unverifiable binding; the stale request is rejected and a new approval requested. No persistent data exists outside dev databases.
- The mismatch `409` parks the run until the stale request is rejected/expired (the partial unique index allows exactly one `PENDING` per step) — a deliberate fail-safe for an invariant-violation path, visible in the API and the audit trail.
- The reject/expiry paths intentionally re-validate nothing (see Why); if a future task makes gate payloads dynamic (resolved at execution), the check seam documented here is the place to extend.

---

## S11-05 — reject and the expiry lifecycle: the full approval lifecycle

**The decision now completes the run.** S11-04 recorded the decision and parked the outcome; S11-05 is the second half of every decision, applied in the same transaction through the single transition service (invariant 4), plus the expiry lifecycle that fails what outlived its deadline. A rejected/expired request never launches a side effect, and the continuation/refusal is published only through the outbox.

### What changed

- **Reject.** `ApprovalService#reject` mirrors `approve` guard for guard (capability at the edge, tenant-scoped lookups → `404`, only a `PENDING` unexpired approval whose step is still `WAITING_FOR_APPROVAL` → `409`, the guarded conditional write, the atomic audit append, both idempotent replay mechanisms — state- and key-based, including the lost-race re-read and the duplicate-key race). The completion differs by design: `refuseSideEffect` moves the run and the gated step `WAITING_FOR_APPROVAL -> FAILED` (`run.rejected`) without the gated handler ever executing, and publishes `workflow.run.rejected.v1` (topic `agentflow.workflow.run-rejected.v1`, payload `cause: "REJECTED"`). The endpoint accepts `{"decision":"reject"}` through the same `POST /api/v1/runs/{runId}/approvals/{approvalId}`; any other decision is a `400` at the edge. New audit verb `REJECT` (`AuditAction`).
- **Approve completion (the resume).** `resumeAfterApproval` — `WAITING_FOR_APPROVAL -> RUNNING` (`run.approved`), the gated step back to `RUNNING` and immediately `SUCCEEDED` (the human decision is the gate step's whole execution, so the step can never re-pause and recreate a second `PENDING` approval on the same step), and the continuation `workflow.run.resumed.v1` (topic `agentflow.workflow.run-resumed.v1`) through the transactional outbox — the only publication channel (invariant 3).
- **The shared decision write.** `ApprovalRepository#recordApprove` / `#recordReject` share `recordDecision` — one guarded `UPDATE` (`status = 'PENDING'` + optimistic-lock `version`, zero rows → `ConflictException`), the status a bound internal literal, never caller input; the status vocabulary stays closed.
- **Expiry.** `ApprovalRepository#expirePending` — the guarded `EXPIRED` write with no human actor: only `decision_at` marks the observed deadline, so an expired row can never be read back as a human decision. `ApprovalService#expireOverdue` — the sweep's transactional entry point: the guarded write (a lost race against a human decision answers `false`), the `EXPIRE` audit row under the system actor `agentflow-approval-expiry`, and the same refusal completion with `cause: "EXPIRED"`. `findOverduePending(cutoff, limit)` scans `PENDING` rows with `expiry` already past, oldest-first, bounded per pass. New audit verb `EXPIRE`; `ApprovalStatus.EXPIRED` already existed in the `approvals_status_check` vocabulary.
- **The sweep.** `worker.ApprovalExpiryScheduler` — `@Scheduled` on `agentflow.worker.approval-expiry-fixed-rate-ms` (default 5 s), enabled per role by `agentflow.worker.approval-expiry-enabled=true` (default off, the API role never expires approvals), gated on `DataSource` like the recovery scheduler. One pass: scan (bounded by `approval-expiry-batch-size`, default 16) → pre-check the gated run is still `WAITING_FOR_APPROVAL` (a run that moved on owns its lifecycle; a missing/cross-tenant run is indistinguishable from moved-on) → expire through the service → one `agentflow.workflow.approvals.expired` counter increment per won item (no tenant/run label, invariant 9). A lost race, a faulted item or a moved-on run never stops the rest of the batch; the sweep is idempotent, so a re-run after a crash expires nothing twice (at-least-once, ADR-003).
- **The worker poll no longer fights the pause.** `WorkflowWorker#processRun` special-cases two states around the `run.started` transition. A run claimed in `WAITING_FOR_APPROVAL` (the pause released the lease, so a claim is possible even though no worker may drive it) answers by releasing the accidental claim again and making no progress — a poll neither steals the run back to `RUNNING` (a lie about the state, and a race against the decision's own resume edge) nor re-runs the gated step. A run already `RUNNING` when claimed — resumed by an approval decision, or re-claimed after its owner's lease expired — is driven without the transition: the state machine forbids `RUNNING -> RUNNING`, and the re-drive is invisible on the run's transition history.
- **Config.** `AgentFlowProperties.Worker` gains `approval-expiry-enabled` (default `false`), `approval-expiry-fixed-rate-ms` (default `5000`), `approval-expiry-batch-size` (default `16`, `@Min(1)`).

### Why

The plan's acceptance: «отклонённый/просроченный запрос не должен запускать side effect» and «продолжение/отказ публиковать только через outbox». The state machines (`run-state-machine.md` §3, `step-state-machine.md` guard 11) already named the edges — `run.approved` (resume), `run.rejected` (refusal) — this task lands them. The pause released the lease (S11-03), which created two new worker-race shapes (steal-back, double `run.started`) that the poll must refuse.

### Verification

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.workflowexecution.application.ApprovalServiceTest' \
  --tests 'com.example.agentflow.workflowexecution.web.ApprovalControllerTest' \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalRepositoryTest' \
  --tests 'com.example.agentflow.workflowexecution.worker.ApprovalExpirySchedulerTest' \
  --tests 'com.example.agentflow.workflowexecution.application.WorkflowWorkerTest' \
  --rerun-tasks
./gradlew :apps:agentflow-app:integrationTest \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalDecisionWriteIT'
```

`BUILD SUCCESSFUL`, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`): `ApprovalServiceTest` **26** (the S11-04 guards plus: the resume — run to `RUNNING`, step `RUNNING`→`SUCCEEDED`, one `run-resumed` outbox event; the refusal — run and step to `FAILED`, one `run-rejected` event with `cause`; reject replays state- and key-based; reject 409s on approved-elsewhere/expired/step-moved/lost-race; expiry — guarded write, `EXPIRE` audit under the system actor, refusal with `cause: EXPIRED`; lost expiry race answers `false` with no side effects), `ApprovalControllerTest` **7** (reject routes to `ApprovalService#reject` and returns `200` with the decision; an unknown decision is a `400` touching neither half), `ApprovalRepositoryTest` **13** (the shared decision write's SQL/binds for approve and reject, SQL-NULL bindings, zero-rows → conflict, `expirePending` writes no actor/comment, the overdue scan's SQL/bounds/mapper), `ApprovalExpirySchedulerTest` **5** (expired-and-counted with the invariant-9 counter, moved-on run skipped, lost race and faulted item never stop the batch, empty pass), `WorkflowWorkerTest` **15** (the S11-03 restart acceptance plus: a paused run is not stolen back — no `run.started`, no step moves, the accidental claim released; an already-`RUNNING` claimed run is driven to its conclusion without a second `run.started`).

Integration on `pgvector/pgvector:0.8.6-pg18` (the same image as Compose — never H2): `ApprovalDecisionWriteIT` **5** — the guarded writes against real rows: a reject lands with bumped version; a second decision on the decided row is a conflict on both the stale and the fresh version; a genuine two-thread approve/reject race affects **exactly one** row (invariant 4); `expirePending` marks `EXPIRED` with `decision_actor`/`decision_comment` NULL; the overdue scan returns only overdue `PENDING` rows oldest-first.

### Documentation

- `docs/architecture/kafka-topics.md` and `docs/architecture/events/README.md` — the two new topics/events (`run-resumed.v1`, `run-rejected.v1` with the closed `cause` vocabulary) in the tables and as example payloads.
- `docs/architecture/module-api.md` — the `ApprovalService` entry (decision half of the lifecycle, the sweep, the worker poll contract) and the published-event-types row.
- `contracts/openapi/openapi.json` — regenerated (the decision endpoint now documents both `approve` and `reject`).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`; this devlog section (newest-first, above S11-04).

### Risks / follow-up

- The expiry sweep runs per workflow-worker role and scans across tenants (like the S07-09 recovery scan); each item is fenced by its own guarded write and the run pre-check, and the counter carries no tenant label (invariant 9).
- A human decision racing the sweep between the scan and the write wins or loses on the `version` fence — the loser makes no progress, at-least-once (ADR-003), never exactly-once.
- The payload binding lands in [S11-06](#s11-06--the-payload-bound-approval-the-fingerprint-re-validated-at-the-decision): the canonical fingerprint of the exact arguments is re-validated against the run's pinned graph at the decision, so a decision authorizes the step only while the stored digest still binds to the payload that would execute.

---

## S11-04 — the approve endpoint

**Task (P0):** implement the approve endpoint — require `run:approve`; check tenant, approval status/expiry and step; record the decision and the audit atomically; make a repeated decision idempotent.

**Deliverable:** `POST /api/v1/runs/{runId}/approvals/{approvalId}` — the human decision on the durable pause S11-03 parks, idempotent by state and by `Idempotency-Key`, the decision and its audit row one durable fact.

### What changed

- **The endpoint.** `workflowexecution.web.ApprovalController` accepts the decision body (`{"decision":"approve","comment":…}` — reject and the expiry lifecycle land in S11-05), the optional `Idempotency-Key` header and a canonical payload fingerprint, and delegates to `ApprovalService#approve`. The body is consumed as raw bytes and parsed canonically, so the idempotency fingerprint is wire-format independent (the controller test pins the fingerprint across `{"decision":"approve","comment":"x"}` and a pretty-printed equivalent). The OpenAPI contract documents the request body as the real `ApprovalDecisionRequest` schema (the swagger `@RequestBody` annotation overrides the raw-`String` parameter shape; `generateOpenApiContract` regenerated, `***`-scan clean).
- **The decision guards, the S11-04 acceptance ("unauthorized user / another tenant cannot approve a step").** The access matrix gates the endpoint on the `run:approve` authority (S04-08, new `AccessMatrix` rule). `ApprovalService#approve` looks the approval up by `(tenant_id, run_id, id)` — another tenant's approval is a `404`, never a leak (invariant 1) — and decides only a `PENDING`, unexpired approval whose step is still `WAITING_FOR_APPROVAL` (`409` otherwise). The write is the guarded transition itself: a conditional `UPDATE` on `status = 'PENDING'` and the optimistic-lock `version`, a zero-rows outcome a `ConflictException` (invariant 4, at-least-once `ADR-003`).
- **The atomic audit append.** `AuditService.record(...)` (the new `APPROVE` verb / `APPROVAL` resource type) joins the same `@Transactional` boundary, so the decision and its audit row commit or roll back together — across the new `workflowexecution -> audit` compile edge recorded as `ADR-018` (module-map arrow 20; the architecture gates `ArchitectureBoundaryTest` / `PackageDependencyGraphTest` updated). The audit row carries the actor, the verb, the resource type and the approval id — never the comment text, never the payload (invariant 8).
- **Idempotency, two layers.** By state: a repeat approve on an already-`APPROVED` approval replays the recorded decision, and a lost race re-reads the winner instead of failing. By key: migration `V3_3_23` adds the nullable `idempotency_key` / `idempotency_fingerprint` pair and the `approvals_tenant_idempotency_key` unique guard (the `workflow_runs` convention of S06-05/S06-06) — the stored fingerprint proves a replay, a different payload under a reused key a `409`, and a duplicate-key race is answered from the keyed row re-read. An unkeyed decision skips the keyed path entirely; the state-based replay still answers a repeat.
- **New types.** `workflowexecution.domain.ApprovalStatus` mirrors the `approvals_status_check` vocabulary (`PENDING`/`APPROVED`/`REJECTED`/`EXPIRED`/`CANCELLED`); `application.ApprovalDecisionRequest` is the decision body; `application.ApprovalDecisionDto` is the response snapshot (`approvalId`, `runId`, `stepKey`, `status`, `decisionActor`, `decisionAt`, `decisionComment` — never the payload or the fingerprint preimage, invariant 8). `ApprovalRepository` gains the tenant-scoped `find` / `findByIdempotencyKey` reads and the guarded `recordApprove` write.

### Why

- **Distrust of the model (invariant 7):** the human decision is the gate — it must be recorded by the backend, with the actor from the verified security context, never from the request body.
- **Tenant isolation (invariant 1):** every read and the guarded write carry the `tenant_id` predicate, so a foreign approval is indistinguishable from a missing one (404, not a leak).
- **PostgreSQL is the source of truth (invariant 4):** the decision is a state-machine transition — conditional update + optimistic version in one transaction, never a read-then-write.
- **At-least-once (`ADR-003`):** a repeated decision (retry, double-click, redelivery) answers with the recorded decision instead of a second write — by state and by key.

### Verification

New DB-free suites, JUnit-XML-verified (`failures="0" errors="0" skipped="0"`): `ApprovalServiceTest` (`tests="15"` — happy path with the atomic audit append, the state- and key-based replays, 404 cross-tenant/missing, 409 decided-otherwise/expired/step-moved/lost-race/different-payload, the duplicate-key race answered from the keyed row, 503 without a database); `ApprovalRepositoryTest` (`tests="8"` — the tenant-scoped `find`/`findByIdempotencyKey` SQL, the `recordApprove` binds driven through the `PreparedStatementSetter`, SQL-NULL bindings, zero-rows → `ConflictException`); `ApprovalControllerTest` (`tests="6"` — the `200` contract, the key/fingerprint threading, the canonical-fingerprint stability across wire formats, the `400` edges); `ApprovalStatusTest` (`tests="2"`); `AccessMatrixTest` (`tests="5"`) gains the `run:approve` rule.

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.workflowexecution.application.ApprovalServiceTest' \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalRepositoryTest' \
  --tests 'com.example.agentflow.workflowexecution.web.ApprovalControllerTest' \
  --rerun-tasks
```

The schema probe `MigrationsTest#approvals_idempotency_key_is_nullable_and_unique_per_tenant` proves the `V3_3_23` acceptance on `pgvector/pgvector:0.8.6-pg18` via Testcontainers: the columns are nullable, the unique guard rejects a second decision under the same `(tenant, key)`, the tenant-scoped guard accepts the same key under another tenant, an unkeyed decision lands.

```bash
./gradlew :apps:agentflow-app:integrationTest --tests '*MigrationsTest*' --rerun-tasks
```

`MigrationsTest` `tests="22" failures="0" errors="0" skipped="0"` (`BUILD SUCCESSFUL`). `./gradlew :apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL` — unit + architecture gates (`ArchitectureBoundaryTest` `tests="1"`, `PackageDependencyGraphTest` `tests="4"`, `NoCyclicModuleDependenciesTest` `tests="1"` — all `failures="0"`, XML verified fresh) + no-cycles + formatting (`spotless`) + the OpenAPI contract gate (`OpenApiContractCheckTest` green against the regenerated `contracts/openapi/openapi.json`, `***`-scan zero). All counts read from the XML in `apps/agentflow-app/build/test-results/`.

### Documentation

- `docs/adr/ADR-018-workflowexecution-audit-edge.md` — the new `workflowexecution -> audit` compile edge (the decision/audit atomicity), added to the `docs/adr/README.md` register.
- `docs/architecture/module-map.md` — arrow 20 and the `workflowexecution` dependency row.
- `docs/architecture/module-api.md` — the `ApprovalService` entry and the real permitted public types (`ApprovalStatus`, `ApprovalDecisionRequest`, `ApprovalDecisionDto`).
- `docs/security/permissions.md` — the `POST /api/v1/runs/*/approvals/*` → `run:approve` matrix row.
- `contracts/openapi/openapi.json` — regenerated (the new path, the `ApprovalDecisionRequest` body schema, the `Idempotency-Key` header parameter).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`; this devlog section (newest-first, above S11-03).

### Risks / follow-up

- The approve endpoint records the decision; the resume of the run (step/run transition out of `WAITING_FOR_APPROVAL`, published only through the outbox) landed in [S11-05](#s11-05--reject-and-the-expiry-lifecycle-the-full-approval-lifecycle), together with reject and the expiry lifecycle.
- The payload binding lands in [S11-06](#s11-06--the-payload-bound-approval-the-fingerprint-re-validated-at-the-decision): the canonical fingerprint of the exact arguments is re-validated against the run's pinned graph at the decision, so a decision authorizes the step only while the stored digest still binds to the payload that would execute.

---

## S11-03 — durable approval pause (`WAITING_FOR_APPROVAL`)

**Task (P0):** the worker stops a run at a `HUMAN_APPROVAL` side-effect boundary and parks it in `WAITING_FOR_APPROVAL` — the durable pause the `approvals` table (S11-02) records.

**Deliverable:** the worker pause — a durable checkpoint of where execution stopped, with the worker lease released while the run waits. No new table, no new endpoint, no new module edge.

### What changed

- **A boundary before the handler.** `workflowexecution.application.WorkflowWorker#executeStep` gains the gate: at the top of a step's lifecycle — before the heartbeat and the `StepHandler` — it checks `HUMAN_APPROVAL.equals(node.type())` and, when true, routes to `pauseForApproval` instead of running a handler. The gated side effect never executes (invariant 7), so the model cannot run a forbidden tool past a human gate and no worker thread is held while the run waits (S11-03 acceptance).
- **`pauseForApproval`** records the durable approval first (`ApprovalRepository#create(tenant, run, step, action, payloadFingerprint, actor, actor)` — the redacted `action` summary and the canonical-config SHA-256 `payload_fingerprint`, never the payload; invariant 8), parks the run (`RunStateMachineService#transitionRun(RUNNING → WAITING_FOR_APPROVAL, run.approval-needed)` — applied before the step; the run-level pause is the higher-level fact and if the run's lease moved the pause changes nothing and the next poll recovers) and then the step (`transitionStep(WAITING_FOR_APPROVAL, step.approval-needed)`), and finally releases the run's lease (`WorkflowRunRepository#releaseLease` — a new conditional `UPDATE ... SET lease_owner = NULL, lease_until = NULL, version = version + 1 WHERE id = ? AND tenant_id = ? AND lease_epoch = ?`, fenced on `lease_epoch`, invariant 5) so the parked run is claimable again for the resume (S11-04) rather than re-running its gated step.
- **Best-effort release.** The lease release is wrapped: a `ConflictException` (the lease moved in the gap between the step and the release) is logged, never failed. The pause is durable regardless of the release — a restart re-pauses rather than skipping the approval (the acceptance: a restart neither skips the approval nor leaves a thread occupied). The checkpoint of where execution stopped is the `WAITING_FOR_APPROVAL` state itself — a higher-level pause, not a step failure; the step's in-flight attempt is concluded by the transition service (invariant 4).

### Why

- **Distrust of the model (invariant 7):** the `HUMAN_APPROVAL` node is a deny-by-default gate — the gated side effect must not run without an explicit human decision.
- **Lease/fencing (invariant 5):** the parked run must not hold a worker thread; releasing the lease (fenced on `lease_epoch`) makes it claimable for the resume instead of re-running the gated step.
- **PostgreSQL is the source of truth (invariant 4):** the pause is durable in the run/step status; Redis is not consulted.
- **Consistency (ADR-002/003):** forward-only — no new migration (`approvals` is `V3_3_22` / S11-02); the pause is a runtime `WAITING_FOR_APPROVAL`, not a step failure.

### Verification

One new DB-free acceptance suite, JUnit-XML-verified: `WorkflowWorkerTest` (`apps/agentflow-app/src/test/java/com/example/agentflow/workflowexecution/application/WorkflowWorkerTest.java`, `tests="13"` `failures="0"` `errors="0"` `skipped="0"`) gains `a_human_approval_pause_survives_a_restart_without_skipping_the_side_effect` — a two-node graph (`HUMAN_APPROVAL` → `NOOP`) pauses at the gate (`send-report` in `WAITING_FOR_APPROVAL`, the downstream `send-email` still `PENDING`, its recording handler never invoked), records one durable approval at the pause, releases the run's lease once and never re-holds it across a simulated restart, and proves the restart neither skips the approval nor runs the gated side effect. `ApprovalRepositoryTest` (`persistence`, `tests="2"`) covers the tenant-scoped insert and the SQL-NULL binding of the nullable `requester` / `created_by`.

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.workflowexecution.application.WorkflowWorkerTest.a_human_approval_pause_survives_a_restart_without_skipping_the_side_effect' \
  --tests 'com.example.agentflow.workflowexecution.persistence.ApprovalRepositoryTest' \
  --rerun-tasks
```

`./gradlew :apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL`. Unit + architecture gates (`ArchitectureBoundaryTest`(1), `PackageDependencyGraphTest`(4), `NoCyclicModuleDependenciesTest`(1), `AiProviderPortArchitecturalTest`(1) — `failures=0 errors=0`), no-cycles + formatting (`spotless`) + OpenAPI; no new migration, no new endpoint, no new module edge — `S11-03` does not change module ownership. Verified via the XML in `apps/agentflow-app/build/test-results/test/`.

### Documentation

- `docs/domain-glossary.md` — the **Approval node** row in §9 (human approval): "Stops the run at a safe side-effect boundary, moves it to `WAITING_FOR_APPROVAL`, and releases the worker lease while waiting."
- `docs/architecture/run-state-machine.md` — transition 2 (`RUNNING → WAITING_FOR_APPROVAL`, `step.approval-needed`): "The worker lease is released while the run waits."
- `docs/architecture/step-state-machine.md` — the step-level `WAITING_FOR_APPROVAL` row: "the run's lease is released while it awaits a human decision."
- `docs/devlog/sprint-11-human-approval-mcp-security.md` — this section (newest-first, above S11-02).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- The release is best-effort and fenced on `lease_epoch`; a `ConflictException` during the release is logged, never failed — the pause is durable regardless.
- The resume (S11-04) is the approve/reject endpoint (`POST /api/v1/runs/{runId}/approvals/{approvalId}`, scope `run:approve`), idempotent (`Idempotency-Key`), binding the decision to the fingerprint (glossary §9).

---

## S11-02 — durable approvals schema

**Task (P0):** create the approvals schema — a durable registry of human-approval requests for
dangerous (WRITE / EXTERNAL_SIDE_EFFECT) actions (S11-01); persist run/step, the action summary,
the payload fingerprint, the requester, the status, the decision (actor/time/comment) and expiry,
and keep secrets out of the approval text.

**Deliverable:** migration `V3_3_22__create_approvals.sql` (owner: `workflowexecution`).

### What changed

- **`approvals`** — one row per approval request at a side-effect boundary (the `HUMAN_APPROVAL`
  node). Columns follow glossary §9: `id`, `tenant_id` (FK `tenants`), `run_id` (FK
  `workflow_runs`), `step_key` (TEXT, like `workflow_step_runs.step_key`), `status`, `action`,
  `payload_fingerprint`, `requester`, `decision_actor`, `decision_at`, `decision_comment`,
  `expiry`; plus `version` (optimistic lock, invariant 4) and `updated_at` (mutable row,
  `database-conventions.md` S03-06 — as `workflow_runs`).
- **`CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CANCELLED'))`** — binds the
  status vocabulary at the storage layer (`approvals_status_check`).
- **`UNIQUE (run_id, step_key) WHERE status = 'PENDING'`** (`approvals_run_step_pending`) — a
  partial unique index: a step cannot have two active (PENDING) approvals at once. This is the
  task's acceptance — two active conflicting approvals on one step cannot arise by accident, even
  under a race or a duplicate event (at-least-once, ADR-003).
- Access index `approvals_run_created_at_idx` (a run's approvals in append order).
- No secrets, no full prompt, no arguments and no payload on the row — only `action` (a redacted
  action summary) and `payload_fingerprint` (a hash of the arguments, not the arguments
  themselves) (invariant 8).

### Why

- **Ownership (module-map edge):** `approvals` belongs to `workflowexecution` (see
  `apps/agentflow-app/src/main/java/com/example/agentflow/workflowexecution/AGENTS.md`). The FK
  to `workflow_runs` is allowed — it is the same owning module (unlike `tool_invocations`, where
  a cross-module FK is forbidden).
- **Tenant isolation (invariant 1):** `tenant_id` on the row + FK; `run_id` is already
  tenant-scoped (`workflow_runs.tenant_id`), so another tenant's step is indistinguishable from a
  missing one.
- **Durability (invariant 4):** `version` for the conditional update; the decision is written in
  the same transaction as the status transition (S11-04).
- **Consistency (ADR-002/003/008):** PostgreSQL is the source of truth; at-least-once, never
  exactly-once; forward-only migration with `IF NOT EXISTS`.

### Verification

The migration is applied against a real PostgreSQL (`pgvector/pgvector:0.8.6-pg18`, the same image
as Testcontainers):

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
./gradlew :apps:agentflow-app:flywayMigrate --console=plain
```

`now at version v3.3.22`. The schema is checked on the live DB (columns, `approvals_status_check`,
FK, `approvals_run_step_pending WHERE status='PENDING'`, the table comment). The acceptance is
proven by inserts in one rolled-back transaction:

- two `PENDING` on one `(run_id, step_key)` → `duplicate key value violates unique constraint
  "approvals_run_step_pending"`;
- the same step, a different run — accepted (the constraint is bound to `(run, step)`);
- `tenant_id` NULL → not-null violation; `run_id` → FK violation; `status` → CHECK violation;
- a valid `PENDING` is inserted, then the transaction is rolled back (the volume stays clean: 0
  rows).

`./gradlew :apps:agentflow-app:check --rerun-tasks` → `BUILD SUCCESSFUL`. The architecture gates
(`ArchitectureBoundaryTest`(1), `PackageDependencyGraphTest`(4),
`NoCyclicModuleDependenciesTest`(1), `AiProviderPortArchitecturalTest`(1) — `failures=0 errors=0`,
verified via the XML in `apps/agentflow-app/build/test-results/test/`).

### Documentation

- `docs/domain-glossary.md` — the `approvals` row in §9 (human approval): the canonical columns
  (+`version`/`updated_at`) + the partial unique index.
- `docs/devlog/sprint-11-human-approval-mcp-security.md` — this section (newest-first, above
  S11-01).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- `version`/`updated_at` were added per `database-conventions.md` (mutable row); if a decision will
  never mutate the row in the future, the question of whether to revisit it (it affects the
  glossary) stays open.
- The durable approval pause (`WAITING_FOR_APPROVAL`) is done — [S11-03](#s11-03-durable-approval-pause). The next steps (S11-04…S11-06): the approve/reject endpoint (S11-04), expiry, binding the decision to the fingerprint, the audit, and the MCP allowlist / risk classification / gateway hardening (S11-05…S11-06).

---

## S11-01 — tool risk classification: a closed, deny-by-default vocabulary owned by the catalogue

**Task (P0):** classify tools by risk (`READ_ONLY`, `WRITE`, `EXTERNAL_SIDE_EFFECT`); an unknown risk class is denied by default.

**Deliverable:** tool risk policy — a closed risk-class vocabulary, defined once in the owning module, with deny-by-default.

### What changed

- **Acceptance test** — `ToolRiskClassificationAvailabilityTest` (`toolexecution.application`). No new files are created in the repository; it is the only new test. It checks availability at the `ToolRegistry` level (the single source of "which tools are registered"):
  - a classified tool is available for each of the three closed risk classes;
  - a tool without a risk class (`null`) is rejected at `ToolDescriptor` construction (the contract `IllegalArgumentException`), so it cannot reach the registry → it is not available;
  - the registry holds no unclassified tool (`size()==0`, `resolve()` is empty).

- **ADR-017** — `docs/adr/ADR-017-tool-risk-classification.md`. The closed deny-by-default risk-class vocabulary: `READ_ONLY` / `WRITE` / `EXTERNAL_SIDE_EFFECT`, defined in the owning module `agentcatalog`, an unknown risk class is not available (`ToolRiskClass.valueOf` throws), `ToolDescriptor` requires a non-`null` risk class (rejects at construct time), `ToolInvocationPolicy` carries `REASON_UNKNOWN_RISK_CLASS` as a defensive fail-safe for the DB-load path. A tool without a risk class cannot be registered → it is not available.

### Why

- **Distrust of the model (invariant 7, ADR-006):** the model is an untrusted planner, not an authorization authority; the risk is decided by the backend, not the model.
- **Closed vocabulary:** without a closed set, "unknown" is meaningless; `valueOf` rejects anything outside the three values.
- **Ownership:** `agentcatalog` owns "which tools exist and how dangerous they are" (module-map edge 3); `toolexecution` only reads the risk class.
- **Verification (invariant 3):** classification is a closed, cross-module, persistent set — so extending it is a breaking change and requires an ADR.

### Verification

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.toolexecution.application.ToolRiskClassificationAvailabilityTest' \
  --tests 'com.example.agentflow.agentcatalog.domain.ToolRiskClassTest' \
  --tests 'com.example.agentflow.toolexecution.domain.ToolDescriptorTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolRegistryTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolInvocationPolicyTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolAuthoringGuideExamplesTest' \
  --rerun-tasks
```

`BUILD SUCCESSFUL`. `ToolRiskClassificationAvailabilityTest` — 2 tests, both pass (verified via the XML in `apps/agentflow-app/build/test-results/test/`), mtime is current. The tests are DB-free: `ToolRegistry` and `ToolDescriptor` are pure values.

### Documentation

- `docs/adr/ADR-017-tool-risk-classification.md` — new (ADR-017).
- `docs/adr/README.md` — a row in the ADR register (ADR-017, Accepted, 2026-11-14).
- `docs/architecture/README.md` — a row in "Current architectural constraints" (ref. ADR-017).
- `docs/adr/000-template.md`, `docs/adr/README.md` — the ADR template (in use).
- `CHANGELOG.md` — an entry in `[Unreleased] > Added`.

### Risks / follow-up

- Opening a risk class is a breaking change of the closed vocabulary (a new variant + ADR + updating `reason`/taxonomies).
- `unknown_risk_class` in `ToolInvocationPolicy` — a defensive fail-safe: the constructor already rejects an unclassified descriptor, so a valid descriptor never reaches it. It must not be removed.
- The next steps (S11-02…S11-06): the human-approval lifecycle for WRITE/EXTERNAL_SIDE_EFFECT, the approvals store, tool allowlist enforcement, MCP tool allowlist + risk classification, MCP gateway hardening.
