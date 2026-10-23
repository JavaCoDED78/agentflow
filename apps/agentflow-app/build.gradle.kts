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
    // Persistence approach (S03-08): Spring Data JDBC is the single approach for the whole
    // monolith (ADR-009). starter-jdbc brings spring-jdbc, spring-tx and HikariCP, and — unlike
    // flyway-core alone — it auto-configures a DataSource. That auto-configuration is NOT a
    // no-op when no datasource is configured: it eagerly tries to build a HikariDataSource and
    // FAILS ("Failed to determine a suitable driver class"). It is therefore excluded in the base
    // profile (application.yml, spring.autoconfigure.exclude) so the skeleton still boots without a
    // database (S01-02), and re-enabled in the local profile once spring.datasource.* is supplied
    // from the environment.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Structured JSON logging (S01-19): logstash-logback-encoder renders log
    // events as JSON on stdout and masks sensitive values before serialization
    // (see logback-spring.xml). BOM-independent library; the version is pinned in
    // gradle/libs.versions.toml (logstash-logback-encoder = 9.0).
    implementation(libs.logstash.logback.encoder)
    // OpenAPI contract generation (S03-12): springdoc-openapi v3 is compatible with the
    // Boot 4.1.1 / Spring Framework 7 / Jackson 3 stack. Provides the OpenAPI description
    // (JSON/YAML) and Swagger UI for local exploration; the generated spec is checked in under
    // contracts/openapi/. Version comes from the catalog; nothing pinned inline.
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // JUnit Platform launcher is required on the test runtime classpath; the
    // version is managed by the Spring Boot BOM.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Testcontainers (S03-15): a real PostgreSQL (pgvector/pgvector:0.8.6-pg18, the same image as
    // Compose) for the migration integration suite. The version is BOM-managed by the Spring Boot
    // BOM's Testcontainers import; nothing is pinned inline and no H2 is used as a stand-in for
    // PostgreSQL-specific behaviour (AGENTS.md). The 2.0.5 module coordinate is
    //   `testcontainers-postgresql` (the 1.x `postgresql` name no longer resolves).
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // JUnit 5 integration (@Container / @Testcontainers) is a separate module in
    // Testcontainers 2.0.5 (the 1.x bundle is gone); managed by the Spring Boot BOM.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
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

// Regenerate the checked-in OpenAPI contract (S03-16). Boots the app on the
// default profile (no database needed), normalizes the generated description and
// writes it to contracts/openapi/openapi.json. Never edit that file by hand:
// the CI compatibility gate compares the running app against this baseline.
tasks.register<JavaExec>("generateOpenApiContract") {
    group = "verification"
    description = "Regenerates the checked-in OpenAPI contract (contracts/openapi/openapi.json)."
    dependsOn(tasks.named("classes"))
    mainClass.set("com.example.agentflow.tooling.OpenApiContractTool")
    classpath = sourceSets["main"].runtimeClasspath
    // Quiet the Spring banner/logs so the normalized JSON is the only thing printed.
    systemProperty("logging.level.root", "WARN")
    systemProperty("logging.level.org.springframework.boot.autoconfigure", "WARN")
    args("--output", File(rootProject.projectDir, "contracts/openapi/openapi.json").absolutePath)
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
// Test strategy (S02-13): two tiers, both JUnit Platform tests.
//
// `test` is the fast gate - the unit tests plus the module-boundary (S02-08)
// and no-cycles (S02-09) architecture gates - and it is the task the standard
// `check` lifecycle depends on, so a module boundary violation fails
// ./gradlew check on every PR.
//
// `integrationTest` (S03-15) is the full suite: real PostgreSQL/Kafka/Redis
// via Testcontainers instead of H2 (AGENTS.md). It is a separate task, run with
// `./gradlew integrationTest`, so the Docker-backed suite does not slow the fast
// `check` gate. It shares the unit-test classpath (integrationTestImplementation
// extends testImplementation) so the Testcontainers / JUnit dependencies are
// declared once, not duplicated.
tasks.named<Test>("test") {
    useJUnitPlatform()
    // Fail the build (not silently pass) if a future change makes the platform
    // discover zero tests. This caught the silent "0 tests discovered" pass.
    failOnNoDiscoveredTests = true
    // OpenAPI contract gate (S03-16): hand the integration test the path to the
    // checked-in baseline so it can compare the live app against it.
    val contractBaseline = File(rootProject.projectDir, "contracts/openapi/openapi.json")
    systemProperty("openapi.contract.baseline", contractBaseline.absolutePath)
}

// The integration-test source set (S03-15). Created explicitly so Gradle makes
// its own configurations; the main output is on the classpath so
// @SpringBootTest-style suites can start the application, and the unit-test
// configurations are inherited so the Testcontainers / JUnit stack is shared.
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations["testImplementation"])
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations["testRuntimeOnly"])
}

tasks.register<Test>("integrationTest") {
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    // Fail loudly if the suite is ever emptied instead of silently passing.
    failOnNoDiscoveredTests = true
}
