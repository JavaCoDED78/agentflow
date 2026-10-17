// Spring Boot application artifact for the AgentFlow modular monolith (S01-07).
//
// This is a single image that runs several runtime roles (api /
// workflow-worker / ingestion-worker) via different profiles; the role split is
// documented in apps/AGENTS.md. Module packages live in
// src/main/java/com/example/agentflow/<module> (see the plan Sprint 4 role
// split).
//
// On purpose this task stays minimal: only the Boot plugin and starter-web are
// wired so the artifact boots and stops on a clean machine. No Kafka consumers
// and no AI calls yet (see DEVELOPMENT_PLAN.md, Sprint 1). Versions live in the
// version catalog (gradle/libs.versions.toml, S01-06); the Spring Boot BOM is
// imported so its managed transitive versions win and nothing is re-pinned.

plugins {
    id("org.springframework.boot") version "4.1.1"
    application
}

dependencies {
    // Spring Boot BOM pins every managed transitive version; nothing re-pinned.
    implementation(platform(libs.spring.boot.bom))
    // Spring AI BOM (S01-08): consume the BOM-managed, Boot-compatible
    // transitive versions. Importing a BOM only manages versions, so it does
    // not add runtime artifacts here and no AI code is wired yet (S08+).
    // Boot 4.1.1 + Spring AI 2.0.1 verified together in S01-08 (uniform
    // Spring Framework 7.0.9, Jackson 3.1.5) — see dependency-management.md.
    implementation(platform(libs.spring.ai.bom))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JUnit Platform launcher is required on the test runtime classpath; the
    // version is managed by the Spring Boot BOM.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The application plugin provides `run` / `bootRun`; the Boot plugin makes
// `bootRun` use the Boot classpath. The main class points at the single Spring
// Boot entry point for this artifact.
application {
    mainClass.set("com.example.agentflow.AgentflowApplication")
}

// Run tests through the JUnit Platform. Explicit rather than relying on plugin
// defaults, so framework selection is self-documenting and robust across
// Gradle / Spring Boot versions.
//
// JUnit version: the Boot 4.1.1 BOM manages JUnit to the 6.0.x line, and
// spring-boot-test (compiled against the JUnit Platform 6.0 Store API) is only
// compatible with it — forcing the JUnit 5 line causes NoSuchMethodError at
// Spring context startup. We keep the BOM-managed JUnit; see CHANGELOG.md.
tasks.named<Test>("test") {
    useJUnitPlatform()
    // Fail the build (not silently pass) if a future change makes the platform
    // discover zero tests. This caught the silent "0 tests discovered" pass.
    failOnNoDiscoveredTests = true
}
