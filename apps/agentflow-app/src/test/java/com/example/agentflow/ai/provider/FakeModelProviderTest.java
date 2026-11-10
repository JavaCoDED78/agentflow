package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.ChatRequest;
import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.MessageRole;
import com.example.agentflow.ai.application.PlannerFailure;
import com.example.agentflow.ai.application.PlannerPlanParseException;
import com.example.agentflow.ai.application.PlannerPlanParser;
import com.example.agentflow.ai.application.PlannerPlanResult;
import com.example.agentflow.ai.application.PlannerService;
import com.example.agentflow.ai.application.PromptCataloguePort;
import com.example.agentflow.ai.application.PromptVersion;
import com.example.agentflow.ai.application.ProviderFailureReason;
import com.example.agentflow.ai.domain.plan.PlannerPlan;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * DB-free unit tests for the deterministic {@link FakeModelProvider} (S09-11): every fixture
 * reproduces its provider outcome exactly, flows through the <em>real</em> production seams (the
 * {@link PlannerPlanParser}, the {@link ProviderExceptionClassifier}, the {@link PlannerService}
 * bounded repair) exactly as a live provider fault would, and never touches a key, the network or a
 * clock — the S09-11 acceptance that the standard {@code ./gradlew check} gate needs neither.
 *
 * <p>The planner-level mappings pin the fixture-to-classification contract: content fixtures parse
 * (or fail to parse) through the real parser, fault fixtures reach the planner as {@code
 * TRANSPORT_FAILURE} (its only transport signal), and the refusal is never retried. The
 * production-classifier checks pin that the thrown fault <em>types</em> classify as {@code TIMEOUT}
 * / {@code RATE_LIMIT}, so a fixture wrapped in {@code ResilientChatModel} retries under the S09-09
 * policy exactly like the real fault.
 */
class FakeModelProviderTest {

  private final ProviderExceptionClassifier classifier = new ProviderExceptionClassifier();

  private static ChatRequest request() {
    return new ChatRequest(
        FakeModelProvider.MODEL, List.of(new ChatMessage(MessageRole.USER, "task")));
  }

