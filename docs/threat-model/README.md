# `docs/threat-model/` — threat model

Threat model of agent execution: assets, trust boundaries, attacker goals and
mitigations linked to tests. Prompt injection, tenant isolation, SSRF and log
leaks are mandatory topics.

Scaffolded in **S01-03**. The threat model is added in the security sprint
(**S11**) and summarized in `docs/security/`.

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
