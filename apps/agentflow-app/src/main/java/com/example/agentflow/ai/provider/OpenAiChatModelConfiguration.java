package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.ChatModelPort;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Opt-in wiring of the OpenAI {@link ChatModelPort} behind the neutral port (S09-02).
 *
 * <p>The whole configuration is <em>opt-in</em>: the beans exist only when the application is
 * explicitly told to use the OpenAI provider, {@code agentflow.ai.provider=openai}. The default
 * profile (and the integration / smoke runs) never set it, so the core app boots exactly as before
 * — with <b>no</b> {@link ChatModel} bean and <b>no</b> AI key on the classpath. This is the S09-02
 * requirement: the provider is behind the port, and the provider-specific beans are created only by
 * a {@code @ConditionalOnProperty} config (ADR-014).
 *
 * <p>The API key and base URL come from the environment / secrets, never hardcoded. The key is read
 * from {@code agentflow.ai.provider.openai.api-key} or the {@code OPENAI_API_KEY} environment
 * variable; the base URL and model default to the OpenAI public endpoint and {@code gpt-4o-mini}
 * (the default is a documented placeholder, never a real credential). A missing key does not fail
 * boot — the OpenAI client resolves {@code OPENAI_API_KEY} from the environment at call time — but
 * a call without a key will be rejected by the provider, which is the correct fail-fast for a
 * missing secret.
 *
 * <p>The model bean is the provider-neutral {@link ChatModel} interface; {@link
 * #chatModelPort(ChatModel)} adapts it to {@link ChatModelPort}, so the domain depends only on the
 * port. The adapter itself depends on the {@link ChatModel} interface, which is why it can be
 * unit-tested against a fake model.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "agentflow.ai",
    name = "provider",
    havingValue = "openai",
    matchIfMissing = false)
public class OpenAiChatModelConfiguration {

  /**
   * The OpenAI {@link ChatModel} used by the adapter.
   *
   * @param apiKey the provider API key, from {@code agentflow.ai.provider.openai.api-key} or {@code
   *     OPENAI_API_KEY}; {@code null} / empty when the secret is absent (the client falls back to
   *     {@code OPENAI_API_KEY} from the environment)
   * @param baseUrl the provider base URL; {@code null} / empty uses the OpenAI default endpoint
   * @param model the model id the adapter calls by default; a documented placeholder, never a
   *     credential
   * @return the OpenAI chat model wired with the secret-bearing options
   */
  @Bean
  public ChatModel openAiChatModel(
      @Value("${agentflow.ai.provider.openai.api-key:${OPENAI_API_KEY:}}") String apiKey,
      @Value("${agentflow.ai.provider.openai.base-url:}") String baseUrl,
      @Value("${agentflow.ai.provider.openai.model:gpt-4o-mini}") String model) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .apiKey(StringUtils.hasText(apiKey) ? apiKey : null)
            .model(model);
    // Only set an explicit base URL when one is configured; leaving it null keeps the OpenAI
    // default endpoint (so a plain key + no base URL still works against the public API).
    if (StringUtils.hasText(baseUrl)) {
      options.baseUrl(baseUrl);
    }
    return OpenAiChatModel.builder().options(options.build()).build();
  }

  /**
   * The provider-neutral port the domain calls. Backed by the {@link OpenAiChatModelAdapter}
   * (S09-02), the single class that renders the contract into the Spring AI shape.
   *
   * @param openAiChatModel the OpenAI model to wrap (see {@link #openAiChatModel(...)})
   * @return the OpenAI adapter implementing {@link ChatModelPort}
   */
  @Bean
  public ChatModelPort chatModelPort(ChatModel openAiChatModel) {
    return new OpenAiChatModelAdapter(openAiChatModel);
  }
}
