# MCP integration and configuration (S11-07, S11-08, S11-09)

AgentFlow integrates MCP (Model Context Protocol) in two places: an isolated demo
server (`agentflow-mcp-lab`, S11-08) and an allowlisted client inside the main
app (S11-09). This document is the configuration source of truth for both. The
transport and version-compatibility decision behind every coordinate here is
recorded in [`ADR-019`](../adr/ADR-019-mcp-streamable-http-transport.md) — read it
first; this file only lists what to depend on and what to configure.

## Verified version matrix

Verified against the published artifacts and the official documentation on
2026-11-15 (commands below):

| Component | Version | Managed by | Role |
|---|---|---|---|
| Spring AI BOM | 2.0.1 (stable) | `gradle/libs.versions.toml` (`spring-ai`) | pins every Spring AI artifact |
| `spring-ai-starter-mcp-server-webmvc` | 2.0.1 | BOM | demo server (lab) |
| `spring-ai-starter-mcp-client` | 2.0.1 | BOM | client (main app) |
| `mcp` (MCP Java SDK) | 2.0.0 | transitive via `spring-ai-mcp` (BOM) | protocol implementation, both ends |
| MCP protocol revision | 2025-11-25 | MCP Java SDK 2.0.0 | wire contract |
| Spring Boot | 4.1.1 | Spring Boot BOM | runtime |

Client and server compatibility is **not configured — it is derived**: both ends
ride the same `spring-ai-bom` 2.0.1 → `spring-ai-mcp` → MCP Java SDK 2.0.0
chain, so a connection always pairs identical SDK versions and one protocol
revision. Neither app pins or imports `io.modelcontextprotocol.*` directly.

**What is deliberately not used:** the SSE transport (deprecated in Spring AI
2.0.0 and MCP Java SDK 2.0.0), the WebFlux starters (the stack forbids
WebFlux), stateless Streamable-HTTP (no need at demo scale), and the SDK 2.2.x
"modern" 2026-07-28 preview (unstable API until 3.0). See ADR-019 for the full
alternatives table.

## Demo server — `agentflow-mcp-lab` (S11-08)

Implemented as its own Gradle subproject and Spring Boot artifact (`apps/agentflow-mcp-lab/`,
`com.example.agentflow.mcplab`) — a separate process with its own lifecycle, holding no
business-database credentials (no JDBC starter, no Flyway, no PostgreSQL driver on its
classpath; isolation by absence). One Gradle dependency (version from the BOM, no
inline pin — S01-06):

```kotlin
// apps/agentflow-mcp-lab/build.gradle.kts
dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
}
```

Configuration (`apps/agentflow-mcp-lab/src/main/resources/application.yml`):

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        protocol: STREAMABLE          # the ADR-019 transport; SSE is deprecated
        name: agentflow-mcp-lab
        version: 1.0.0
        type: SYNC                    # Servlet, synchronous
        streamable-http:
          mcp-endpoint: /mcp          # default; explicit for clarity
```

The lab exposes one safe, read-only demo tool, `ticket_lookup` (the MCP specification
restricts tool names to letters, digits, `_` and `-`, so the in-process `ticket.lookup`
name cannot cross the wire), answering from a fixed in-memory fixture dataset —
fictitious tickets `TKT-1001` / `TKT-1002` / `TKT-1003`, deterministic, no network, no
database.

```bash
# run the lab independently (port 8081)
./gradlew :apps:agentflow-mcp-lab:bootRun
# its gate: the tool contract, the no-datasource isolation, and a real MCP
# initialize handshake over Streamable-HTTP
./gradlew :apps:agentflow-mcp-lab:test
```

## Client — `agentflow-app` (S11-09)

One Gradle dependency:

```kotlin
// apps/agentflow-app/build.gradle.kts
dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
}
```

### The two-sided seam

A server is reachable only when **both** sides name it — the Spring AI connection
map (transport) and the `agentflow.mcp` allowlist (authorization):

```yaml
# the main app is an MCP client only — never an MCP server
spring:
  ai:
    mcp:
      server:
        enabled: false
      client:
        enabled: true
        type: SYNC
        streamable-http:
          connections:
            agentflow-mcp-lab:
              url: http://localhost:8081   # the lab; no other host is configured

# authorization policy, not transport: which tools on which server may be called
agentflow:
  mcp:
    servers:
      agentflow-mcp-lab:
        tools: [ticket_lookup]     # the exact tool names; an empty list fails startup
        timeout-seconds: 10        # per-call timeout (default 10)
        max-response-bytes: 65536  # results above this are refused (default 64 KiB)
