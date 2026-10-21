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
    // Bean Validation (hibernate-validator) so @Validated on @ConfigurationProperties
    // actually fails startup on a missing required value (S01-17). Without it the
    // validation annotations are silently ignored.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Actuator (S01-18): health + info endpoints for minimal runtime diagnostics.
    // Only health and info are exposed over HTTP; the sensitive endpoints (env,
    // configprops, beans, ...) are closed by the application config. Managed by
    // the Spring Boot BOM, so no version is pinned here.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Schema migrations (S03-01): Flyway. flyway-core is on the application
    // classpath so the migration entry point (FlywayMigrate) compiles and Spring
    // Boot's Flyway auto-configuration can run when a datasource exists. The
    // PostgreSQL dialect (flyway-database-postgresql) and the JDBC driver are on
    // this classpath too: neither of them makes Spring Boot try to auto-create a
    // DataSource — only spring-boot-starter-jdbc's DataSource auto-configuration
    // does, and that is intentionally absent until a datasource is configured
    // (S03). So the skeleton still boots without a database (S01-02). Versions
    // come from the Spring Boot BOM via the catalog; nothing is pinned inline. See
    // docs/adr/ADR-008-database-migrations.md.
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    // Structured JSON logging (S01-19): logstash-logback-encoder renders log
    // events as JSON on stdout and masks sensitive values before serialization
    // (see logback-spring.xml). BOM-independent library; the version is pinned in
    // gradle/libs.versions.toml (logstash-logback-encoder = 9.0).
    implementation(libs.logstash.logback.encoder)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JUnit Platform launcher is required on the test runtime classpath; the
    // version is managed by the Spring Boot BOM.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Architecture tests (S02-08): ArchUnit JUnit 5 enforces the module boundary
    // rules (allowed module->module edges, no cross-package imports) during
    // ./gradlew check. The aggregator pulls archunit core as a compile
    // dependency (so the classes() DSL compiles) and the runtime engine that the
    // JUnit 5 platform needs to discover @ArchTest fields. Consumed through the
    // version catalog; no version pinned here.
    testImplementation(libs.archunit.junit5)
}

// The application plugin provides `run` / `bootRun`; the Boot plugin makes
// `bootRun` use the Boot classpath. The main class points at the single Spring
// Boot entry point for this artifact.
application {
    mainClass.set("com.example.agentflow.AgentflowApplication")
}

// Schema migration entry point (S03-01). Runs the Flyway Java API (FlywayMigrate)
// against the database. Connection defaults match the Compose `local` profile
// (POSTGRES_USER/POSTGRES_DB/POSTGRES_PORT/POSTGRES_PASSWORD) so the task works
// against the local Postgres out of the box; override via -Dflyway.* or the
// FLYWAY_* environment variables. The Flyway version on this runtime classpath
// is the BOM-managed one, shared with the application, so the task and the app
// can never diverge on the migration line.
tasks.register<JavaExec>("flywayMigrate") {
    group = "database"
    description = "Applies Flyway database migrations (defaults match the compose local profile)."
    dependsOn(tasks.named("classes"))
    mainClass.set("com.example.agentflow.tooling.FlywayMigrate")
    classpath = sourceSets["main"].runtimeClasspath
    // Connection from the FLYWAY_* environment variables, falling back to the
    // Compose `local` profile defaults so the task runs against the local
    // Postgres out of the box. Override FLYWAY_URL / FLYWAY_USERNAME /
    // FLYWAY_PASSWORD for any other database.
    systemProperty("flyway.url",
        providers.environmentVariable("FLYWAY_URL").orElse("jdbc:postgresql://localhost:5432/agentflow").get())
    systemProperty("flyway.username",
        providers.environmentVariable("FLYWAY_USERNAME").orElse("agentflow").get())
    systemProperty("flyway.password",
        providers.environmentVariable("FLYWAY_PASSWORD").orElse("agentflow-dev").get())
}

// Run tests through the JUnit Platform. Explicit rather than relying on plugin
// defaults, so framework selection is self-documenting and robust across
// Gradle / Spring Boot versions.
//
// JUnit version: the Boot 4.1.1 BOM manages JUnit to the 6.0.x line, and
// spring-boot-test (compiled against the JUnit Platform 6.0 Store API) is only
// compatible with it — forcing the JUnit 5 line causes NoSuchMethodError at
// Spring context startup. We keep the BOM-managed JUnit; see CHANGELOG.md.
//
// Test strategy (S02-13): two tiers. `test` is the fast gate - the unit tests
// plus the module-boundary (S02-08) and no-cycles (S02-09) architecture gates -
// and it is the task the standard `check` lifecycle depends on, so a module
// boundary violation fails ./gradlew check on every PR. The full suite is
// integrationTest (Testcontainers on the real PostgreSQL/Kafka/Redis images,
// added in S03+); until it exists it is referenced in AGENTS.md only. Both tiers
// are JUnit Platform tests.
tasks.named<Test>("test") {
    useJUnitPlatform()
    // Fail the build (not silently pass) if a future change makes the platform
    // discover zero tests. This caught the silent "0 tests discovered" pass.
    failOnNoDiscoveredTests = true
}
