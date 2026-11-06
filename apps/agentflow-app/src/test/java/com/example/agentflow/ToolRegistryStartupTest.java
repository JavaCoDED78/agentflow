package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.ToolRegistryConfiguration;
import com.example.agentflow.toolexecution.application.BuiltInTools;
import com.example.agentflow.toolexecution.application.DuplicateToolRegistrationException;
import com.example.agentflow.toolexecution.application.ToolRegistry;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the S08-02 startup behavior with a lightweight Spring context (no full server, no
 * database, no Kafka):
 *
 * <ul>
 *   <li>the real {@link ToolRegistryConfiguration} starts the context and exposes a registry seeded
 *       with the built-in tools (so the platform registers its tools at startup);
 *   <li>a duplicate registration thrown from the {@code toolRegistry} {@code @Bean} factory method
 *       fails the context to start with {@link DuplicateToolRegistrationException} (so a duplicate
 *       registration <em>clearly breaks startup</em>).
 * </ul>
 */
class ToolRegistryStartupTest {

  // A bare runner: by default the ApplicationContextRunner applies no autoconfiguration, so this
  // context contains only the tool-registry bean and nothing web/security/DB (no external deps).
  private final ApplicationContextRunner runner = new ApplicationContextRunner();

  @Test
  void the_registry_is_seeded_with_the_built_in_tools_at_startup() {
    runner
        .withUserConfiguration(ToolRegistryConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ToolRegistry registry = context.getBean(ToolRegistry.class);
              assertThat(registry).isNotNull();
              assertThat(registry.size()).isGreaterThanOrEqualTo(2);
              // The built-in tools resolve; an unknown tool does not (unknown -> never called).
              assertThat(registry.resolve("calculator", "1")).isPresent();
              assertThat(registry.resolve("report", "1")).isPresent();
              assertThat(registry.resolve("ticket", "1")).isEmpty();
            });
  }

  @Test
  void a_duplicate_registration_fails_context_startup() {
    // The factory-method exception is wrapped by Spring's bean creation; the root cause is the
    // registration rejection, which is what breaks the startup. Assert inside the run() callback
    // so the failure is captured from the failed context.
    runner
        .withUserConfiguration(DuplicateRegistryConfiguration.class)
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .as("the duplicate registration surfaces as a startup failure")
                    .hasRootCauseInstanceOf(DuplicateToolRegistrationException.class));
  }

  /**
   * A test-only configuration that registers the same tool twice from its {@code @Bean} factory
   * method, so the second {@link ToolRegistry#register(ToolDescriptor)} throws and the context
   * fails to start. This is the "duplicate registration breaks startup" acceptance path.
   */
  @Configuration
  static class DuplicateRegistryConfiguration {

    @Bean
    ToolRegistry toolRegistry() {
      ToolRegistry registry = new ToolRegistry();
      ToolDescriptor calculator = BuiltInTools.calculator();
      registry.register(calculator);
      registry.register(
          calculator); // duplicate (name, version) -> DuplicateToolRegistrationException
      return registry;
    }
  }
}
