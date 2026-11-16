# `docs/threat-model/` — threat model

Threat model of agent execution: assets, trust boundaries, attacker goals and
mitigations linked to tests. Prompt injection, tenant isolation, SSRF and log
leaks are mandatory topics.

Scaffolded in **S01-03**. The threat model is added in the security sprint
(**S11**) and summarized in `docs/security/`.

## Policy bypass through a planner plan (S11-12)

The model is an untrusted planner (invariant 7): the plan it returns is hostile until the
gate holds it. Giving the model only the minimal tool catalogue narrows the proposal
surface, but catalogue membership is not authorization — a plan can still name an
allowlisted tool the caller lacks the scope for, or one whose action is gated on a human
decision. The `ValidationGate` (`ai.application`) is the double policy gate: after the
model answers, the structural stage (closed node vocabulary, tool allowlist, field
contract, DAG, `PlanLimits`, S09-07) is followed by the authorization stage, which
re-checks every allowlisted tool reference against the caller-supplied `ToolGatePolicy`
facts — required scopes against the caller's *current* permission set, human-approval
requirement on the automatic path. There is deliberately no provider tool-calling channel:
`ChatRequest` carries no tools parameter, so a raw provider tool call has no path into
execution, and the invocation-time permission guard (S08-09) re-checks the current scopes
again before any tool runs.

| Threat | Mitigation | Test |
|---|---|---|
| A prompt-injected model proposes an allowlisted tool the caller holds no scope for | the second gate stage refuses the plan after the answer, naming the missing scopes, before the executor | `DoublePolicyGateTest.a_malicious_plan_naming_an_allowlisted_tool_the_caller_lacks_the_scope_for_is_held`; `ValidationGateTest.an_allowlisted_tool_the_caller_lacks_scopes_for_is_rejected` |
| A plan smuggles in a tool whose action needs a human decision | approval-gated tools are refused on the automatic path — a model-proposed plan never attests a grant (the workflow-DSL `HUMAN_APPROVAL` lifecycle is the approval path) | `DoublePolicyGateTest.a_malicious_plan_naming_an_approval_gated_tool_is_held`; `ValidationGateTest.an_allowlisted_tool_requiring_human_approval_is_rejected_on_the_automatic_path` |
| A provider tool-call payload is executed as-is | no tool-calling channel exists on `ChatRequest`; the payload deserializes into a no-steps plan and the gate refuses it ("plan declares no steps") | `DoublePolicyGateTest.a_raw_provider_tool_call_payload_declares_no_plan_and_is_held_by_the_gate` |
| The gate check widened a module edge / reached a registry | the gate stays pure: the policy facts are caller-supplied data through `ValidationContext`, so `ai` still compiles only against `ai` (S02-08) | `ArchitectureBoundaryTest` / `PackageDependencyGraphTest` green in `./gradlew check` |

## SSRF on the MCP connection (S11-10)

The MCP client adapter (`toolexecution.mcp`) opens a network socket to a host the
operator has allowlisted. An allowlisted *host* is not an allowlisted *destination*:
a workflow step, a document or a server response must never turn reachability into
a call to a loopback, metadata or private endpoint. The guard is the pure
`OutboundDestinationPolicy` (invariant 7 — the model is an untrusted planner): it
classifies a connection's host + protocol **before** any socket opens and permits
only `http`/`https` to a routable public host; loopback, unspecified, private
(RFC-1918), link-local (including the cloud metadata endpoint `169.254.169.254`),
unique-local IPv6 and reserved/future ranges are refused, and `McpClientAdapter`
re-checks the policy in both `invoke` and `isReachable`.

| Threat | Mitigation | Test |
|---|---|---|
| MCP server redirects a call to `127.0.0.1` / `169.254.169.254` / a private range | the guard refuses the connection host up front; the policy owns the network, not the caller | `OutboundDestinationPolicyTest` (loopback / unspecified / private / link-local metadata / unique-local / reserved refused; http/https permitted) |
| `ws` / `file` / `gopher` connection scheme used to reach a local resource | only `http` / `https` permitted at construction | `OutboundDestinationPolicyTest.Protocol.nonHttpProtocolIsRejectedAtConstruction` |
| The denied destination leaking through logs / metrics | the destination is observed only as a protocol + classification label (`LOOPBACK`/`PRIVATE_RANGE`/…), never the URL | `OutboundDestinationPolicyTest` `toString`; invariant 9 |
