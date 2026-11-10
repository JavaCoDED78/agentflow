package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.ChatOptions;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.ProviderFailureReason;
import com.example.agentflow.ai.application.ProviderResiliencePolicy;
import com.example.agentflow.config.AgentFlowProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The opt-in real provider contract test (S09-12): the {@link OpenAiChatModelAdapter} contract —
 * response mapping, token usage, structured output and timeout classification — is verified against
 * a <em>live</em> provider, so the adapter can never silently drift from the real API shape the way
 * a mocked SDK can.
 *
 * <p>It is deliberately <b>not</b> part of the standard gate: the class is tagged {@code
 * real-provider}, the default {@code test} task excludes that tag and the dedicated {@code
 * realProviderContractTest} Gradle task runs only this tag, only when {@code OPENAI_API_KEY} is
 * set. The standard {@code ./gradlew check} stays free, offline and deterministic (the S09-12
 * acceptance); the deterministic offline coverage of the same seams remains the S09-11 {@code
 * FakeModelProvider}. Run it manually or on a schedule — see {@code
 * docs/ai/real-provider-contract-test.md}.
 *
 * <p>The API key comes only from the environment ({@code OPENAI_API_KEY}) or a system property
 * ({@code agentflow.contract-test.api-key}), never from the repository; the test fixture prompts
 * contain no sensitive data and the key is never logged or asserted on. The model and base URL
 * default to {@code gpt-4o-mini} and the OpenAI public endpoint and can be overridden with {@code
 * AGENTFLOW_CONTRACT_TEST_MODEL} / {@code OPENAI_BASE_URL} (or the {@code
 * agentflow.contract-test.*} system properties), so the same suite runs against a compatible
 * gateway.
 *
 * <p>Three checks, each against the production wiring (the happy-path checks boot the real {@link
 * OpenAiChatModelConfiguration} through {@link ApplicationContextRunner}, so the request rendering
 * and response mapping under test are exactly what production runs):
 *
 * <ul>
 *   <li>{@link #maps_a_real_completion_into_the_contract()} — a plain chat completion maps to the
 *       neutral {@link ChatResponse}: non-null content, the provider-reported model and finish
 *       reason, and {@link com.example.agentflow.ai.application.ChatUsage token accounting} with
 *       positive input/output counts whose total matches the sum;
 *   <li>{@link #maps_structured_output_into_the_contract()} — a request carrying a neutral {@code
 *       outputSchemaJson} renders into the provider's structured-output form and the completion
 *       comes back as JSON conforming to that schema (the Planner's S09-05 seam, end to end);
 *   <li>{@link #an_overall_timeout_against_a_real_in_flight_call_is_classified_as_timeout()} — the
 *       production resilience wrapper ({@link ResilientChatModel}) aborts a real in-flight call
 *       under an overall timeout far below any realistic completion, and the production {@link
 *       ProviderExceptionClassifier} maps the abort to {@link ProviderFailureReason#TIMEOUT}
 *       (retryable). The platform's own timeout bound is verified against the live endpoint; the
 *       SDK-level read-timeout exceptions stay covered offline by the classifier suite and the
 *       S09-11 fake {@code TIMEOUT} fixture, because a millisecond read timeout also interrupts the
 *       TLS handshake on some network paths and is too environment-sensitive for a contract test.
 * </ul>
 */
@Tag("real-provider")
class RealProviderContractTest {

  /** The cheapest generally available chat model; the documented default, never a credential. */
  private static final String DEFAULT_MODEL = "gpt-4o-mini";

  /** The neutral structured-output schema the structured-output check requests. */
  private static final String ANSWER_SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},"
          + "\"required\":[\"answer\"],\"additionalProperties\":false}";

  @BeforeAll
  static void require_a_real_provider_key() {
    assumeTrue(
        apiKey() != null,
        "real provider contract test is opt-in: set OPENAI_API_KEY (or"
            + " -Dagentflow.contract-test.api-key=...) and run :apps:agentflow-app:realProviderContractTest");
  }

  @Test
  void maps_a_real_completion_into_the_contract() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ChatModelPort port = context.getBean(ChatModelPort.class);

              ChatResponse response = port.chat(ping());

              assertThat(response.content()).isNotNull().containsIgnoringCase("pong");
              assertThat(response.model()).isNotBlank();
              assertThat(response.finishReason()).isNotBlank();
              assertThat(response.usage()).isNotNull();
              assertThat(response.usage().inputTokens()).isPositive();
              assertThat(response.usage().outputTokens()).isPositive();
              assertThat(response.usage().totalTokens())
                  .isEqualTo(response.usage().inputTokens() + response.usage().outputTokens());
            });
  }

  @Test
  void maps_structured_output_into_the_contract() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              ChatModelPort port = context.getBean(ChatModelPort.class);
              ChatRequest request =
                  new ChatRequest(
                      model(),
                      List.of(
                          new ChatMessage(
                              MessageRole.SYSTEM,
                              "You are a test fixture. Answer only with the requested JSON."),
                          new ChatMessage(
                              MessageRole.USER,
                              "Return a JSON object with a single string field \"answer\" set to"
                                  + " \"pong\".")),
                      new ChatOptions(0.0, 64, ANSWER_SCHEMA));

              ChatResponse response = port.chat(request);

              assertThat(response.content()).isNotNull();
              JsonNode parsed = new ObjectMapper().readTree(response.content());
              assertThat(parsed.get("answer").asString()).isEqualToIgnoringCase("pong");
              assertThat(response.usage()).isNotNull();
              assertThat(response.usage().outputTokens()).isPositive();
            });
  }

  @Test
  void an_overall_timeout_against_a_real_in_flight_call_is_classified_as_timeout() {
    // The platform's own bound: the production resilience wrapper aborts a single attempt after
    // the overall timeout while the provider is still generating. The request deliberately asks
    // for a long completion (a forced count to 50) so no healthy endpoint can answer inside the
    // bound. A millisecond SDK read timeout is deliberately not used here — it also interrupts
    // the TLS handshake on some network paths (classified CONNECTION, not TIMEOUT), which is
    // environment noise, not a contract violation.
    ProviderResiliencePolicy noRetry =
        ProviderResiliencePolicy.of(1, Duration.ZERO, 1.0, Duration.ZERO, false);
    ProviderExceptionClassifier classifier = new ProviderExceptionClassifier();

    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              OpenAiChatModelAdapter adapter = context.getBean(OpenAiChatModelAdapter.class);
              ResilientChatModel resilient =
                  ResilientChatModel.of(
                      adapter, noRetry, classifier::classify, overallTimeout(), 1);

              assertThatThrownBy(() -> resilient.chat(slowPing()))
                  .satisfies(
                      failure ->
                          assertThat(classifier.classify(failure))
                              .isEqualTo(ProviderFailureReason.TIMEOUT));
            });
  }

  /**
   * Boots the production provider wiring exactly as the application does — {@link
   * OpenAiChatModelConfiguration} behind its {@code agentflow.ai.provider=openai} gate, the
   * resilience layer included — bound to the configured key/model/endpoint.
   */
  private static ApplicationContextRunner runner() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(
                OpenAiChatModelConfiguration.class,
                ProviderExceptionClassifier.class,
                AgentFlowProperties.class)
            .withPropertyValues(
                "agentflow.ai.provider=openai",
                "agentflow.ai.provider.openai.api-key=" + apiKey(),
                "agentflow.ai.provider.openai.model=" + model());
    String baseUrl = baseUrl();
    if (baseUrl != null) {
      runner = runner.withPropertyValues("agentflow.ai.provider.openai.base-url=" + baseUrl);
    }
    return runner;
  }

  /**
   * The smallest possible deterministic chat request: a fixed system policy and a fixed user ask.
   */
  private static ChatRequest ping() {
    return new ChatRequest(
        model(),
        List.of(
            new ChatMessage(
                MessageRole.SYSTEM, "You are a test fixture. Answer as briefly as possible."),
            new ChatMessage(MessageRole.USER, "Reply with the single word: pong")));
  }

  /**
   * A request that forces a long generation — a count to 50 is ~150 output tokens, so no healthy
   * endpoint can complete inside the overall-timeout bound of the timeout check.
   */
  private static ChatRequest slowPing() {
    return new ChatRequest(
        model(),
        List.of(
            new ChatMessage(
                MessageRole.SYSTEM, "You are a test fixture. Follow the instruction exactly."),
            new ChatMessage(
                MessageRole.USER,
                "Count from 1 to 50 inclusive, separated by commas, nothing else.")),
        new ChatOptions(null, 256, null));
  }

  /**
   * Reads an opt-in setting from the environment first, then from a system property; blank values
   * count as unset.
   */
  private static String setting(String envName, String propertyName) {
    String fromEnv = System.getenv(envName);
    if (fromEnv != null && !fromEnv.isBlank()) {
      return fromEnv.trim();
    }
    String fromProperty = System.getProperty(propertyName);
    return fromProperty == null || fromProperty.isBlank() ? null : fromProperty.trim();
  }

  private static String apiKey() {
    return setting("OPENAI_API_KEY", "agentflow.contract-test.api-key");
  }

  private static String model() {
    String configured = setting("AGENTFLOW_CONTRACT_TEST_MODEL", "agentflow.contract-test.model");
    return configured != null ? configured : DEFAULT_MODEL;
  }

  private static String baseUrl() {
    return setting("OPENAI_BASE_URL", "agentflow.contract-test.base-url");
  }

  /**
   * The overall-timeout bound for the timeout check: far below the seconds a forced long generation
   * takes, so a healthy provider is still generating when the abort fires. Overridable for exotic
   * setups ({@code AGENTFLOW_CONTRACT_TEST_TIMEOUT_MILLIS} or {@code
   * agentflow.contract-test.timeout-millis}).
   */
  private static Duration overallTimeout() {
    String configured =
        setting("AGENTFLOW_CONTRACT_TEST_TIMEOUT_MILLIS", "agentflow.contract-test.timeout-millis");
    return configured != null
        ? Duration.ofMillis(Long.parseLong(configured))
        : Duration.ofMillis(500);
  }
}
