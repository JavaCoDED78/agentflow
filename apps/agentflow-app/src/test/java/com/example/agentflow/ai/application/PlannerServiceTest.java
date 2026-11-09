package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link PlannerService} (S09-06): it asks the model through the neutral
 * {@link ChatModelPort}, renders the policy + task/user-content split via a fake {@link
 * PromptCataloguePort}, maps a successful response into a plan with its metadata, and fails loud
 * and clear (never a silent {@code null} plan) when the provider is missing or the model returns
 * nothing.
 *
 * <p>The catalogue is faked with an in-package {@link PromptCataloguePort} implementation so the
 * test never imports {@code ai.prompt} -- importing it here would reintroduce the {@code
 * ai.application -> ai.prompt} edge that closes the package-cycle the {@code S02-09} acyclicity
 * gate guards against.
 */
class PlannerServiceTest {

  private static final String PLANNER_TEMPLATE = "PLANNER_POLICY_v1";

  @Test
  void propose_maps_a_successful_response_into_a_plan_with_metadata() {
    ChatModelPort port =
        request ->
            new ChatResponse(
                "gpt-plan-1",
                "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}",
                "stop",
                new ChatUsage(10, 5, 15));
    PlannerService planner = new PlannerService(catalogue(), "gpt-plan-1", port);

    PlannerPlanResult result = planner.propose("book a table", List.of("calc"));

    assertThat(result.plan().steps()).hasSize(1);
    assertThat(result.plan().steps().get(0).id()).isEqualTo("s1");
    assertThat(result.model()).isEqualTo("gpt-plan-1");
    assertThat(result.promptVersion().name()).isEqualTo("planner");
    assertThat(result.usage()).isEqualTo(new ChatUsage(10, 5, 15));
  }

  @Test
  void propose_sends_the_policy_as_system_and_the_task_and_tools_as_user() {
    AtomicReference<ChatRequest> captured = new AtomicReference<>();
    ChatModelPort recording =
        request -> {
          captured.set(request);
          return new ChatResponse(
              "gpt", "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}", null, null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", recording);

    planner.propose("compute 2 + 2", List.of("calc", "report"));

    ChatRequest request = captured.get();
    assertThat(request.promptVersion().name()).isEqualTo("planner");
    assertThat(request.model()).isEqualTo("gpt");
    assertThat(request.messages()).hasSize(2);
    // The policy is a fixed SYSTEM message; the untrusted task + tools ride together as one USER
    // turn.
    assertThat(request.messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
    assertThat(request.messages().get(0).content()).isEqualTo(PLANNER_TEMPLATE);
    assertThat(request.messages().get(1).role()).isEqualTo(MessageRole.USER);
    assertThat(request.messages().get(1).content())
        .contains("compute 2 + 2")
        .contains("calc")
        .contains("report");
  }

  @Test
  void propose_fails_loud_when_no_provider_is_configured() {
    PlannerService planner = new PlannerService(catalogue(), "gpt", null);

    assertThatThrownBy(() -> planner.propose("task", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ChatModelPort configured");
  }

  @Test
  void propose_refusal_with_null_content_is_a_parse_signal_not_a_plan() {
    ChatModelPort port = request -> new ChatResponse("gpt", null, "stop", null);
    PlannerService planner = new PlannerService(catalogue(), "gpt", port);

    assertThatThrownBy(() -> planner.propose("task", List.of()))
        .isInstanceOf(PlannerPlanParseException.class)
        .hasMessageContaining("no content");
  }

  @Test
  void propose_blank_task_is_a_contract_error() {
    ChatModelPort port = request -> new ChatResponse("gpt", "{\"steps\":[]}", null, null);
    PlannerService planner = new PlannerService(catalogue(), "gpt", port);

    assertThatThrownBy(() -> planner.propose("   ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * A same-package fake {@link PromptCataloguePort}: it returns a fixed policy template and a
   * version whose hash satisfies {@link PromptVersion}'s SHA-256 validation, so no real {@code
   * ai.prompt} dependency is needed.
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
        messages.add(new ChatMessage(MessageRole.SYSTEM, PLANNER_TEMPLATE));
        messages.add(new ChatMessage(MessageRole.USER, userContent));
        return messages;
      }
    };
  }

  private static String hex64(char digit) {
    return new String(new char[64]).replace('\0', digit);
  }
}
