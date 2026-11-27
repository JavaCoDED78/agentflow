package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.Storage;
import com.example.agentflow.config.StorageClientConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Proves the {@code S07-06A} acceptance through the real {@code @ConditionalOnProperty} +
 * {@code @ConfigurationProperties} startup path, DB-free:
 *
 * <ul>
 *   <li>disabled by default &rarr; the context starts with no S3 client bean (the app boots and
 *       passes readiness with no endpoint and no credentials);
 *   <li>enabled with a complete environment &rarr; the context starts and the {@link S3Client} bean
 *       exists, is a single Spring singleton (the app never creates a client per request) and its
 *       {@code toString} never echoes the credentials;
 *   <li>enabled with a missing endpoint / access key / secret key &rarr; startup fails with a clear
 *       message that names the offending setting — only for the enabled storage profile/role;
 *   <li>the startup log line never carries a credential (the canary is planted only in the
 *       sanctioned fixture);
 *   <li>the typed config carries no credential field in its class hierarchy.
 * </ul>
 *
 * <p>DB-free: no {@code DataSource} / S3 dependency, so an {@link ApplicationContextRunner}
 * exercises the same real binding path as the properties tests.
 */
class StorageClientConfigurationTest {

  private static final String CANARY_RESOURCE = "/secrets/canary-storage-access-key.txt";
  private static final String CANARY_FIXTURE = "canary-storage-access-key.txt";
  private static final long MAX_SCANNED_BYTES = 2L * 1024 * 1024;

  private static final String[] REQUIRED_PROPS = {
    "agentflow.app-name=agentflow-app",
    "agentflow.security.issuer-uri=https://keycloak.test/realms/agentflow",
    "agentflow.security.audience=agentflow-api",
  };

  private String canary() {
    try (InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
  }

  private ApplicationContextRunner runner(String... extraProperties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
        .withUserConfiguration(AgentFlowStartupTestConfig.class, StorageClientConfiguration.class)
        .withPropertyValues(REQUIRED_PROPS)
        .withPropertyValues(extraProperties);
  }

  @Test
  void disabled_by_default_starts_with_no_s3_client() {
    runner()
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeanNamesForType(S3Client.class)).isEmpty();
            });
  }

  @Test
  void enabled_with_complete_env_starts_and_creates_a_singleton_client() {
    String canary = canary();
    runner(
            "agentflow.storage.enabled=true",
            "agentflow.storage.endpoint=http://garage.test:3900",
            "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY=" + canary,
            "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY=" + canary)
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              S3Client first = context.getBean(S3Client.class);
              S3Client second = context.getBean(S3Client.class);
              assertThat(first).as("the S3 client must be created").isNotNull();
              assertThat(second)
                  .as("the client must be a singleton, never per request")
                  .isSameAs(first);
              assertThat(first.toString())
                  .as("the client toString must never echo the canary credential")
                  .doesNotContain(canary);
            });
  }

  @Test
  void enabled_without_endpoint_fails_startup_with_a_clear_message() {
    runner(
            "agentflow.storage.enabled=true",
            "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY=access-key-stand-in",
            "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY=secret-key-stand-in")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .as("missing endpoint must fail startup")
                    .hasMessageContaining("agentflow.storage.endpoint"));
  }

  @Test
  void enabled_without_access_key_fails_startup_with_a_clear_message() {
    runner(
            "agentflow.storage.enabled=true",
            "agentflow.storage.endpoint=http://garage.test:3900",
            "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY=secret-key-stand-in")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .as("missing access key must fail startup")
                    .hasMessageContaining("AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY"));
  }

  @Test
  void enabled_without_secret_key_fails_startup_with_a_clear_message() {
    runner(
            "agentflow.storage.enabled=true",
            "agentflow.storage.endpoint=http://garage.test:3900",
            "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY=access-key-stand-in")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .as("missing secret key must fail startup")
                    .hasMessageContaining("AGENTFLOW_OBJECT_STORAGE_SECRET_KEY"));
  }

  @Test
  void no_credential_ever_reaches_the_startup_log_output() {
    String canary = canary();
    // Capture the exact log line the configuration emits on startup: a canary-as-credential
    // through the production bean-building path must never appear in any emitted message.
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StorageClientConfiguration.class);
    ch.qos.logback.classic.Level originalLevel = logger.getLevel();
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      AgentFlowProperties properties = storageProperties();
      StandardEnvironment env = new StandardEnvironment();
      env.getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "storage-test",
                  Map.of(
                      "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY", canary,
                      "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY", canary)));
      StorageClientConfiguration config = new StorageClientConfiguration();
      S3Client client = config.s3Client(properties, env);
      try {
        List<String> messages =
            appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(messages).as("the startup log must emit at least one line").isNotEmpty();
        assertThat(messages)
            .as("no startup log line may carry the canary credential")
            .noneMatch(message -> message.contains(canary));
      } finally {
        client.close();
      }
    } finally {
      logger.detachAppender(appender);
      if (originalLevel != null) {
        logger.setLevel(originalLevel);
      } else {
        logger.setLevel(null);
      }
    }
  }

  /** The enabled, complete typed config the startup-log test drives through the production path. */
  private static AgentFlowProperties storageProperties() {
    AgentFlowProperties properties = new AgentFlowProperties();
    properties.setAppName("agentflow-app");
    Storage storage = properties.getStorage();
    storage.setEnabled(true);
    storage.setEndpoint("http://garage.test:3900");
    storage.setBucket("agentflow");
    storage.setRegion("garage");
    return properties;
  }

  @Test
  void the_typed_config_hierarchy_carries_no_credential_field() {
    // The typed config structurally cannot hold a credential: no field named access-key /
    // secret-key / password anywhere in the class hierarchy, so a configured credential can
    // never be a bound value, an actuator entry or a toString value (S07-06A acceptance).
    Class<?> type = Storage.class;
    while (type != null && type != Object.class) {
      // publicAccess is a legitimate, non-credential field (the access policy); the check targets
      // credential-shaped names only.
      for (Field field : type.getDeclaredFields()) {
        String name = field.getName().toLowerCase().replace("-", "").replace("_", "");
        assertThat(name)
            .as("agentflow.storage typed config must not carry a credential field")
            .doesNotContain("password")
            .doesNotContain("accesskey")
            .doesNotContain("secretkey")
            .doesNotContain("credential");
      }
      type = type.getSuperclass();
    }
  }

  @Test
  void the_canary_lives_only_in_the_sanctioned_git_fixture() throws Exception {
    String canary = canary();

    List<String> tracked = gitTrackedFiles();
    assertThat(tracked).as("git must list tracked files").isNotEmpty();
    // The sanctioned fixture must be git-tracked: an untracked or missing fixture is a silent
    // gap in the leak guarantee, not a pass.
    assertThat(tracked)
        .as("the canary fixture must be git-tracked")
        .anyMatch(path -> toDisplayPath(path).endsWith("/" + CANARY_FIXTURE));

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
      String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
      assertThat(content)
          .as("the storage canary must not be committed to %s", toDisplayPath(relative))
          .doesNotContain(canary);
    }
    assertThat(trackedScanned).isGreaterThan(0);
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
      assertThat(exit).as("git ls-files exited " + exit).isZero();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while listing git files", exception);
    }
    return lines;
  }
}
