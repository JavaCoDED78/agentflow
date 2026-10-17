// Root settings for the AgentFlow modular monolith (S01-04).
//
// Modules are added to the `include(...)` list incrementally as they are
// created (see DEVELOPMENT_PLAN.md, Sprint 1).
//
// Plugin versions, dependency versions and repositories are centralized in the
// version catalog gradle/libs.versions.toml (S01-06). Modules reference them via
// the `libs` accessor (for example `plugins { libs.plugins.spring.boot }`)
// instead of pinning versions inline, so the catalog stays the single source of
// truth and one library cannot drift across build files.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "agentflow"

// Subprojects are listed explicitly (rather than relying on Gradle's automatic
// subdirectory discovery) so the set of artifacts is obvious and stable as the
// monolith grows. Add each new module here, for example
// include("apps:agentflow-app") (S01-07).
include("apps:agentflow-app")
