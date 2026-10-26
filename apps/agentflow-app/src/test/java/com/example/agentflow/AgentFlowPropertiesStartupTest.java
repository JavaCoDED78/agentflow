package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the S01-17 acceptance criterion: a missing mandatory typed property fails context startup
 * with a clear message, while a provided one loads normally. This exercises the real
 * {@code @Validated} path through Spring Boot's configuration-properties binding.
 */
class AgentFlowPropertiesStartupTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class));

  @Test
  void missing_required_app_name_fails_startup() {
    // A missing agentflow.app-name makes the @Validated bean fail to bind, so the context
    // fails to start. Assert on the startup failure reported by ApplicationContextRunner.
    runner
        .withUserConfiguration(AgentFlowStartupTestConfig.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }

  @Test
  void provided_app_name_starts() {
    runner
        .withUserConfiguration(AgentFlowStartupTestConfig.class)
        .withPropertyValues(
            "agentflow.app-name=agentflow-app",
            "agentflow.http.port=8080",
            "agentflow.http.host=0.0.0.0",
            "agentflow.security.issuer-uri=https://keycloak.test/realms/agentflow",
            "agentflow.security.audience=agentflow-api")
        .run(
            context -> {
              assertThat(context.getBean(AgentFlowProperties.class)).isNotNull();
            });
  }
}
