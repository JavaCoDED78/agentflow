# ADR-019. MCP transport and client/server compatibility: Streamable-HTTP on the stable Spring AI 2.0.x line

- **Status:** Accepted
- **Date:** 2026-11-15
- **Task:** S11-07 (Sprint 11 — human approval, MCP and security of agent actions)
- **Supersedes:** none
- **Superseded by:** none

## Context

AgentFlow plans an isolated MCP demo server (`agentflow-mcp-lab`, S11-08) and an
allowlisted MCP client in the main app (S11-09). The MCP ecosystem moved fast:
the transport story changed twice in one year, and several APIs that tutorials
still reference are deprecated or relocated. The acceptance criterion for this
decision is firm: **it must not rest on a deprecated API or an experimental
transport** — everything else in Sprint 11 (the allowlist, the SSRF guard, the
demo server) builds on this seam, so a wrong transport choice here would force a
rewrite downstream.

Verified against the published artifacts and the official documentation on
2026-11-15:

- Spring AI **2.0.1** (pinned in `gradle/libs.versions.toml`) is the current
  **stable** line. Its MCP reference marks the SSE WebMVC/WebFlux server
  *deprecated since 2.0.0 — "use STREAMABLE instead"*, and documents the
  Streamable-HTTP transport (introduced with MCP spec revision 2025-03-26) as
  the replacement for SSE.
- Spring AI 2.0.1's `spring-ai-mcp` depends on `io.modelcontextprotocol.sdk:mcp`
  **2.0.0** (verified from the published `spring-ai-mcp-2.0.1.pom`). MCP Java
  SDK 2.0.0 (2026-06-11) tracks the MCP specification revision **2025-11-25**
  and deprecates the SSE transports in favor of Streamable HTTP.
- In Spring AI 2.0, the Spring-specific transport artifacts
  (`mcp-spring-webmvc`, `mcp-spring-webflux`) moved from the
  `io.modelcontextprotocol.sdk` group to `org.springframework.ai`; the
  `spring-ai-bom` manages every MCP artifact (`spring-ai-starter-mcp-server`,
  `spring-ai-starter-mcp-server-webmvc`, `spring-ai-starter-mcp-server-webflux`,
  `spring-ai-starter-mcp-client`, `spring-ai-starter-mcp-client-webflux`,
  `mcp-spring-webmvc`, `mcp-spring-webflux`, `spring-ai-mcp`) at 2.0.1 — no
  inline version pins are needed or allowed (S01-06).
- MCP Java SDK 2.2.0 previews a new "modern" stateless server for the
  **2026-07-28** specification revision; its own changelog states *"the API may
  change in minor releases until 3.0"* — explicitly experimental.

The project invariants this decision must respect: the dependency BOM is the
single source of versions (S01-06); no WebFlux — the stack is Spring MVC with a
single persistence approach; stable releases only, no deprecated APIs without an
ADR; the model and any tool output are untrusted (`ADR-006`, invariant 7).

## Decision

**The demo MCP server and the MCP client both use the Streamable-HTTP transport,
exclusively through Spring AI 2.0.1 BOM-managed starters — nothing deprecated,
nothing experimental.**

- **Demo server** (`agentflow-mcp-lab`, S11-08): `spring-ai-starter-mcp-server-webmvc`
  with `spring.ai.mcp.server.protocol=STREAMABLE`, synchronous server type —
  the Servlet stack the project already standardizes on.
- **Client** (`agentflow-app`, S11-09): `spring-ai-starter-mcp-client` — the JDK
  `HttpClient`-based starter carrying STDIO and Streamable-HTTP/SSE transports.
  Only Streamable-HTTP connections are configured; the connection map *is* the
  allowlist seam S11-09 enforces.
- **No direct MCP Java SDK usage.** Neither app imports
  `io.modelcontextprotocol.*` classes or pins SDK artifacts. The SDK rides
  transitively through `spring-ai-mcp` (2.0.0 today), so both ends of a
  connection run the same SDK version by construction — the client/server
  compatibility pairing is the BOM's job, not ours.
- **The main app never exposes an MCP server** — it is a client only
  (`spring.ai.mcp.server.enabled=false`; no server starter on its classpath).

## Alternatives considered

- **SSE transport** — the pre-2025-03-26 HTTP transport. Rejected: deprecated in
  Spring AI 2.0.0 (server side) and in MCP Java SDK 2.0.0 (client and server);
  the MCP specification itself replaced it with Streamable HTTP. Choosing it
  would fail the acceptance criterion on day one.
- **STDIO transport** — the local-subprocess transport. Rejected for the demo:
  `agentflow-mcp-lab` is a separate network service by design (S11-08), and
  STDIO would couple the demo to a local process launch instead. (It remains
  available in the client starter for future local tools.)
- **Stateless Streamable-HTTP** (`protocol=STATELESS`) — the same transport
  without per-session state, suited to horizontally scaled deployments.
  Rejected for the demo: a single-node lab gains nothing from it, and the
  stateful streamable server is the documented default. Revisit with an ADR if
  the lab ever needs replication.
- **WebFlux starters** (`spring-ai-starter-mcp-server-webflux`,
  `spring-ai-starter-mcp-client-webflux`, and the Spring AI docs' "production
  recommendation") — rejected: the project stack forbids introducing WebFlux
  (AGENTS.md), and the Servlet-based JDK-HttpClient client is sufficient for a
  demo-scale allowlisted connection.
- **MCP Java SDK 2.2.x "modern" 2026-07-28 preview** — rejected: the SDK
  changelog declares the API unstable until 3.0; the acceptance criterion
  excludes experimental transports.
- **Direct `io.modelcontextprotocol.sdk` artifacts** — rejected: the 2.0 group-id
  relocation (`mcp-spring-webmvc`/`mcp-spring-webflux` → `org.springframework.ai`)
  makes direct SDK pinning a maintenance hazard, and direct imports would bypass
  the BOM discipline (S01-06).

## Consequences

- **Positive:** one transport, one BOM line, zero version pins; client and server
  can never skew on the MCP protocol revision because both ride the same
  `spring-ai-bom` 2.0.1 → `spring-ai-mcp` → MCP Java SDK 2.0.0 chain. The
  decision is verifiable from published POMs, not from blog posts.
- **Negative / operational:** Spring AI's own docs recommend the WebFlux client
  for production deployments; we accept the JDK-HttpClient client as the
  deliberate cost of the no-WebFlux stack rule, at demo scale. The Streamable-HTTP
  server keeps per-connection session state — a single-node constraint already
  true of the lab design.
- **Follow-ups:** S11-08 builds the lab on these coordinates; S11-09 wires the
  client behind the allowlist (named connections only, unknown server/tool
  refused); S11-10 adds the SSRF guard for the connection URLs. A Spring AI
  version bump re-verifies this ADR's facts (deprecation status, SDK line) as
  part of the bump's compatibility check.