  // ---------------------------------------------------------------------------------------------
  // Content fixtures parse through the real PlannerPlanParser.
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_valid_plan_fixture_parses_into_the_expected_two_step_plan() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.VALID_PLAN);

    ChatResponse response = provider.chat(request());

    PlannerPlan plan = new PlannerPlanParser(new ObjectMapper()).parse(response.content());
    assertThat(plan.steps()).hasSize(2);
    assertThat(plan.steps().get(0).id()).isEqualTo("s1");
    assertThat(plan.steps().get(0).type()).isEqualTo("TOOL_CALL");
    assertThat(plan.steps().get(0).toolId()).isEqualTo("calculator");
    assertThat(plan.steps().get(1).id()).isEqualTo("s2");
    assertThat(plan.steps().get(1).dependsOn()).containsExactly("s1");
  }

  @Test
  void the_invalid_plan_fixture_is_wellformed_json_the_real_parser_rejects_as_schema_mismatch() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.INVALID_PLAN);

    ChatResponse response = provider.chat(request());

    // The duplicate step id surfaces from the plan's canonical constructor; the parser unwraps
    // Jackson 3's ValueInstantiationException wrapping, so the structural violation classifies as
    // SCHEMA_MISMATCH (the parser-follow-up fix done together with S09-11).
    assertThatThrownBy(() -> new PlannerPlanParser(new ObjectMapper()).parse(response.content()))
        .isInstanceOf(PlannerPlanParseException.class)
        .hasMessageContaining("duplicate step id")
        .extracting(e -> ((PlannerPlanParseException) e).classification())
        .isEqualTo(PlannerFailure.SCHEMA_MISMATCH);
  }

  @Test
  void the_malformed_response_fixture_is_not_json_the_real_parser_rejects_as_malformed() {
    FakeModelProvider provider =
        new FakeModelProvider(FakeModelProvider.Fixture.MALFORMED_RESPONSE);

    ChatResponse response = provider.chat(request());

    assertThatThrownBy(() -> new PlannerPlanParser(new ObjectMapper()).parse(response.content()))
        .isInstanceOf(PlannerPlanParseException.class)
        .extracting(e -> ((PlannerPlanParseException) e).classification())
        .isEqualTo(PlannerFailure.MALFORMED_JSON);
  }

  // ---------------------------------------------------------------------------------------------
  // Fault fixtures throw faults the production classifier maps to the real reasons.
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_timeout_fixture_classifies_as_timeout_by_the_production_classifier() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.TIMEOUT);

    RuntimeException fault = catchFault(provider);

    assertThat(classifier.classify(fault)).isEqualTo(ProviderFailureReason.TIMEOUT);
    assertThat(ProviderFailureReason.TIMEOUT.isRetryable()).isTrue();
  }

  @Test
  void the_rate_limit_fixture_classifies_as_rate_limit_by_the_production_classifier() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.RATE_LIMIT);

    RuntimeException fault = catchFault(provider);

    assertThat(classifier.classify(fault)).isEqualTo(ProviderFailureReason.RATE_LIMIT);
    assertThat(ProviderFailureReason.RATE_LIMIT.isRetryable()).isTrue();
  }

  // ---------------------------------------------------------------------------------------------
  // The refusal fixture answers with null content, the port contract's refusal shape.
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_refusal_fixture_returns_null_content_with_a_content_filter_stop() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.REFUSAL);

    ChatResponse response = provider.chat(request());

    assertThat(response.model()).isEqualTo(FakeModelProvider.MODEL);
    assertThat(response.content()).isNull();
    assertThat(response.finishReason()).isEqualTo("content_filter");
  }

  // ---------------------------------------------------------------------------------------------
  // Determinism: the same fixture always answers the same way, and calls are counted.
  // ---------------------------------------------------------------------------------------------

  @Test
  void every_content_fixture_is_deterministic_across_repeated_calls() {
    for (FakeModelProvider.Fixture fixture :
        List.of(
            FakeModelProvider.Fixture.VALID_PLAN,
            FakeModelProvider.Fixture.INVALID_PLAN,
            FakeModelProvider.Fixture.MALFORMED_RESPONSE,
            FakeModelProvider.Fixture.REFUSAL)) {
      FakeModelProvider provider = new FakeModelProvider(fixture);
      ChatResponse first = provider.chat(request());
      ChatResponse second = provider.chat(request());
      assertThat(second).isEqualTo(first);
    }
  }

  @Test
  void every_fault_fixture_throws_the_same_fault_shape_across_repeated_calls() {
    for (FakeModelProvider.Fixture fixture :
        List.of(FakeModelProvider.Fixture.TIMEOUT, FakeModelProvider.Fixture.RATE_LIMIT)) {
      FakeModelProvider provider = new FakeModelProvider(fixture);
      RuntimeException first = catchFault(provider);
      RuntimeException second = catchFault(provider);
      assertThat(second.getClass()).isEqualTo(first.getClass());
      assertThat(second.getMessage()).isEqualTo(first.getMessage());
    }
  }

  @Test
  void every_call_is_counted() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.VALID_PLAN);

    provider.chat(request());
    provider.chat(request());
    provider.chat(request());

    assertThat(provider.calls()).isEqualTo(3);
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture -> Planner classification (the bounded-repair behaviour on fixed fixtures).
  // ---------------------------------------------------------------------------------------------

  @Test
  void the_planner_repairs_a_malformed_fake_plan_once_and_then_gives_up() {
    FakeModelProvider provider =
        new FakeModelProvider(FakeModelProvider.Fixture.MALFORMED_RESPONSE);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanParseException failure = catchPlannerFailure(planner);

    assertThat(failure.classification()).isEqualTo(PlannerFailure.MALFORMED_JSON);
    // The initial call plus exactly one corrective retry (the S09-08 bound).
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  void the_planner_repairs_an_invalid_fake_plan_once_and_then_gives_up() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.INVALID_PLAN);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanParseException failure = catchPlannerFailure(planner);

    assertThat(failure.classification()).isEqualTo(PlannerFailure.SCHEMA_MISMATCH);
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  void the_planner_maps_the_timeout_fixture_to_transport_failure_with_one_retry() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.TIMEOUT);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanParseException failure = catchPlannerFailure(planner);

    assertThat(failure.classification()).isEqualTo(PlannerFailure.TRANSPORT_FAILURE);
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  void the_planner_maps_the_rate_limit_fixture_to_transport_failure_with_one_retry() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.RATE_LIMIT);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanParseException failure = catchPlannerFailure(planner);

    assertThat(failure.classification()).isEqualTo(PlannerFailure.TRANSPORT_FAILURE);
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  void the_planner_never_retries_a_fake_refusal() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.REFUSAL);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanParseException failure = catchPlannerFailure(planner);

    assertThat(failure.classification()).isEqualTo(PlannerFailure.PROVIDER_REFUSAL);
    assertThat(provider.calls()).isEqualTo(1);
  }

  @Test
  void the_planner_returns_the_fake_valid_plan_with_its_metadata() {
    FakeModelProvider provider = new FakeModelProvider(FakeModelProvider.Fixture.VALID_PLAN);
    PlannerService planner =
        new PlannerService(catalogue(), FakeModelProvider.MODEL, provider, null);

    PlannerPlanResult result = planner.propose("compute 2 + 2", List.of("calculator"));

    assertThat(result.plan().steps()).hasSize(2);
    assertThat(result.model()).isEqualTo(FakeModelProvider.MODEL);
    assertThat(result.usage().totalTokens()).isEqualTo(18);
    assertThat(provider.calls()).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // Configuration parsing.
  // ---------------------------------------------------------------------------------------------

  @Test
  void fixtures_parse_from_kebab_case_config_names_case_insensitively() {
    assertThat(FakeModelProvider.Fixture.fromConfig("valid-plan"))
        .isEqualTo(FakeModelProvider.Fixture.VALID_PLAN);
    assertThat(FakeModelProvider.Fixture.fromConfig("Invalid-Plan"))
        .isEqualTo(FakeModelProvider.Fixture.INVALID_PLAN);
    assertThat(FakeModelProvider.Fixture.fromConfig("MALFORMED-response"))
        .isEqualTo(FakeModelProvider.Fixture.MALFORMED_RESPONSE);
    assertThat(FakeModelProvider.Fixture.fromConfig("timeout"))
        .isEqualTo(FakeModelProvider.Fixture.TIMEOUT);
    assertThat(FakeModelProvider.Fixture.fromConfig("refusal"))
        .isEqualTo(FakeModelProvider.Fixture.REFUSAL);
  }

  @Test
  void four_twenty_nine_is_an_alias_for_the_rate_limit_fixture() {
    assertThat(FakeModelProvider.Fixture.fromConfig("429"))
        .isEqualTo(FakeModelProvider.Fixture.RATE_LIMIT);
  }

  @Test
  void a_blank_config_selects_the_documented_default_fixture() {
    assertThat(FakeModelProvider.Fixture.fromConfig(null))
        .isEqualTo(FakeModelProvider.Fixture.VALID_PLAN);
    assertThat(FakeModelProvider.Fixture.fromConfig("   "))
        .isEqualTo(FakeModelProvider.Fixture.VALID_PLAN);
  }

  @Test
  void an_unknown_config_mode_fails_fast_listing_the_supported_modes() {
    assertThatThrownBy(() -> FakeModelProvider.Fixture.fromConfig("chaos"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("chaos")
        .hasMessageContaining("valid-plan")
        .hasMessageContaining("429");
  }

  @Test
  void a_null_fixture_is_a_contract_error() {
    assertThatThrownBy(() -> new FakeModelProvider(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------------------------

  private static RuntimeException catchFault(FakeModelProvider provider) {
    try {
      provider.chat(request());
      return fail("expected the %s fixture to throw", provider.fixture());
    } catch (RuntimeException e) {
      return e;
    }
  }

  private static PlannerPlanParseException catchPlannerFailure(PlannerService planner) {
    try {
      planner.propose("task", List.of("calculator"));
      return fail("expected the planner to fail against the fake fixture");
    } catch (PlannerPlanParseException e) {
      return e;
    }
  }

  /**
   * A same-package-free fake {@link PromptCataloguePort} (mirrors {@code PlannerServiceTest}'s): a
   * fixed policy template and a version whose hash satisfies {@link PromptVersion}'s SHA-256
   * validation, so the Planner runs without the {@code ai.prompt} package.
   */
  private static PromptCataloguePort catalogue() {
    PromptVersion version = new PromptVersion("planner", "1", hex64('0'));
    return new PromptCataloguePort() {
      @Override
      public PromptVersion versionOf(String name) {
        return version;
      }

      @Override
      public List<ChatMessage> build(String name, String userContent) {
        List<ChatMessage> messages = new ArrayList<>(2);
        messages.add(new ChatMessage(MessageRole.SYSTEM, "FAKE_PLANNER_POLICY"));
        messages.add(new ChatMessage(MessageRole.USER, userContent));
        return messages;
      }
    };
  }

  private static String hex64(char digit) {
    return new String(new char[64]).replace('\0', digit);
  }
}
