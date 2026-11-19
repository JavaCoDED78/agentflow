package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.RateLimit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the {@code S04-16} {@code agentflow.ratelimit.*} group binds like the other typed config
 * groups (S01-17): the safe defaults bind, custom values override them, and a nonsensical value
 * fails startup through the real {@code @Validated} + {@code @ConfigurationProperties} binding path
 * (the same pattern {@link UploadPropertiesTest} uses). DB-free — an {@link
 * ApplicationContextRunner} exercises the configuration-to-value path; no database, Kafka or Redis
 * is required.
 */
class RateLimitPropertiesTest {

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
  void binds_the_safe_defaults() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          RateLimit rateLimit = context.getBean(AgentFlowProperties.class).getRateLimit();
          assertThat(rateLimit.isEnabled()).isTrue();
          assertThat(rateLimit.getLimits().getCreateRun().getMaxPerMinute()).isEqualTo(60);
          assertThat(rateLimit.getLimits().getUpload().getMaxPerMinute()).isEqualTo(30);
          assertThat(rateLimit.getLimits().getSearchDebug().getMaxPerMinute()).isEqualTo(120);
          assertThat(rateLimit.getLimits().getOps().getMaxPerMinute()).isEqualTo(300);
        });
  }

  @Test
  void custom_limits_bind_from_agentflow_ratelimit() {
    runner
        .withPropertyValues(
            "agentflow.ratelimit.enabled=false",
            "agentflow.ratelimit.limits.create-run.max-per-minute=15",
            "agentflow.ratelimit.limits.upload.max-per-minute=5",
            "agentflow.ratelimit.limits.search-debug.max-per-minute=200",
            "agentflow.ratelimit.limits.ops.max-per-minute=500")
        .run(
            context -> {
              RateLimit rateLimit = context.getBean(AgentFlowProperties.class).getRateLimit();
              assertThat(rateLimit.isEnabled()).isFalse();
              assertThat(rateLimit.getLimits().getCreateRun().getMaxPerMinute()).isEqualTo(15);
              assertThat(rateLimit.getLimits().getUpload().getMaxPerMinute()).isEqualTo(5);
              assertThat(rateLimit.getLimits().getSearchDebug().getMaxPerMinute()).isEqualTo(200);
              assertThat(rateLimit.getLimits().getOps().getMaxPerMinute()).isEqualTo(500);
            });
  }

  @Test
  void an_invalid_limit_fails_startup_through_the_validated_binding() {
    runner
        .withPropertyValues("agentflow.ratelimit.limits.create-run.max-per-minute=-1")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }
}