```

The map defaults to empty: an unconfigured app has **no MCP surface at all** — the
skeleton boots unchanged (S01-02), and the connected-client list the adapter
receives is empty.

### The invocation path

`McpClientAdapter` (`toolexecution.mcp`) is the only path from the platform to an
MCP server, and it fails closed. For `invoke(server, tool, argumentsJson)`:

1. **Unknown server** (`McpAccessDeniedException.Reason.UNKNOWN_SERVER`) — the
   server is not in the allowlist, or is allowlisted but has no connection. Refused
   before any client interaction.
2. **Tool outside the allowlist** (`TOOL_NOT_ALLOWLISTED`) — refused without even
   listing the server's capabilities.
3. **Tool not advertised** (`TOOL_NOT_ADVERTISED`) — the server's catalogue is
   fetched once per server and cached; only the intersection of the operator's
   allowlist and the server's advertisement is callable.
4. **Argument validation** — arguments must be a JSON object and must validate
   against the server's advertised input schema (the existing
   `SchemaValidator`/`JsonSchema` machinery); an MCP server is untrusted input, so
   nothing unvalidated crosses the seam. A tool that advertises no schema accepts
   any JSON object.
5. **The bounded call** — `ToolCallTimeout` enforces the per-server timeout on the
   shared tool-call pool (a timed-out call has an ambiguous outcome and releases its
   thread, invariant 6 / S08-10 semantics).
6. **The bounded result** — an answer larger than `max-response-bytes` is refused
   (`RESPONSE_LIMIT_EXCEEDED`); `isError` results surface as `ToolExecutionError`.

Because the planner is an untrusted planner (ADR-006), it only ever *proposes* a
`(server, tool)` pair; the adapter decides from the operator's configuration alone.
There is no path that takes a target from the model, a document, or a server
response and calls it.

Wiring (`config.McpClientConfiguration`): the allowlist bean is built from
`agentflow.mcp.*`; a `McpClientCustomizer` names every connection's client info
after its connection name (the adapter's deterministic client → policy mapping) and
applies the per-server request timeout at the transport level; the adapter bean
maps the starter-managed `McpSyncClient`s by connection name. No DataSource, no
Kafka — safe in every profile.

The adapter is not yet reachable from a workflow: exposing allowlisted MCP tools
as `ToolDescriptor`s in the registry (typed arguments/results, audit, budgets) and
routing `TOOL_CALL` steps to the adapter is **S11-10**.

Security constraints enforced by later tasks (S11-10+): every connection URL passes
the SSRF guard (private/loopback/link-local/metadata destinations are blocked per
environment policy).

### Tests

- `toolexecution.mcp.McpServerPolicyTest`, `McpAllowlistTest` — the policy value
  and the deny-by-default resolution.
- `toolexecution.mcp.McpClientAdapterTest` — deny paths first (no allowlist bypass
  reaches a client), argument validation, bounded call and bounded result.
- `config.McpClientConfigurationTest` — property binding/validation and the
  allowlist → adapter → customizer wiring without a database.
- `AgentflowApplicationTests` (the full default boot) proves the MCP starter on the
  classpath leaves the skeleton context intact (S01-02).

## Verification

The matrix above is not taken from blogs; each cell reproduces from the build
or the published artifacts:

```bash
# Spring AI / MCP artifacts managed by the pinned BOM (2.0.1)
./gradlew :apps:agentflow-app:dependencies --configuration runtimeClasspath | grep -i mcp

# the exact chain the BOM resolves (spring-ai-mcp -> io.modelcontextprotocol.sdk:mcp:2.0.0)
curl -s https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-mcp/2.0.1/spring-ai-mcp-2.0.1.pom \
  | grep -A2 'modelcontextprotocol'

# the BOM itself manages every MCP starter at the BOM version
curl -s https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-bom/2.0.1/spring-ai-bom-2.0.1.pom \
  | grep -o 'spring-ai-starter-mcp-[a-z-]*' | sort -u
```

Deprecation status (SSE deprecated since Spring AI 2.0.0 / SDK 2.0.0) is
checked against the Spring AI 2.0.1 reference (MCP server boot starter page) and
the MCP Java SDK changelog on every Spring AI version bump — recorded as a
follow-up in ADR-019.

## Docs

- [`ADR-019`](../adr/ADR-019-mcp-streamable-http-transport.md) — the transport
  and compatibility decision (context, alternatives, consequences).
- Sprint 11 devlog — the S11-07 / S11-08 / S11-09 records.
