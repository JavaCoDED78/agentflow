package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.ToolExecutionConfiguration;
import com.example.agentflow.toolexecution.application.BoundedToolExecutor;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Proves the S08-10 Spring wiring: the {@code agentflow.tools.executor.*} caps bind from
 * configuration (with their validated defaults), a non-positive cap fails startup through the real
 * {@code @Validated} binding path, and the {@link ToolExecutionConfiguration} beans build a
 * functional {@link BoundedToolExecutor}. DB-free: the executor wiring has no {@code DataSource} /
 * Kafka dependency (S01-02), so an {@link ApplicationContextRunner} exercises it without
 * Testcontainers — the behaviour of the caps themselves is covered by the unit suite ({@code
 * BoundedToolExecutorTest}); this test covers the layer the unit suite cannot reach: the
 * configuration-to-bean path.
 */
class ToolExecutionConfigurationTest {

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
                  org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
                      .class))
          .withUserConfiguration(AgentFlowStartupTestConfig.class, ToolExecutionConfiguration.class)
          .withPropertyValues(REQUIRED_PROPS);

  @Test
  void executorBeansAreCreatedWithTheValidatedDefaults() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          AgentFlowProperties.Tools.Executor executor =
              context.getBean(AgentFlowProperties.class).getTools().getExecutor();
          assertThat(executor.getPoolSize()).isEqualTo(8);
          assertThat(executor.getGlobalMaxConcurrency()).isEqualTo(32);
          assertThat(executor.getPerTenantMaxConcurrency()).isEqualTo(8);
          assertThat(context.getBean(ExecutorService.class)).isNotNull();
          assertThat(context.getBean(BoundedToolExecutor.class)).isNotNull();

          // The wired executor is functional: a call within its descriptor's timeout returns.
          assertThat(
                  context
                      .getBean(BoundedToolExecutor.class)
                      .execute(context(), descriptor(), () -> "wired-ok"))
              .isEqualTo("wired-ok");
        });
  }

  @Test
  void customCapsBindFromAgentflowToolsExecutor() {
    runner
        .withPropertyValues(
            "agentflow.tools.executor.pool-size=4",
            "agentflow.tools.executor.global-max-concurrency=16",
            "agentflow.tools.executor.per-tenant-max-concurrency=2")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              AgentFlowProperties.Tools.Executor executor =
                  context.getBean(AgentFlowProperties.class).getTools().getExecutor();
              assertThat(executor.getPoolSize()).isEqualTo(4);
              assertThat(executor.getGlobalMaxConcurrency()).isEqualTo(16);
              assertThat(executor.getPerTenantMaxConcurrency()).isEqualTo(2);
            });
  }

  @Test
  void aNonPositiveCapFailsStartupThroughTheValidatedBinding() {
    runner
        .withPropertyValues("agentflow.tools.executor.global-max-concurrency=0")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }

  private static ToolExecutionContext context() {
    return ToolExecutionContext.of(
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        "alice",
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
        "step-1",
        "corr-1",
        Set.of());
  }

  private static ToolDescriptor descriptor() {
    return ToolDescriptor.of(
        "tool-wired-1",
        "wired-tool",
        "1",
        "A tool used only by the wiring test.",
        JsonSchema.of(schema()),
        JsonSchema.of(schema()),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        Duration.ofSeconds(5),
        RetryPolicy.ofDefaults(),
        false);
  }

  private static Map<String, Object> schema() {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "object");
  }
}
