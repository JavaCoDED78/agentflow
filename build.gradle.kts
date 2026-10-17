// Root build for the AgentFlow modular monolith (S01-04).
//
// This is the build entry point. It stays minimal in Sprint 1:
//  - the Gradle toolchain (Java 25) is pinned in S01-05;
//  - versions live in the version catalog gradle/libs.versions.toml (S01-06);
//  - the Spring Boot application artifact is wired up in S01-07.
//
// Common per-module configuration (repositories, shared plugin options) is
// added here as modules are introduced. See DEVELOPMENT_PLAN.md, Sprint 1.

import java.security.MessageDigest

import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

import com.diffplug.gradle.spotless.SpotlessExtension

// Shared Java configuration for every subproject (S01-05).
//
// Pin the JDK 25 toolchain, force UTF-8 source encoding and a fixed compile
// release so the build never silently falls back to the host/system JDK. The
// configuration is applied to all subprojects now, so it is inherited by the
// Spring Boot artifact introduced in S01-07.

// Spotless formatter (S01-10) is declared in the root so every subproject
// inherits the plugin classpath; the formatting rules live in the subprojects
// block below (one formatter for the whole monolith).
plugins {
    id("com.diffplug.spotless") version "8.10.4"
}

subprojects {
    apply(plugin = "java")

    // Dependency locking (S01-09): pin every resolved configuration to the
    // committed gradle.lockfile so a build reproduces the same module versions
    // across machines. See docs/architecture/dependency-management.md.
    dependencyLocking {
        lockAllConfigurations()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    // Spotless formatter (S01-10): one Java formatter (Google Java Format) for
    // every subproject. The task `spotlessCheck` is wired into `check` by the
    // plugin, so a formatting violation fails the build. The target is every
    // Java source under `src/`; the build scripts and other DSLs are out of
    // scope for this Java-focused gate. googleJavaFormat() enforces Google Java
    // style: 2-space indentation, braces, spacing and import order (java/javax,
    // then third-party, then static last); combined with .editorconfig (LF, no
    // trailing whitespace, final newline) it standardizes indentation, imports,
    // whitespace and line endings.
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<SpotlessExtension> {
        java {
            googleJavaFormat()
        }
    }

    // The wrapper checksum is part of the verification gate so a tampered or
    // wrong gradle-wrapper.jar fails ./gradlew check on any machine.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(":verifyWrapper")
    }
}

// ---------------------------------------------------------------------------
// Gradle wrapper integrity (S01-09)
//
// The wrapper is the only supported build entry point, so its jar must be
// verifiable. The committed gradle-wrapper.jar is byte-identical across Gradle
// releases; we pin and verify its SHA-256 so a checkout on another machine uses
// the exact same jar. The expected hash lives in
// gradle/wrapper/gradle-wrapper.sha256 and is updated with
// './gradlew updateWrapperChecksum' whenever the wrapper version changes.
// ---------------------------------------------------------------------------

val wrapperJar = file("gradle/wrapper/gradle-wrapper.jar")
val checksumFile = file("gradle/wrapper/gradle-wrapper.sha256")

private fun sha256Hex(target: java.io.File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(target.readBytes())
        .joinToString("") { "%02x".format(it) }

tasks.register("updateWrapperChecksum") {
    group = "verification"
    description = "Writes the SHA-256 of gradle-wrapper.jar to gradle/wrapper/gradle-wrapper.sha256. Run it after changing the wrapper version and commit the new hash together with the jar."
    doLast {
        val actual = sha256Hex(wrapperJar)
        checksumFile.writeText(
            "# SHA-256 of gradle-wrapper.jar. Regenerate with './gradlew updateWrapperChecksum' after changing the wrapper version (S01-09).\n" +
                "gradle-wrapper.jar.sha256=$actual\n",
        )
        println("Wrote gradle-wrapper.jar SHA-256 to ${checksumFile.path}: $actual")
    }
}

tasks.register("verifyWrapper") {
    group = "verification"
    description = "Verifies the committed gradle-wrapper.jar against its pinned SHA-256 for reproducible builds."
    val expected = provider {
        if (!checksumFile.exists()) {
            throw GradleException("Expected wrapper checksum file ${checksumFile.path} is missing. Generate it with './gradlew updateWrapperChecksum'.")
        }
        val line = checksumFile.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("gradle-wrapper.jar") }
            ?: throw GradleException("No 'gradle-wrapper.jar' entry found in ${checksumFile.path}.")
        line.substringAfter('=').lowercase()
    }
    doLast {
        val actual = sha256Hex(wrapperJar)
        if (actual != expected.get()) {
            throw GradleException(
                "gradle-wrapper.jar checksum mismatch: expected ${expected.get()} but computed $actual.\n" +
                    "If the wrapper version changed, run './gradlew updateWrapperChecksum', commit the new hash with the jar, then retry.",
            )
        }
        println("gradle-wrapper.jar checksum OK ($actual)")
    }
}

// Generate or update the dependency lock files (S01-09).
//
// The `dependencies` task does not reliably write locks for every subproject
// configuration (Gradle issue #9373), so we resolve every resolvable
// configuration explicitly. Run it with --write-locks and commit the resulting
// gradle.lockfile files (one per project):
//
//   ./gradlew generateLockFiles --write-locks
tasks.register("generateLockFiles") {
    group = "help"
    description = "Generates (or updates) the dependency lock file for every resolvable configuration across all projects. Run with --write-locks and commit the resulting gradle.lockfile files."
    doLast {
        // Resolve the build classpaths of every project so --write-locks pins
        // every transitive version. The artifact-metadata configurations
        // (apiElements / runtimeElements) are not resolvable, so resolve the
        // classpath configurations explicitly. Add any configuration that
        // ./gradlew check later reports as missing a lock entry.
        allprojects {
            val classpaths = listOf(
                "compileClasspath",
                "runtimeClasspath",
                "testCompileClasspath",
                "testRuntimeClasspath",
            )
            configurations
                .filter { it.name in classpaths }
                .forEach { it.resolve() }
        }
    }
}
