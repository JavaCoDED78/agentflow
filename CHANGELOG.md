# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

### Added

- **Java 25 toolchain (`S01-05`).** The Gradle build pins the JDK 25 toolchain
  for every subproject, forces UTF-8 source encoding and a fixed compile
  release (`options.release = 25`), so the build never silently uses the
  host/system JDK. `gradle.properties` disables on-demand JDK downloads, so a
  missing or incompatible JDK fails fast and explicitly (offline CI).
- **Version catalog (`S01-06`).** Plugin and dependency versions are centralized in
  `gradle/libs.versions.toml`; modules reference them via the `libs` accessor instead
  of pinning versions inline, so one library cannot drift across build files.
  Spring-managed dependencies go through the Spring Boot and Spring AI BOMs
  (`spring-boot-dependencies:4.1.1`, `spring-ai-bom:2.0.1`).
  `pluginManagement`/`dependencyResolutionManagement` in `settings.gradle.kts` point at
  the Gradle Plugin Portal and Maven Central. Verified with `./gradlew help` and by
  resolving both BOMs (and applying the Boot plugin) against the real catalog.

- **Gradle reproducibility (S01-09).** The build now pins the wrapper checksum
  (`gradle/wrapper/gradle-wrapper.sha256`, checked by the `verifyWrapper` task on
  every invocation) and enables dependency locking (`lockAllConfigurations()`),
  committing `apps/agentflow-app/gradle.lockfile` (the main app dependency lock) plus
  the placeholder locks `settings-gradle.lockfile` and `apps/gradle.lockfile`; plugin
  versions are pinned explicitly in `pluginManagement`, so a build against a different
  graph fails instead of drifting. Update a dependency by
  editing the version catalog and regenerating the locks with
  `./gradlew --write-locks generateLockFiles`, then keep `./gradlew check` green.

- **Spring AI BOM compatibility (`S01-08`).** The Spring AI BOM
  (`spring-ai-bom:2.0.1`) is now consumed by `apps/agentflow-app`
  (`implementation(platform(libs.spring.ai.bom))`). Both BOMs import together with
  no forced overrides: Spring Framework resolves uniformly to `7.0.9` and Jackson
  to `3.1.5` (`tools.jackson`), so the dependency graph is predictable and
  reproducible. Manual overrides of Spring Framework/Jackson/Security remain
  forbidden without an ADR. A runtime test
  (`SpringAiBomCompatibilityTest`) exercises the BOM-managed `ObjectMapper`. See
  `docs/architecture/dependency-management.md` and the saved
  `docs/architecture/dependency-insight.txt`. Known follow-up (not a blocker): no
  `JavaTimeModule` on the runtime classpath, so `LocalDate` does not round-trip —
  a Jackson 3.x ecosystem question, out of scope for this task.

### Fixed

- **`apps/agentflow-app` test task runs (S01-07).** The Boot 4.1.1 BOM manages JUnit
  to the 6.0.x line and `spring-boot-test` is compiled against the JUnit Platform
  6.0 `Store` API, so forcing the JUnit 5 line broke Spring context startup
  (`NoSuchMethodError`) and the platform discovered zero tests. We keep the
  BOM-managed JUnit 6.0.3 and add `junit-platform-launcher` (also BOM-managed) to
  the test runtime classpath. The `test` task now compiles and runs the
  `AgentflowApplicationTests` context-load test. `failOnNoDiscoveredTests = true`
  makes a future zero-test discovery fail the build instead of passing silently.
