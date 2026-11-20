package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.Redis;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the {@code S12-16} {@code agentflow.redis.*} group binds like the other typed config
 * groups (S01-17): the safe opt-in defaults bind, custom values override them, and a nonsensical
 * value (a timeout below its {@code @Min} floor) fails startup through the real {@code @Validated}
 * + {@code @ConfigurationProperties} binding path (the same pattern {@link RateLimitPropertiesTest}
 * and {@link UploadPropertiesTest} use). DB-free and network-free — an {@link
 * ApplicationContextRunner} exercises the configuration-to-value path; no running Redis is
 * required, and no Redis connection bean is created (that wiring lives in {@code
 * RedisConnectionConfiguration}, gated by {@code agentflow.redis.enabled}).
 */
class RedisPropertiesTest {

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
          Redis redis = context.getBean(AgentFlowProperties.class).getRedis();
          // Opt-in: disabled by default, so no connection bean is created when this binds.
          assertThat(redis.isEnabled()).isFalse();
          assertThat(redis.getHost()).isNull();
          assertThat(redis.getPort()).isEqualTo(6379);
          assertThat(redis.isTls()).isFalse();
          assertThat(redis.getConnectTimeoutSeconds()).isEqualTo(5);
          assertThat(redis.getCommandTimeoutSeconds()).isEqualTo(5);
        });
  }

  @Test
  void custom_values_bind_from_agentflow_redis() {
    runner
        .withPropertyValues(
            "agentflow.redis.enabled=true",
            "agentflow.redis.host=redis.local",
            "agentflow.redis.port=6380",
            "agentflow.redis.tls=true",
            "agentflow.redis.connect-timeout-seconds=10",
            "agentflow.redis.command-timeout-seconds=15")
        .run(
            context -> {
              Redis redis = context.getBean(AgentFlowProperties.class).getRedis();
              assertThat(redis.isEnabled()).isTrue();
              assertThat(redis.getHost()).isEqualTo("redis.local");
              assertThat(redis.getPort()).isEqualTo(6380);
              assertThat(redis.isTls()).isTrue();
              assertThat(redis.getConnectTimeoutSeconds()).isEqualTo(10);
              assertThat(redis.getCommandTimeoutSeconds()).isEqualTo(15);
            });
  }

  @Test
  void an_invalid_timeout_fails_startup_through_the_validated_binding() {
    runner
        .withPropertyValues("agentflow.redis.command-timeout-seconds=0")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("Could not bind properties to"));
  }
}
