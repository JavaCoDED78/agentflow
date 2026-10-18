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

- **Java formatter / static check (`S01-10`).** Java sources are standardized with
  one formatter — Google Java Format, applied through the Spotless Gradle plugin
  (`com.diffplug.spotless:8.10.4`), pinned in the root `plugins {}` block. The
  `spotlessCheck` task is part of `./gradlew check`, so a formatting violation
  fails the build; `./gradlew spotlessApply` reformats every Java source under
  `src/`. Line endings, trailing whitespace and final newlines follow
  `.editorconfig` (LF). Existing skeleton files were reformatted to the standard.

- **Repository secret hygiene (`S01-11`).** Real credentials and local env files
  are not tracked. `.gitignore` excludes `.env`/`.env.*` (only `!.env.example`
  is committed), Java keytool artifacts (`*.keystore`, `*.p12`, `*.jks`) and
  local Compose data volumes / `docker-compose*.override.yml`. `.editorconfig`
  already enforces LF endings, UTF-8, trailing-whitespace trimming and final
  newlines. A `.env.example` template with `CHANGEME` placeholders and localhost
  defaults is committed for local use (real secrets stay in the environment or a
  secret store). A scan of every text blob in the commit history (43 files)
  against AWS / GitHub / Google / Stripe / Slack key patterns, PEM private keys
  key`/`token`/`password` assignments found no secret-like values.

- **PostgreSQL Compose service (`S01-12`).** `infra/compose/docker-compose.yml`
  starts the core profile's PostgreSQL on the pinned image
  `pgvector/pgvector:0.8.6-pg18` (pgvector 0.8.6 on PostgreSQL 18 — the same
  pgvector image Testcontainers uses). It exposes a named volume
  (`agentflow-pgdata`), a healthcheck (`pg_isready`), a `core` profile so the
  minimum stack starts without the other services, and env-driven config with
  local-only defaults (`POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB`/
  `POSTGRES_PORT`) overridable through a git-ignored `.env`. Note: PostgreSQL 18
  requires the data mount at `/var/lib/postgresql` (not
  `/var/lib/postgresql/data`); see the compose file comment. Verified: `docker
  compose config` passes, the service reaches `healthy`, and test data plus the
  `vector` extension survive a container restart through the named volume.

### Fixed

- **`apps/agentflow-app` test task runs (S01-07).** The Boot 4.1.1 BOM manages JUnit
  to the 6.0.x line and `spring-boot-test` is compiled against the JUnit Platform
  6.0 `Store` API, so forcing the JUnit 5 line broke Spring context startup
  (`NoSuchMethodError`) and the platform discovered zero tests. We keep the
  BOM-managed JUnit 6.0.3 and add `junit-platform-launcher` (also BOM-managed) to
  the test runtime classpath. The `test` task now compiles and runs the
  `AgentflowApplicationTests` context-load test. `failOnNoDiscoveredTests = true`
  makes a future zero-test discovery fail the build instead of passing silently.
