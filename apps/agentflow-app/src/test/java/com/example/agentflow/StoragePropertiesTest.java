package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.Storage;
import com.example.agentflow.config.AgentFlowProperties.Storage.PublicAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the {@code S10-02} storage-config acceptance through the real {@code @Validated} +
 * {@code @ConfigurationProperties} binding path: the {@code agentflow.storage.*} group binds from
 * configuration with its safe defaults, the bucket is <b>private</b> by default (no public URL),
 * and an invalid value fails startup. DB-free: no {@code DataSource} / S3 dependency, so an {@link
 * ApplicationContextRunner} exercises the configuration-to-bean path without Testcontainers (the
 * same pattern {@code ToolExecutionConfigurationTest} uses for the tool caps).
 */
class StoragePropertiesTest {

  private static final String[] REQUIRED_PROPS = {
    "agentflow.app-name=agentflow-app",
    "agentflow.security.issuer-uri=https://keycloak.test/realms/agentflow",
    "agentflow.security.audience=agentflow-api"
  };

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(AgentFlowStartupTestConfig.class)
          .withPropertyValues(REQUIRED_PROPS);

  @Test
  void storage_group_binds_with_the_safe_defaults() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          Storage storage = context.getBean(AgentFlowProperties.class).getStorage();
          assertThat(storage.getBucket()).isEqualTo("agentflow");
          assertThat(storage.getRegion()).isEqualTo("garage");
          // The endpoint is environment-specific and supplied by a profile (never defaulted here).
          assertThat(storage.getEndpoint()).isNull();
        });
  }

  @Test
  void the_bucket_is_private_by_default_so_no_object_has_a_public_url() {
    // The S10-02 acceptance: an object is addressed only through the app, never via a public or
    // anonymous URL. The default access policy is DISABLED, and it can only be changed by an
    // explicit, opt-in configuration value (see {@link
    // #public_access_is_reachable_only_by_opt_in()}).
    runner.run(
        context ->
            assertThat(context.getBean(AgentFlowProperties.class).getStorage().getPublicAccess())
                .isEqualTo(PublicAccess.DISABLED));
  }

  @Test
  void custom_endpoint_bucket_region_bind_from_agentflow_storage() {
    runner
        .withPropertyValues(
            "agentflow.storage.endpoint=http://garage.internal:3900",
            "agentflow.storage.bucket=other-bucket",
            "agentflow.storage.region=eu-west-1")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              Storage storage = context.getBean(AgentFlowProperties.class).getStorage();
              assertThat(storage.getEndpoint()).isEqualTo("http://garage.internal:3900");
              assertThat(storage.getBucket()).isEqualTo("other-bucket");
              assertThat(storage.getRegion()).isEqualTo("eu-west-1");
            });
  }

  @Test
  void public_access_is_reachable_only_by_explicit_opt_in() {
    // PUBLIC exists so an operator can opt in, but it is never the default. Asserting it binds to
    // PUBLIC only when explicitly set proves the platform cannot reach it by accident.
    runner
        .withPropertyValues("agentflow.storage.public-access=public")
        .run(
            context ->
                assertThat(
                        context.getBean(AgentFlowProperties.class).getStorage().getPublicAccess())
                    .isEqualTo(PublicAccess.PUBLIC));
  }

  @Test
  void an_invalid_public_access_value_fails_startup_through_the_validated_binding() {
    runner
        .withPropertyValues("agentflow.storage.public-access=maybe")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }
}
