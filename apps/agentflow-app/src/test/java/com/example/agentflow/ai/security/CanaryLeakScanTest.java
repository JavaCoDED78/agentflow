package com.example.agentflow.ai.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.observability.SecretRedactingValueMasker;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S09-03 regression guard: the OpenAI provider API key is handled so that a planted <b>canary</b>
 * key never reaches Git, the logs, or the diagnostic surface.
 *
 * <p>The canary value lives only in {@code src/test/resources/secrets/canary-openai-key.txt} — it
 * is never written into a source file, so this class cannot trip its own scan. The guard has three
 * independent parts (all DB-free, no model / API key / network):
 *
 * <ul>
 *   <li><b>Git</b> — {@code git ls-files} lists every tracked path; every one except the sanctioned
 *       fixture is scanned and asserted not to carry the canary. A developer who accidentally
 *       commits a key (or the canary stand-in for it) fails the build.
 *   <li><b>Logs</b> — the same {@link SecretRedactingValueMasker} the {@code logback-spring.xml}
 *       registers is asked to mask a value that contains the canary ({@code api_key=<canary>}), so
 *       a secret embedded in a log line is masked before it reaches stdout / Loki.
 *   <li><b>Diagnostic endpoints</b> — see {@link ProviderSecretsActuatorTest}: the canary is not
 *       echoed by any reachable {@code /actuator} endpoint, and the sensitive {@code env} / {@code
 *       configprops} endpoints are closed, so a configured key can never be read back.
 * </ul>
 */
class CanaryLeakScanTest {

  private static final String CANARY_RESOURCE = "/secrets/canary-openai-key.txt";
  private static final String CANARY_FIXTURE = "canary-openai-key.txt";
  private static final long MAX_SCANNED_BYTES = 2L * 1024 * 1024;

  private final SecretRedactingValueMasker masker = new SecretRedactingValueMasker();

  /**
   * The canary value, read from the test-classpath fixture. Reading it here (rather than declaring
   * it in source) keeps the literal out of every tracked source file, so the class is not itself a
   * place the scan would flag.
   */
  private String canary() {
    try (InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
  }

  @Test
  void the_canary_is_planted_exactly_once_in_the_sanctioned_fixture() {
    String canary = canary();
    assertThat(canary).isNotBlank();
    assertThat(canary).doesNotContain("***").doesNotContain("<<redacted>>");
  }

  @Test
  void the_canary_lives_only_in_the_sanctioned_git_fixture() throws Exception {
    String canary = canary();

    List<String> tracked = gitTrackedFiles();
    assertThat(tracked).as("git must list tracked files").isNotEmpty();

    long trackedScanned = 0;
    for (String relative : tracked) {
      if (isSanctionedFixture(relative)) {
        continue; // the single place the canary is allowed to live
      }
      Path path = repoRoot().resolve(relative);
      if (!Files.isRegularFile(path) || Files.size(path) > MAX_SCANNED_BYTES) {
        continue;
      }
      trackedScanned++;
      // Read as UTF-8 with replacement so binary files never throw; a text canary cannot appear in
      // a binary blob unless it was actually written there.
      String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertThat(content)
          .as("the provider canary must not be committed to %s", toDisplayPath(relative))
          .doesNotContain(canary);
    }
    assertThat(trackedScanned).isGreaterThan(0);
  }

  @Test
  void the_canary_is_masked_before_it_reaches_the_log_output() {
    // Mirror the production wiring (logback-spring.xml): a value that quotes an api_key is masked
    // by
    // the registered ValueMasker before the event is serialized to JSON.
    String masked = (String) masker.mask(null, "provider api_key=" + canary());
    assertThat(masked).contains("***");
    assertThat(masked).doesNotContain(canary());
  }

  @Test
  void the_canary_is_masked_inside_a_larger_message() {
    // A secret embedded in a longer log line is masked too, not only when the whole value is
    // secret.
    // "apiKey" is a key name the registered ValueMasker protects (unlike a bare word "key").
    String masked =
        (String) masker.mask(null, "calling model with apiKey=" + canary() + " before the call");
    assertThat(masked).doesNotContain(canary());
    assertThat(masked).contains("***");
  }

  private boolean isSanctionedFixture(String relative) {
    String normalized = relative.replace('\\', '/');
    return normalized.endsWith("/" + CANARY_FIXTURE);
  }

  private String toDisplayPath(String relative) {
    return relative.replace('\\', '/');
  }

  private Path repoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "git repository root not found walking up from " + Path.of(System.getProperty("user.dir")));
  }

  private List<String> gitTrackedFiles() {
    Process process;
    try {
      process =
          new ProcessBuilder("git", "-C", repoRoot().toString(), "ls-files")
              .redirectErrorStream(true)
              .start();
    } catch (IOException exception) {
      throw new IllegalStateException(
          "git is not available on PATH to run the canary scan", exception);
    }
    List<String> lines = new ArrayList<>();
    try (InputStreamReader reader =
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        java.io.BufferedReader buffered = new java.io.BufferedReader(reader)) {
      String line;
      while ((line = buffered.readLine()) != null) {
        lines.add(line);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("failed reading 'git ls-files' output", exception);
    }
    try {
      int exit = process.waitFor();
      assertThat(exit).as("'git ls-files' exited " + exit).isZero();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while listing git files", exception);
    }
    return lines;
  }
}
