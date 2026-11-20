package com.example.agentflow.config;

import com.example.agentflow.config.AgentFlowProperties.Redis;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Redis connection wiring (S12-16, ADR-021). The {@link RedisConnectionFactory} bean exists only
 * when {@code agentflow.redis.enabled=true}; when it is {@code false} (the default) no connection
 * bean is created, so the app boots and passes readiness without Redis and the in-memory
 * implementations stand in (the {@code common} rate limiter, S04-17). The exclusion of Spring
 * Boot's own {@code RedisAutoConfiguration} in the base profile (application.yml) makes this class
 * the single creator of the connection, so there is never a second bean pointing at an unconfigured
 * host.
 *
 * <p>The password is never held in the typed config ({@code AgentFlowProperties.Redis} has no
 * password field): it is read from the environment ({@code spring.redis.password}, set from {@code
 * AGENTFLOW_REDIS_PASSWORD} in the local / kubernetes profiles) by this class and handed to the
 * connection directly, so it can never reach the {@code configprops} Actuator, the {@code toString}
 * of the typed config, or the logs (S12-16 acceptance). A missing {@code host} when enabled fails
 * startup with a clear message — the host is the environment-specific connection target and is
 * never a hardcoded default (invariant: no localhost).
 */
@Configuration
@ConditionalOnProperty(prefix = "agentflow.redis", name = "enabled", havingValue = "true")
public class RedisConnectionConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RedisConnectionConfiguration.class);

  /**
   * Build the Redis connection from the validated typed config. The password is read from the
   * environment, never from the typed config, so it is not part of the bound object.
   *
   * @param properties the validated {@code agentflow.redis.*} typed config
   * @param env the environment, from which {@code spring.redis.password} (set from {@code
   *     AGENTFLOW_REDIS_PASSWORD} in the profiles) is read
   * @return the connection factory wired with the bounded timeouts and TLS flag
   * @throws IllegalStateException when {@code host} is missing — it is required when Redis is
   *     enabled and is never a hardcoded default
   */
  @Bean
  public RedisConnectionFactory redisConnectionFactory(
      AgentFlowProperties properties, Environment env) {
    Redis redis = properties.getRedis();

    String host = redis.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalStateException(
          "agentflow.redis.host is required when agentflow.redis.enabled=true "
              + "and must be an environment-specific address (never a hardcoded localhost)");
    }

    int port = redis.getPort() == null ? 6379 : redis.getPort();

    RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(host, port);
    String password = env.getProperty("spring.redis.password");
    if (password != null && !password.isBlank()) {
      serverConfig.setPassword(RedisPassword.of(password));
    }

    log.info(
        "agentflow.redis: connection factory created (host={}, port={}, tls={}, connect-timeout={}s, command-timeout={}s)",
        host,
        port,
        redis.isTls(),
        redis.getConnectTimeoutSeconds(),
        redis.getCommandTimeoutSeconds());

    return new LettuceConnectionFactory(serverConfig, buildClientConfig(redis));
  }

  /**
   * Build the Lettuce client configuration from the validated typed config. The connect timeout is
   * applied via {@link ClientOptions}/{@link TimeoutOptions} and the command timeout via the client
   * configuration builder; both bound how long a call can hang before the caller returns, so a
   * blocked Redis can never pin a virtual thread indefinitely (S12-16: bounded timeouts). TLS is
   * enabled only when {@code agentflow.redis.tls=true}.
   */
  private static LettuceClientConfiguration buildClientConfig(Redis redis) {
    ClientOptions clientOptions =
        ClientOptions.builder()
            .timeoutOptions(
                TimeoutOptions.builder()
                    .fixedTimeout(Duration.ofSeconds(redis.getConnectTimeoutSeconds()))
                    .connectionTimeout()
                    .build())
            .build();

    LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
        LettuceClientConfiguration.builder()
            .clientOptions(clientOptions)
            .commandTimeout(Duration.ofSeconds(redis.getCommandTimeoutSeconds()));

    return redis.isTls() ? builder.useSsl().build() : builder.build();
  }
}
