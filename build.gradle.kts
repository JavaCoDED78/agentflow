// Root build for the AgentFlow modular monolith (S01-04).
//
// This is the build entry point. It stays minimal in Sprint 1:
//  - the Gradle toolchain (Java 25) is pinned in S01-05;
//  - versions live in the version catalog gradle/libs.versions.toml (S01-06);
//  - the Spring Boot application artifact is wired up in S01-07.
//
// Common per-module configuration (repositories, shared plugin options) is
// added here as modules are introduced. See DEVELOPMENT_PLAN.md, Sprint 1.

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

// Shared Java configuration for every subproject (S01-05).
//
// Pin the JDK 25 toolchain, force UTF-8 source encoding and a fixed compile
// release so the build never silently falls back to the host/system JDK. The
// configuration is applied to all subprojects now, so it is inherited by the
// Spring Boot artifact introduced in S01-07.
subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}
