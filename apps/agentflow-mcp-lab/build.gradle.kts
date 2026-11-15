// Isolated MCP demo server (S11-08, ADR-019).
//
// A separate Spring Boot artifact — `agentflow-mcp-lab` — exposing one or two safe,
// read-only demo tools over the Streamable-HTTP MCP transport. It is deliberately NOT a
// package of agentflow-app: it runs as its own process, is wired to nothing the monolith
// owns, and carries no business-database credentials (no JDBC starter, no Flyway, no
// PostgreSQL driver — isolation by absence, so a credential can never leak in through
// autoconfiguration). See AGENTFLOW_ARCHITECTURE_AND_PLAN.md Sprint 11 and
// docs/adr/ADR-019-mcp-streamable-http-transport.md.
//
// Versions live in the version catalog (gradle/libs.versions.toml, S01-06); the Spring
// Boot BOM and the Spring AI BOM are imported so their managed transitive versions win
// and nothing is re-pinned.

plugins {
    id("org.springframework.boot") version "4.1.1"
    application
}

dependencies {
    // Spring Boot BOM pins every managed transitive version; nothing re-pinned.
    implementation(platform(libs.spring.boot.bom))
    // Spring AI BOM (S01-08): manages the MCP server starter and its SDK chain at 2.0.1
    // (ADR-019 — client/server compatibility is derived from this single BOM line).
    implementation(platform(libs.spring.ai.bom))
    // Servlet web stack for the Streamable-HTTP transport (the project forbids WebFlux).
    implementation("org.springframework.boot:spring-boot-starter-web")
    // The Streamable-HTTP WebMVC MCP server starter (ADR-019). BOM-managed; the main app
    // never takes this dependency — it is an MCP client only.
    implementation(libs.spring.ai.starter.mcp.server.webmvc)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JUnit Platform launcher is required on the test runtime classpath; the version is
    // managed by the Spring Boot BOM.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The application plugin provides `run` / `bootRun`; the Boot plugin makes `bootRun` use
// the Boot classpath. The main class is the lab's single Spring Boot entry point.
application {
    mainClass.set("com.example.agentflow.mcplab.McpLabApplication")
}

// Run tests through the JUnit Platform (Boot 4.1.1 manages JUnit to the 6.0.x line;
// spring-boot-test is only compatible with it — see agentflow-app/build.gradle.kts).
tasks.named<Test>("test") {
    useJUnitPlatform()
    // Fail the build (not silently pass) if a future change makes the platform discover
    // zero tests, the same guard as the monolith's fast gate.
    failOnNoDiscoveredTests = true
}
