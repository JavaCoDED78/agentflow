package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for {@link PlannerService} (S09-06 / S09-08): it asks the model through the
 * neutral {@link ChatModelPort}, renders the policy + task/user-content split via a fake {@link
 * PromptCataloguePort}, maps a successful response into a plan with its metadata, and fails loud
 * and clear (never a silent {@code null} plan) when the provider is missing or the model returns
 * nothing.
 *
 * <p>The S09-08 bounded-repair behaviour is pinned here too: a malformed / schema-mismatch /
 * transport failure is classified ({@link PlannerFailure}) and, when budget is available, repaired
 * with at most one corrective retry ({@link PlannerRepairPolicy}) — never an infinite loop of model
 * calls — while a provider refusal is never retried and a retry never runs without budget.
 *
 * <p>The catalogue is faked with an in-package {@link PromptCataloguePort} implementation so the
 * test never imports {@code ai.prompt} -- importing it here would reintroduce the {@code
 * ai.application -> ai.prompt} edge that closes the package-cycle the {@code S02-09} acyclicity
 * gate guards against.
 */
class PlannerServiceTest {

  private static final String PLANNER_TEMPLATE = "PLANNER_POLICY_v1";
  private static final Currency CURRENCY = Currency.getInstance("USD");

  @Test
  void propose_maps_a_successful_response_into_a_plan_with_metadata() {
    ChatModelPort port =
        request ->
            new ChatResponse(
                "gpt-plan-1",
                "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}",
                "stop",
                new ChatUsage(10, 5, 15));
    PlannerService planner = new PlannerService(catalogue(), "gpt-plan-1", port, null);

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
    PlannerService planner = new PlannerService(catalogue(), "gpt", recording, null);

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
    PlannerService planner = new PlannerService(catalogue(), "gpt", null, null);

    assertThatThrownBy(() -> planner.propose("task", List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no ChatModelPort configured");
  }

  @Test
  void propose_refusal_with_null_content_is_a_parse_signal_not_a_plan() {
    ChatModelPort port = request -> new ChatResponse("gpt", null, "stop", null);
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanParseException failure = null;
    try {
      planner.propose("task", List.of());
      fail("expected a PlannerPlanParseException (the model refused)");
    } catch (PlannerPlanParseException e) {
      failure = e;
    }
    assertThat(failure).isNotNull();
    assertThat(failure.classification()).isEqualTo(PlannerFailure.PROVIDER_REFUSAL);
    assertThat(failure).hasMessageContaining("no content");
  }

  @Test
  void propose_blank_task_is_a_contract_error() {
    ChatModelPort port = request -> new ChatResponse("gpt", "{\"steps\":[]}", null, null);
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    assertThatThrownBy(() -> planner.propose("   ", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void propose_retries_once_and_returns_a_valid_plan_after_a_malformed_attempt() {
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return calls.get() == 1
              ? new ChatResponse("gpt", "{ not valid json", null, null)
              : new ChatResponse(
                  "gpt", "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}", "stop", null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanResult result = planner.propose("task", List.of("calc"));

    assertThat(result.plan().steps()).hasSize(1);
    // One malformed attempt plus exactly one corrective retry.
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void propose_retries_a_transport_failure_and_returns() {
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          if (calls.get() == 1) {
            throw new RuntimeException("provider socket closed");
          }
          return new ChatResponse("gpt", "{\"steps\":[]}", "stop", null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanResult result = planner.propose("task", List.of());

    assertThat(result.plan().steps()).isEmpty();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void propose_retries_a_schema_mismatch_and_returns() {
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return calls.get() == 1
              ? new ChatResponse(
                  "gpt",
                  "{\"steps\":[{\"id\":\"dup\",\"type\":\"TOOL_CALL\"},{\"id\":\"dup\",\"type\":\"FINALIZE\"}]}",
                  null,
                  null)
              : new ChatResponse(
                  "gpt", "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}", "stop", null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanResult result = planner.propose("task", List.of("calc"));

    assertThat(result.plan().steps()).hasSize(1);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void propose_gives_up_after_one_retry_on_persistent_malformed_output() {
    // The S09-08 acceptance: a malformed plan is repaired with a bounded retry, never an infinite
    // loop of model calls.
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return new ChatResponse("gpt", "{ not valid json", null, null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanParseException failure = null;
    try {
      planner.propose("task", List.of("calc"));
      fail("expected a PlannerPlanParseException");
    } catch (PlannerPlanParseException e) {
      failure = e;
    }
    assertThat(failure).isNotNull();
    assertThat(failure.classification()).isEqualTo(PlannerFailure.MALFORMED_JSON);

    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void propose_does_not_retry_a_provider_refusal() {
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return new ChatResponse("gpt", null, "stop", null);
        };
    PlannerService planner = new PlannerService(catalogue(), "gpt", port, null);

    PlannerPlanParseException failure = null;
    try {
      planner.propose("task", List.of("calc"));
      fail("expected a PlannerPlanParseException (the model refused)");
    } catch (PlannerPlanParseException e) {
      failure = e;
    }
    assertThat(failure).isNotNull();
    assertThat(failure.classification()).isEqualTo(PlannerFailure.PROVIDER_REFUSAL);

    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void propose_blocks_the_initial_call_when_the_budget_is_exhausted() {
    // The S09-10 acceptance: an exhausted model budget stops the call before the request is
    // sent, so the model is never asked (the S09-08 calls==1 budget gate is now the S09-10
    // reserve-before-every-call gate).
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return new ChatResponse(
              "gpt", "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}", "stop", null);
        };
    // conservativePerCallCost (10) > limit (1): the pre-call reserve overdraws and blocks.
    PlannerService planner =
        new PlannerService(
            catalogue(),
            "gpt",
            port,
            new ModelBudget(new BigDecimal("1"), CURRENCY, new BigDecimal("10")));

    assertThatThrownBy(() -> planner.propose("task", List.of("calc")))
        .isInstanceOf(InsufficientBudgetException.class);
    assertThat(calls.get()).isZero();
  }

  @Test
  void propose_blocks_the_retry_after_the_initial_call_spent_the_budget() {
    // A budget spent by the initial (failed) attempt blocks the retry: the retry's reserve
    // overdraws and the failure propagates as a budget refusal (calls==1, not 2).
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return new ChatResponse("gpt", "{ not valid json", null, null);
        };
    // limit (1.5) covers one reserve (1.0) but not two: the retry overdraws and is blocked.
    PlannerService planner =
        new PlannerService(
            catalogue(),
            "gpt",
            port,
            new ModelBudget(new BigDecimal("1.5"), CURRENCY, new BigDecimal("1")));

    assertThatThrownBy(() -> planner.propose("task", List.of("calc")))
        .isInstanceOf(InsufficientBudgetException.class);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void propose_retries_when_the_budget_allows_each_call() {
    // A healthy budget does not interfere with the S09-08 bounded retry: each attempt reserves
    // its conservative estimate, both fit under the limit, and a valid plan is returned after
    // one retry.
    AtomicInteger calls = new AtomicInteger();
    ChatModelPort port =
        request -> {
          calls.incrementAndGet();
          return calls.get() == 1
              ? new ChatResponse("gpt", "{ not valid json", null, null)
              : new ChatResponse(
                  "gpt", "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"}]}", "stop", null);
        };
    PlannerService planner =
        new PlannerService(
            catalogue(),
            "gpt",
            port,
            new ModelBudget(new BigDecimal("100"), CURRENCY, new BigDecimal("1")));

    PlannerPlanResult result = planner.propose("task", List.of("calc"));

    assertThat(result.plan().steps()).hasSize(1);
    assertThat(calls.get()).isEqualTo(2);
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
