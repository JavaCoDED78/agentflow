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
