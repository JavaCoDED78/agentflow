package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.Retrieval;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the {@code S10-14} config acceptance through the real {@code @Validated} +
 * {@code @ConfigurationProperties} binding path: the {@code agentflow.retrieval.*} group binds with
 * its safe defaults, custom values override them, and an invalid value fails startup. DB-free: an
 * {@link ApplicationContextRunner} exercises the configuration-to-value path (the same pattern
 * {@link UploadPropertiesTest} uses).
 */
class RetrievalPropertiesTest {

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
  void the_retrieval_group_binds_with_the_safe_defaults() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          Retrieval retrieval = context.getBean(AgentFlowProperties.class).getRetrieval();
          assertThat(retrieval.getDefaultTopK()).isEqualTo(10);
          assertThat(retrieval.getMaxTopK()).isEqualTo(50);
          assertThat(retrieval.getThreshold()).isEqualTo(0.5);
        });
  }

  @Test
  void custom_retrieval_bounds_bind_from_agentflow_retrieval() {
    runner
        .withPropertyValues(
            "agentflow.retrieval.default-top-k=5",
            "agentflow.retrieval.max-top-k=20",
            "agentflow.retrieval.threshold=0.35")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              Retrieval retrieval = context.getBean(AgentFlowProperties.class).getRetrieval();
              assertThat(retrieval.getDefaultTopK()).isEqualTo(5);
              assertThat(retrieval.getMaxTopK()).isEqualTo(20);
              assertThat(retrieval.getThreshold()).isEqualTo(0.35);
            });
  }

  @Test
  void a_threshold_outside_the_cosine_distance_range_fails_startup() {
    runner
        .withPropertyValues("agentflow.retrieval.threshold=2.5")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }

  @Test
  void a_max_top_k_below_one_fails_startup() {
    runner
        .withPropertyValues("agentflow.retrieval.max-top-k=0")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }
}
