package com.example.agentflow.config;

import com.example.agentflow.toolexecution.application.BoundedToolExecutor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tool-execution runtime wiring (S08-10).
 *
 * <p>Houses the beans the bounded tool executor needs that are configuration mappings rather than
 * Spring components. The executor is created in every profile — like the {@code retryPolicy} bean
 * above it is a plain value-bound object with no {@code DataSource}/Kafka dependency, so it is safe
 * in the DB-less skeleton (S01-02) and the {@code api} role; only the workflow-worker role actually
 * drives tool calls through it (S08-12 wires the {@code StepHandler}).
 */
@Configuration
public class ToolExecutionConfiguration {

  /**
   * The fixed thread pool the tool calls run on, sized from {@code agentflow.tools.executor
   * .pool-size}. Bounded on purpose: a burst of slow calls must spawn threads up to a configured
   * limit, never without one (virtual threads are a measured decision, not a default — root
   * AGENTS.md). The pool is a singleton shared by every tenant; the concurrency caps, not the pool
   * size, enforce per-tenant fairness. Shutdown is the Spring destroy method so a context close
   * (including the S07-13 graceful-shutdown drain) stops the pool's threads; {@code shutdown()}
   * lets a submitted call finish instead of aborting it mid-flight.
   *
   * @param properties the validated tool-executor configuration
   * @return the shared, bounded tool-call pool
   */
  @Bean(destroyMethod = "shutdown")
  public ExecutorService toolCallExecutor(AgentFlowProperties properties) {
    return Executors.newFixedThreadPool(properties.getTools().getExecutor().getPoolSize());
  }

  /**
   * The bounded tool executor (S08-10): per-tool timeout plus the global / per-tenant concurrency
   * caps, built from {@code agentflow.tools.executor.*} so the limits are configuration, never
   * magic numbers. A misconfigured cap fails boot at the {@link BoundedToolExecutor} constructor,
   * before any tool runs.
   *
   * @param pool the shared tool-call pool (see {@link #toolCallExecutor})
   * @param properties the validated tool-executor configuration
   * @return the bounded executor the {@code TOOL_CALL} {@code StepHandler} drives calls through
   *     (wired in S08-12)
   */
  @Bean
  public BoundedToolExecutor boundedToolExecutor(
      ExecutorService pool, AgentFlowProperties properties) {
    AgentFlowProperties.Tools.Executor executor = properties.getTools().getExecutor();
    return new BoundedToolExecutor(
        pool, executor.getGlobalMaxConcurrency(), executor.getPerTenantMaxConcurrency());
  }
}
