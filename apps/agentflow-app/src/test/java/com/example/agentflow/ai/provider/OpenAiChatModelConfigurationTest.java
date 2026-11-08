package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.ChatModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S09-02 configuration test for {@link OpenAiChatModelConfiguration}.
 *
 * <p>Proves the S09-02 opt-in contract: the OpenAI beans exist only when {@code
 * agentflow.ai.provider=openai}, and are absent otherwise — so the core app boots without a
 * provider profile and without any AI key (the default profile leaves the property unset). {@link
 * ApplicationContextRunner} starts a bare context (no Boot auto-configuration), so the real bean
 * definitions and the {@code @ConditionalOnProperty} gate are exercised, and constructing the
 * {@link OpenAiChatModel} is offline (no network, no reachable provider).
 */
class OpenAiChatModelConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(OpenAiChatModelConfiguration.class);

  @Test
  void chat_model_port_is_wired_only_when_the_provider_is_openai() {
    withOpenAiProperties()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("openAiChatModel");
              assertThat(context.getType("openAiChatModel")).isEqualTo(OpenAiChatModel.class);
              assertThat(context).hasBean("chatModelPort");
              assertThat(context.getType("chatModelPort")).isEqualTo(OpenAiChatModelAdapter.class);
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
              assertThat(port).isInstanceOf(OpenAiChatModelAdapter.class);
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
