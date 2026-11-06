package com.example.agentflow.config;

import com.example.agentflow.toolexecution.application.BuiltInTools;
import com.example.agentflow.toolexecution.application.ToolRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tool registry wiring (S08-02).
 *
 * <p>Exposes the in-memory {@link ToolRegistry} as a Spring bean and seeds it with the built-in
 * tool descriptors (see {@link BuiltInTools}) at context startup. The registry holds descriptors
 * only (metadata); the tool executors are registered separately by name in the tasks that build
 * them (S08-05, S08-06, S08-08), so it owns <em>which</em> tools exist and never executes one.
 *
 * <p>Every other bean the platform exposes as a plain value here — rather than a component — keeps
 * it free of {@code DataSource}/{@code Kafka}, so it is safe in every profile, including the API
 * role and the DB-less skeleton (S01-02): the registry is purely in-memory and needs no external
 * dependency.
 *
 * <p>A duplicate registration inside {@link #toolRegistry()} propagates the {@link
 * com.example.agentflow.toolexecution.application.DuplicateToolRegistrationException} out of the
 * {@code @Bean} factory method, so the context fails to start — a mis-wired registry is a
 * configuration error that must break startup, not silently keep one of two tools.
 */
@Configuration
public class ToolRegistryConfiguration {

  /**
   * Builds the tool registry and registers the built-in descriptors.
   *
   * @return the seeded, in-memory tool registry
   */
  @Bean
  public ToolRegistry toolRegistry() {
    ToolRegistry registry = new ToolRegistry();
    BuiltInTools.builtinDescriptors().forEach(registry::register);
    return registry;
  }
}
