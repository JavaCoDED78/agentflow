# MCP integration and configuration (S11-07)

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

## Demo server — `agentflow-mcp-lab` (lands in S11-08)

One Gradle dependency (version from the BOM, no inline pin — S01-06):

```kotlin
// apps/agentflow-mcp-lab/build.gradle.kts — illustrative; the module lands in S11-08
dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
}
```

Configuration:

```yaml
# agentflow-mcp-lab — illustrative; the module lands in S11-08
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

The lab exposes one or two safe, read-only demo tools and holds **no credentials
to the business database** (S11-08 acceptance).

## Client — `agentflow-app` (wired in S11-09)

One Gradle dependency:

```kotlin
// apps/agentflow-app/build.gradle.kts — lands in S11-09
dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
}
```

Configuration — the connection map **is** the allowlist seam: only named
connections exist, and S11-09 refuses any target outside it:

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
```

Security constraints on the client (each enforced by its own task, S11-09 /
S11-10): the model can only reach configured connections (allowlist), every
connection URL passes the SSRF guard (private/loopback/link-local/metadata
destinations are blocked per environment policy), and unknown server/tool names
are refused without a call.

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
- Sprint 11 devlog — the S11-07 record.
