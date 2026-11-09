package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.config.AgentFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S09-02 / S09-09 configuration test for {@link OpenAiChatModelConfiguration}.
 *
 * <p>Proves two contracts together:
 *
 * <ul>
 *   <li>S09-02 opt-in: the OpenAI beans exist only when {@code agentflow.ai.provider=openai}, and
 *       are absent otherwise — so the core app boots without a provider profile and without any AI
 *       key (the default profile leaves the property unset). {@link ApplicationContextRunner}
 *       starts a bare context (no Boot auto-configuration), so the real bean definitions and the
 *       {@code @ConditionalOnProperty} gate are exercised, and constructing the {@link
 *       OpenAiChatModel} is offline (no network, no reachable provider).
 *   <li>S09-09 resilience wiring: {@code chatModelPort} is the resilient wrapper ({@link
 *       ResilientChatModel}) that wraps the OpenAI adapter ({@link OpenAiChatModelAdapter}), so the
 *       domain sees only the resilient port.
 * </ul>
 */
class OpenAiChatModelConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              OpenAiChatModelConfiguration.class,
              ProviderExceptionClassifier.class,
              AgentFlowProperties.class);

  @Test
  void chat_model_port_is_wired_only_when_the_provider_is_openai() {
    withOpenAiProperties()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("openAiChatModel");
              assertThat(context.getType("openAiChatModel")).isEqualTo(OpenAiChatModel.class);
              assertThat(context).hasBean("openAiChatModelAdapter");
              assertThat(context.getType("openAiChatModelAdapter"))
                  .isEqualTo(OpenAiChatModelAdapter.class);
              assertThat(context).hasBean("chatModelPort");
              // S09-09: the port is the resilient wrapper, not the raw adapter.
              assertThat(context.getType("chatModelPort")).isEqualTo(ResilientChatModel.class);
            });
  }

  @Test
  void no_ai_bean_is_created_on_the_default_profile() {
    // No property set: the conditional is not satisfied, so the port (and the model) are absent.
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ChatModelPort.class);
        });
  }

  @Test
  void the_wired_port_wraps_the_openai_adapter() {
    withOpenAiProperties()
        .run(
            context -> {
              ChatModelPort port = context.getBean(ChatModelPort.class);
              // S09-09: the resilient wrapper is the port, and it delegates to the OpenAI adapter.
              assertThat(port).isInstanceOf(ResilientChatModel.class);
              assertThat(((ResilientChatModel) port).delegate())
                  .isInstanceOf(OpenAiChatModelAdapter.class);
            });
  }

  private ApplicationContextRunner withOpenAiProperties() {
    return runner.withPropertyValues(
        "agentflow.ai.provider=openai",
        "agentflow.ai.provider.openai.api-key=sk-test-key",
        "agentflow.ai.provider.openai.base-url=https://example.test/openai",
        "agentflow.ai.provider.openai.model=gpt-4o-mini");
  }
}
