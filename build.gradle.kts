// Root build for the AgentFlow modular monolith (S01-04).
//
// This is the build entry point. It intentionally stays minimal in Sprint 1:
//  - the Gradle toolchain (Java 25) is pinned in S01-05;
//  - versions live in the version catalog gradle/libs.versions.toml (S01-06);
//  - the Spring Boot application artifact is wired up in S01-07.
//
// Common per-module configuration (repositories, shared plugin options) is
// added here as modules are introduced. See DEVELOPMENT_PLAN.md, Sprint 1.
