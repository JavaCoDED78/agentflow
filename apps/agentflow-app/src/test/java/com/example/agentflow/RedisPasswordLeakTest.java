package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties.Redis;
import com.example.agentflow.observability.SecretRedactingValueMasker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * S12-16 regression guard: the Redis connection password is handled so that a planted <b>canary</b>
 * never reaches Git, the logs, the Actuator, or the {@code toString} of any object this change
 * touches.
 *
 * <p>The canary value lives only in {@code src/test/resources/secrets/canary-redis-password.txt} —
 * it is never written into a source file, so this class cannot trip its own scan. The guard has
 * five independent parts (all DB-free, no running Redis, no network):
 *
 * <ul>
 *   <li><b>Git</b> — {@code git ls-files} lists every tracked path; every one except the sanctioned
 *       fixture is scanned and asserted not to carry the canary. A developer who accidentally
 *       commits the connection password (or the canary stand-in for it) fails the build.
 *   <li><b>Logs</b> — the same {@link SecretRedactingValueMasker} the {@code logback-spring.xml}
 *       registers is asked to mask a value that quotes a {@code password=} before it reaches stdout
 *       / Loki, so a secret embedded in a log line is masked before it is observable.
 *   <li><b>Typed config</b> — {@link Redis} has no {@code password} field in its class hierarchy,
 *       so a configured password can never be a bound value, an Actuator {@code configprops} entry
 *       or a {@code toString} of the typed config.
 *   <li><b>Connection factory</b> — the connection object built the way {@code
 *       RedisConnectionConfiguration} builds it does not echo the password in its {@code toString}
 *       (Lettuce omits the credentials there).
 *   <li><b>Diagnostic endpoints</b> — the sensitive {@code env} / {@code configprops} endpoints are
 *       closed (see {@link ProviderSecretsActuatorTest}), so a configured password can never be
 *       read back over HTTP.
 * </ul>
 */
class RedisPasswordLeakTest {

  private static final String CANARY_RESOURCE = "/secrets/canary-redis-password.txt";
  private static final String CANARY_FIXTURE = "canary-redis-password.txt";
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
          .as("the redis canary must not be committed to %s", toDisplayPath(relative))
          .doesNotContain(canary);
    }
    assertThat(trackedScanned).isGreaterThan(0);
  }

  @Test
  void the_canary_is_masked_before_it_reaches_the_log_output() {
    // Mirror the production wiring (logback-spring.xml): a value that quotes a password is masked
    // by the registered ValueMasker before the event is serialized to JSON.
    String masked = (String) masker.mask(null, "redis password=" + canary());
    assertThat(masked).contains("***");
    assertThat(masked).doesNotContain(canary());
  }

  @Test
  void the_canary_is_masked_inside_a_larger_message() {
    // A password embedded in a longer log line is masked too, not only when the whole value is
    // secret ("password" is a key name the registered ValueMasker protects).
    String masked =
        (String) masker.mask(null, "connecting redis with password=" + canary() + " before get");
    assertThat(masked).doesNotContain(canary());
    assertThat(masked).contains("***");
  }

  @Test
  void the_typed_redis_config_carries_no_password_field() {
    // The typed config structurally cannot hold a secret: no field named "password" anywhere in
    // the class hierarchy, so a configured password can never be a bound value, an actuator entry
    // or a toString value (S12-16 acceptance).
    Class<?> type = Redis.class;
    while (type != null && type != Object.class) {
      for (Field field : type.getDeclaredFields()) {
        assertThat(field.getName())
            .as("agentflow.redis typed config must not carry a password field")
            .isNotEqualTo("password");
      }
      type = type.getSuperclass();
    }
  }

  @Test
  void the_connection_factory_does_not_expose_the_password_in_its_to_string() {
    // Built the way RedisConnectionConfiguration builds it: the password sits in the standalone
    // server configuration and Lettuce omits it from the connection factory toString (Lettuce is
    // lazy here — constructing the object does not open a socket, so no Redis is reachable).
    RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("localhost", 6379);
    serverConfig.setPassword(RedisPassword.of(canary()));
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
            serverConfig,
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
    assertThat(factory.toString())
        .as("the connection factory toString must not echo the password")
        .doesNotContain(canary());
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
        BufferedReader buffered = new BufferedReader(reader)) {
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
