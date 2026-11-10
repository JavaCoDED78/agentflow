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
 * S09-13 regression guard: the value of a sensitive prompt / document field is masked so a planted
 * <b>canary</b> key never reaches the log or the diagnostic surface, even though such a value is
 * untrusted content that spans many words (unlike a single-token API key).
 *
 * <p>The canary value lives only in {@code src/test/resources/secrets/canary-openai-key.txt} — it
 * is never written into a source file, so this class cannot trip its own scan. As in {@link
 * CanaryLeakScanTest} the {@link SecretRedactingValueMasker} that {@code logback-spring.xml}
 * registers is asked to mask a value that contains the canary in several shapes (an {@code api_key}
 * pair, a document body, a sensitive prompt field, the {@code messages} payload), and the safe
 * observability fields are asserted to survive.
 */
class AiObservationRedactionTest {

  private static final String CANARY_RESOURCE = "/secrets/canary-openai-key.txt";

  private final SecretRedactingValueMasker masker = new SecretRedactingValueMasker();

  /** The canary value, read from the test-classpath fixture (never declared in source). */
  private String canary() {
    try (InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
  }

  @Test
  void the_canary_is_masked_from_an_api_key_pair() {
    String masked = (String) masker.mask(null, "provider api_key=" + canary());
    assertThat(masked).contains("***");
    assertThat(masked).doesNotContain(canary());
  }

  @Test
  void the_canary_is_masked_from_the_document_body() {
    // S09-13: the document body is untrusted content, so none of it — the canary included —
    // survives.
    String masked = (String) masker.mask(null, "documentBody=the report begins with " + canary());
    assertThat(masked).contains("***");
    assertThat(masked).startsWith("documentBody=");
    assertThat(masked).doesNotContain(canary());
  }

  @Test
  void the_canary_is_masked_from_sensitive_prompt_fields() {
    // S09-13: a sensitive prompt field is masked to end of value so none of its content survives.
    assertThat((String) masker.mask(null, "systemPrompt=Please " + canary() + " now"))
        .doesNotContain(canary());
    assertThat((String) masker.mask(null, "userContent=scan " + canary() + " here"))
        .doesNotContain(canary());
    String maskedMessages =
        (String)
            masker.mask(
                null, "messages=[{\"role\":\"user\",\"text\":\"found " + canary() + " inside\"}]");
    assertThat(maskedMessages).doesNotContain(canary());
    assertThat(maskedMessages).contains("***");
  }

  @Test
  void the_canary_is_masked_inside_a_larger_message() {
    // A secret embedded in a longer log line is masked too, not only when the whole value is
    // secret.
    String masked =
        (String) masker.mask(null, "calling model with apiKey=" + canary() + " before the call");
    assertThat(masked).doesNotContain(canary());
    assertThat(masked).contains("***");
  }

  @Test
  void the_safe_ai_observability_fields_are_logged() {
    // S09-13: the safe observability surface (model, latency, token count, safe error category) is
    // logged; the canary must not appear there either, and none of these fields is masked.
    String observation = "model=gpt-4o-mini latency=42ms tokens=10 error=TRANSPORT_FAILURE";
    String masked = (String) masker.mask(null, observation);
    assertThat(masked).isEqualTo(observation);
    assertThat(masked).doesNotContain(canary());
  }

  @Test
  void the_canary_lives_only_in_the_sanctioned_git_fixture() throws Exception {
    String canary = canary();
    Path repoRoot = repoRoot();
    List<String> tracked = gitTrackedFiles(repoRoot);
    assertThat(tracked).as("git must list tracked files").isNotEmpty();

    long trackedScanned = 0;
    for (String relative : tracked) {
      if (normalized(relative).endsWith("/canary-openai-key.txt")) {
        continue; // the single place the canary is allowed to live
      }
      Path path = repoRoot.resolve(relative);
      if (!Files.isRegularFile(path) || Files.size(path) > 2L * 1024 * 1024) {
        continue;
      }
      trackedScanned++;
      String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertThat(content)
          .as("the provider canary must not be committed to %s", toDisplayPath(relative))
          .doesNotContain(canary);
    }
    assertThat(trackedScanned).isGreaterThan(0);
  }

  private static String normalized(String relative) {
    return relative.replace('\\', '/');
  }

  private static String toDisplayPath(String relative) {
    return normalized(relative);
  }

  private Path repoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (true) {
      if (Files.isDirectory(current.resolve(".git"))) {
        return current;
      }
      Path parent = current.getParent();
      if (parent == null) {
        throw new IllegalStateException("git repository root not found");
      }
      current = parent;
    }
  }

  private List<String> gitTrackedFiles(Path repoRoot) {
    Process process;
    try {
      process =
          new ProcessBuilder("git", "-C", repoRoot.toString(), "ls-files")
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
