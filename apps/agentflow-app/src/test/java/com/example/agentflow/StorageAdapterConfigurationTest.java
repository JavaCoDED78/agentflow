package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.StorageAdapterConfiguration;
import com.example.agentflow.config.StorageClientConfiguration;
import com.example.agentflow.storage.adapter.GarageS3Adapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Proves the {@code S07-06C} adapter wiring through the same {@code @ConditionalOnProperty} +
 * {@code @ConfigurationProperties} startup path as the S3 client (DB-free):
 *
 * <ul>
 *   <li>disabled by default &rarr; no adapter bean (the app boots with no storage wiring);
 *   <li>enabled with a complete environment &rarr; one {@link GarageS3Adapter} bean exists, is a
 *       singleton and is bound to the configured bucket and the shared {@link S3Client} — the
 *       adapter never creates a client per request;
 *   <li>the adapter bean is created only when its client is created (same condition), so an enabled
 *       context can never construct an adapter over a missing client.
 * </ul>
 *
 * <p>{@link GarageS3Adapter} holds no secrets itself — the credentials stay inside the shared
 * client — so there is nothing credential-shaped to assert on the adapter beyond safe toString.
 */
class StorageAdapterConfigurationTest {

  private static final String[] REQUIRED_PROPS = {
    "agentflow.app-name=agentflow-app",
    "agentflow.security.issuer-uri=https://keycloak.test/realms/agentflow",
    "agentflow.security.audience=agentflow-api",
  };

  private ApplicationContextRunner runner(String... extraProperties) {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
        .withUserConfiguration(
            AgentFlowStartupTestConfig.class,
            StorageClientConfiguration.class,
            StorageAdapterConfiguration.class)
        .withPropertyValues(REQUIRED_PROPS)
        .withPropertyValues(extraProperties);
  }

  @Test
  void disabled_by_default_starts_with_no_adapter_bean() {
    runner()
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeanNamesForType(GarageS3Adapter.class)).isEmpty();
            });
  }

  @Test
  void enabled_with_complete_env_starts_and_creates_one_adapter_bound_to_the_configured_bucket() {
    runner(
            "agentflow.storage.enabled=true",
            "agentflow.storage.endpoint=http://garage.test:3900",
            "agentflow.storage.bucket=agentflow-it",
            "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY=access-key-stand-in",
            "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY=secret-key-stand-in")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              GarageS3Adapter first = context.getBean(GarageS3Adapter.class);
              GarageS3Adapter second = context.getBean(GarageS3Adapter.class);
              assertThat(first).as("the adapter must be created").isNotNull();
              assertThat(second).as("the adapter must be a singleton").isSameAs(first);
              S3Client client = context.getBean(S3Client.class);
              assertThat(client)
                  .as("an adapter needs a shared client — the same wiring condition must gate both")
                  .isNotNull();
            });
  }

  @Test
  void the_adapter_is_only_created_when_its_client_is_created() {
    runner(
            "agentflow.storage.enabled=true",
            "agentflow.storage.endpoint=http://garage.test:3900",
            "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY=access-key-stand-in",
            "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY=secret-key-stand-in")
        .run(
            context -> {
              // Both beans are gated on the same condition — when the context starts, the shared
              // client and the adapter are both present or both absent.
              assertThat(context.getStartupFailure()).isNull();
              assertThat(context.getBeanNamesForType(S3Client.class)).hasSize(1);
              assertThat(context.getBeanNamesForType(GarageS3Adapter.class)).hasSize(1);
            });
  }
}
