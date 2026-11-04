package com.example.agentflow.config;

import com.example.agentflow.workflowexecution.domain.RetryPolicy;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker runtime wiring (S07, durable execution).
 *
 * <p>Houses the beans the {@code workflow-worker} role needs that are plain value objects or
 * configuration mappings rather than Spring components. Each bean is intentionally free of {@link
 * org.springframework.jdbc.core.JdbcTemplate}/{@code Kafka}/{@code DataSource} so it is safe in
 * every profile, including the {@code api} role and the DB-less skeleton (S01-02).
 */
@Configuration
public class WorkflowWorkerConfiguration {

  /**
   * The durable-execution retry policy (S07-10), bound from {@code agentflow.worker.retry.*}
   * ({@link AgentFlowProperties.Worker.Retry}). It is a pure, immutable value object with value
   * equality, exposed as a bean so the step executor ({@code S07-11}) and the cooperative-shutdown
   * path ({@code S07-13}) share one policy instance. A misconfigured value fails boot here, at bean
   * creation, through {@link RetryPolicy#of} — before any step runs.
   *
   * @param properties the validated worker configuration (retry tuning lives here, not in code)
   * @return the retry policy built from {@code agentflow.worker.retry.*}
   */
  @Bean
  public RetryPolicy retryPolicy(AgentFlowProperties properties) {
    AgentFlowProperties.Worker.Retry retry = properties.getWorker().getRetry();
    return RetryPolicy.of(
        retry.getMaxAttempts(),
        Duration.ofSeconds(retry.getBaseDelaySeconds()),
        retry.getMultiplier(),
        Duration.ofSeconds(retry.getMaxDelaySeconds()),
        retry.isJitter());
  }
}
