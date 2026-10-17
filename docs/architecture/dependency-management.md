# Dependency management

<!-- Owner: build (S01-06, S01-08). One source of truth — do not duplicate versions. -->

This document records how versions are chosen and verified for the AgentFlow
build. It is the counterpart to the version catalog
(`gradle/libs.versions.toml`, task `S01-06`) and the dependency insight report
(`docs/architecture/dependency-insight.txt`, task `S01-08`).

## Policy

- **One version per dependency.** All plugin and dependency versions live in the
  version catalog (`gradle/libs.versions.toml`). Modules reference entries through
  the `libs` accessor; no module pins a version inline.
- **Prefer BOMs for Spring-managed dependencies.** The Spring Boot BOM
  (`spring-boot-dependencies`) and the Spring AI BOM (`spring-ai-bom`) are
  imported with `platform(...)` so their managed transitive versions win. Nothing
  is re-pinned elsewhere.
- **No manual overrides without an ADR.** Do not override `spring-framework`,
  `jackson`, `spring-security`, or any other Spring-managed version on top of the
  BOM unless an architecture decision record (ADR) documents the trade-off. This
  keeps the dependency graph predictable and reproducible.
- **Stable releases only.** No `latest`, `snapshot`, milestone, or deprecated
  API without a documented reason. Versions change only through a PR.
- **Verify, do not assume.** A catalog entry that parses is not proven. Every
  meaningful change is verified with a real `dependencies --configuration`
  resolution plus a build/test, and the resulting graph is saved. See the
  verification procedure below.

## Verified matrix (Sprint 1, `S01-08`)

Verified against the published artifacts (Gradle `dependencies` resolution):

| Artifact | Version | Managed by | Notes |
|---|---|---|---|
| `org.springframework.boot:spring-boot-dependencies` | `4.1.1` | Spring Boot BOM | |
| `org.springframework.ai:spring-ai-bom` | `2.0.1` | Spring AI BOM | Added in `S01-08`; Boot-compatible release train |
| `org.springframework` (core/context/beans/aop/web/webmvc/expression) | `7.0.9` | Boot BOM | Uniform across the tree — no conflict |
| `tools.jackson.core` (databind/core) | `3.1.5` | Spring AI BOM | Jackson 3.x group |
| `com.fasterxml.jackson.core:jackson-annotations` | `2.21` | Boot BOM | Retained for binary compatibility |
| `org.apache.tomcat.embed` | `11.0.24` | Boot BOM | |
| `org.yaml:snakeyaml` | `2.6` | Boot BOM | |
| `jakarta.annotation:jakarta.annotation-api` | `3.0.0` | Boot BOM | |

Spring AI 2.0.1 adds version constraints only for the artifacts it manages
(notably Jackson 3.x). It does not pin Spring Framework, so the Boot 4.1.1 BOM
keeps Spring Framework uniformly at `7.0.9`. The `(c)` lines in the insight
report are BOM constraints, not duplicate versions.

## Known follow-up (not a blocker)

No `JavaTimeModule` is on the runtime classpath, so `LocalDate` / `LocalDateTime`
do not round-trip through the Boot-configured `ObjectMapper`. This is a Jackson 3.x
ecosystem question (module availability and Boot auto-detection), **not** a Spring
BOM incompatibility. Before persisting or serializing 8.x time values, add the
appropriate Jackson 3.x time module (if/when it exists and is Boot-detectable) and
register it. Do not add without verifying the module exists. See
`docs/architecture/dependency-insight.txt`.

## Verification procedure

```bash
# 1. Toolchain + build entry point
./gradlew --version            # confirms the pinned JVM (Java 25) and Gradle 9.x
./gradlew help                 # parses root build, configures subprojects

# 2. Resolve and inspect the dependency graph for both classpaths
./gradlew :apps:agentflow-app:dependencies --configuration compileClasspath --console=plain
./gradlew :apps:agentflow-app:dependencies --configuration runtimeClasspath --console=plain

# 3. Exercise the managed graph at runtime (compile + unit tests)
./gradlew :apps:agentflow-app:test --rerun-tasks
./gradlew :apps:agentflow-app:check
```

When resolving, confirm:

- every Spring artifact converges to a single version (no forced
  `x -> y` overrides),
- transitive versions are explainable from the two BOMs, and
- the graph is re-captured and saved whenever it changes.
