package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

import com.example.agentflow.ai.application.ChatMessage;
import com.example.agentflow.ai.application.InsufficientBudgetException;
import com.example.agentflow.ai.application.ModelBudget;
import com.example.agentflow.ai.application.PlannerFailure;
import com.example.agentflow.ai.application.PlannerPlanParseException;
import com.example.agentflow.ai.application.PlannerPlanResult;
import com.example.agentflow.ai.application.PlannerRepairPolicy;
import com.example.agentflow.ai.application.PlannerService;
import com.example.agentflow.ai.application.PromptCataloguePort;
import com.example.agentflow.ai.application.PromptVersion;
import com.example.agentflow.ai.application.ProviderResiliencePolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The S14-09 provider error matrix: the deterministic fake provider answers every row of the error
 * space, and each row must be bounded and honest end to end — retries capped, the terminal status
 * classified (never a silent success), the usage reservation kept (never double-settled) and the
 * metrics honest (errors recorded per failed attempt, no tokens on a failure, latency always
 * recorded).
 *
 * <p>Every row drives the <em>full production seam</em>, no shortcuts: {@link PlannerService} over
 * the real {@link ResilientChatModel} (bounded retry + concurrency cap, S09-09) over the
 * deterministic {@link FakeModelProvider}, with the real {@link ProviderExceptionClassifier}, a
 * {@link SimpleMeterRegistry} and a {@link ModelBudget}. The same fixture the planner sees is the
 * fixture a live provider would produce, so the acceptance criterion — retries bounded, expenses
 * accounted, no runaway calls — is proven against the real wrapper, not a parallel
 * reimplementation.
 *
 * <p>DB-free, no network, no model, no system clock or randomness (invariant 10): the executor, the
 * sleeper, the policy and the registry are injected, so every outcome is deterministic.
 */
class ProviderErrorMatrixTest {

  /** The fixed model label; a fixture, never a real model id. */
  private static final String MODEL = FakeModelProvider.MODEL;

  /** Fixed budget: one conservative estimate per call, plenty of headroom for both attempts. */
  private static final BigDecimal CONSERVATIVE_COST = new BigDecimal("1");

  private static final BigDecimal LIMIT = new BigDecimal("100");

  /** Two attempts per resilient call, 1 ms base, capped at 5 ms, no jitter — deterministic. */
  private static ProviderResiliencePolicy resilientPolicy() {
    return ProviderResiliencePolicy.of(2, Duration.ofMillis(1), 2.0, Duration.ofMillis(5), false);
  }

  /**
   * One matrix row: the fake provider's fixture, the classified error reason the S13-13 errors
   * counter must record ({@code null} for the non-fault fixtures), the terminal classification the
   * planner must surface ({@code null} for the valid control), and the exact expectations the row
   * must prove.
   */
  private record MatrixRow(
      FakeModelProvider.Fixture fixture,
      String errorsReason,
      PlannerFailure terminalFailure,
      int expectedCalls,
      int expectedTimerSamples,
      BigDecimal expectedReserved,
      Long expectedInputTokens,
      Long expectedOutputTokens) {

    @Override
    public String toString() {
      return fixture.name();
    }
  }

  private static List<MatrixRow> matrixRows() {
    return List.of(
        // Fault rows: every wrapper attempt fails (the wrapper never gets a response), every
        // planner attempt retries the wrapper within its own S09-08 bound, so the whole call spends
        // 2 wrapper attempts per planner attempt. Terminal: TRANSPORT_FAILURE.
        new MatrixRow(
            FakeModelProvider.Fixture.TIMEOUT,
            "timeout",
            PlannerFailure.TRANSPORT_FAILURE,
            4, // 2 planner attempts x 2 wrapper attempts
            2, // one latency sample per wrapper call
            CONSERVATIVE_COST.multiply(new BigDecimal("2")), // both attempts kept, never settled
            null, // never a response -> no tokens
            null),
        new MatrixRow(
            FakeModelProvider.Fixture.RATE_LIMIT,
            "rate_limit",
            PlannerFailure.TRANSPORT_FAILURE,
            4,
            2,
            CONSERVATIVE_COST.multiply(new BigDecimal("2")),
            null,
            null),
        new MatrixRow(
            FakeModelProvider.Fixture.SERVER_ERROR,
            "server_error",
            PlannerFailure.TRANSPORT_FAILURE,
            4,
            2,
            CONSERVATIVE_COST.multiply(new BigDecimal("2")),
            null,
            null),
        // Invalid structured output: the wrapper succeeds (a response is returned), the planner
        // repairs once, then fails terminal as SCHEMA_MISMATCH. Tokens are recorded — the response
        // carried a usage block — but the reservation is kept on the failed attempt.
        new MatrixRow(
            FakeModelProvider.Fixture.INVALID_PLAN,
            null, // the wrapper never fails -> no errors counter entry
            PlannerFailure.SCHEMA_MISMATCH,
            2, // one wrapper call per planner attempt
            2,
            CONSERVATIVE_COST.multiply(new BigDecimal("2")),
            24L, // 12 input tokens x 2 attempts
            12L), // 6 output tokens x 2 attempts
        // Refusal: never retried (not a repairable failure). One attempt, one reservation, no
        // tokens (the refusal carries no usage block).
        new MatrixRow(
            FakeModelProvider.Fixture.REFUSAL,
            null,
            PlannerFailure.PROVIDER_REFUSAL,
            1,
            1,
            CONSERVATIVE_COST,
            null,
            null),
        // Valid-plan control: exactly one call, tokens recorded once, reserved exactly once.
        new MatrixRow(
            FakeModelProvider.Fixture.VALID_PLAN,
            null,
            null, // the control row must succeed
            1,
            1,
            CONSERVATIVE_COST,
            12L,
            6L));
  }

  private final ExecutorService callExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "test-error-matrix");
            thread.setDaemon(true);
            return thread;
          });

  @AfterEach
  void shutDownExecutor() {
    callExecutor.shutdownNow();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrixRows")
  void every_provider_error_is_bounded_classified_accounted_and_measured(MatrixRow row) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeModelProvider fake = new FakeModelProvider(row.fixture());
    ResilientChatModel resilient = resilientOver(fake, registry);
    ModelBudget budget = new ModelBudget(LIMIT, Currency.getInstance("USD"), CONSERVATIVE_COST);
    PlannerService planner =
        new PlannerService(catalogue(), MODEL, resilient, PlannerRepairPolicy.ofDefaults(), budget);

    if (row.terminalFailure() == null) {
      // Control row: the planner must return the plan — never throw, never silently swallow.
      PlannerPlanResult result = planner.propose("compute 2 + 2", List.of("calculator"));
      assertThat(result.plan().steps()).hasSize(2);
    } else {
      PlannerPlanParseException failure = catchPlannerFailure(planner);
      // Status: the terminal classification is the one the platform's observability surfaces read.
      assertThat(failure.classification()).isEqualTo(row.terminalFailure());
    }

    // Retry count: the fake's own call counter proves the exact number of model calls —
    // bounded, never runaway (the S09-08 repair bound x the S09-09 retry bound).
    assertThat(fake.calls())
        .as("exact model-call count for %s", row.fixture())
        .isEqualTo(row.expectedCalls());

    // Metrics: the errors counter records one increment per failed wrapper attempt, with the
    // classified reason (bounded vocabulary), never an invented reason.
    if (row.errorsReason() != null) {
      assertThat(
              registry
                  .find("agentflow.ai.provider.chat.errors.total")
                  .tag("reason", row.errorsReason())
                  .counter()
                  .count())
          .as("%s must be recorded per failed wrapper attempt", row.errorsReason())
          .isEqualTo((double) row.expectedCalls());
    } else {
      assertThat(registry.find("agentflow.ai.provider.chat.errors.total").meters())
          .as("no wrapper failure -> no errors counter at all")
          .isEmpty();
    }

    // Metrics: the whole-call latency timer is recorded exactly once per wrapper call.
    assertThat(registry.find("agentflow.ai.provider.chat.latency.seconds").timer().count())
        .as("latency timer samples for %s", row.fixture())
        .isEqualTo(row.expectedTimerSamples());

    // Metrics: tokens are recorded only from the provider's own report — never invented, never
    // recorded on a failure.
    if (row.expectedInputTokens() != null) {
      assertThat(registry.find("agentflow.ai.provider.chat.tokens.input.total").counter().count())
          .as("input tokens for %s", row.fixture())
          .isEqualTo((double) row.expectedInputTokens());
      assertThat(registry.find("agentflow.ai.provider.chat.tokens.output.total").counter().count())
          .as("output tokens for %s", row.fixture())
          .isEqualTo((double) row.expectedOutputTokens());
    } else {
      assertThat(registry.find("agentflow.ai.provider.chat.tokens.input.total").meters())
          .as("no tokens recorded for %s", row.fixture())
          .isEmpty();
      assertThat(registry.find("agentflow.ai.provider.chat.tokens.output.total").meters())
          .as("no output tokens recorded for %s", row.fixture())
          .isEmpty();
    }

    // Usage reservation: every attempt keeps its reservation (never settled on a failure), so the
    // committed headroom is the number of attempts x the conservative estimate — never zero, never
    // a double-charge. The success path also keeps the estimate (the actual cost is not metered
    // this sprint — settle(null) is a no-op).
    assertThat(budget.reserved())
        .as("reserved headroom for %s", row.fixture())
        .isEqualByComparingTo(row.expectedReserved());
  }

  @Test
  void a_budget_that_cannot_cover_the_retry_blocks_the_repair_before_any_call_is_sent() {
    // A budget whose headroom covers exactly one conservative estimate: the initial attempt may
    // proceed, the S09-08 repair is refused at the pre-call reserve — the model is never asked
    // twice — and the refusal propagates loudly.
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FakeModelProvider fake = new FakeModelProvider(FakeModelProvider.Fixture.TIMEOUT);
    ResilientChatModel resilient = resilientOver(fake, registry);
    ModelBudget thinBudget =
        new ModelBudget(CONSERVATIVE_COST, Currency.getInstance("USD"), CONSERVATIVE_COST);
    PlannerService planner =
        new PlannerService(
            catalogue(), MODEL, resilient, PlannerRepairPolicy.ofDefaults(), thinBudget);

    assertThatThrownBy(() -> planner.propose("compute 2 + 2", List.of("calculator")))
        .isInstanceOf(InsufficientBudgetException.class);

    // The initial attempt ran through the wrapper exactly once (2 wrapper attempts, 2 fake calls);
    // the repair is refused before a call is sent — the fake is never asked again.
    assertThat(fake.calls()).isEqualTo(2);
    // The one reservation that did proceed is kept — never silently released.
    assertThat(thinBudget.reserved()).isEqualByComparingTo(CONSERVATIVE_COST);
  }

  // ---- helpers
  // -----------------------------------------------------------------------------------

  private ResilientChatModel resilientOver(FakeModelProvider fake, SimpleMeterRegistry registry) {
    return new ResilientChatModel(
        fake,
        resilientPolicy(),
        new ProviderExceptionClassifier()::classify,
        Duration.ofSeconds(30),
        4,
        null,
        noopSleeper(),
        callExecutor,
        registry,
        null);
  }

  private static Consumer<Long> noopSleeper() {
    return delayMillis -> {};
  }

  private PlannerPlanParseException catchPlannerFailure(PlannerService planner) {
    try {
      planner.propose("compute 2 + 2", List.of("calculator"));
      fail("expected the planner to fail against the fake fixture");
      return null;
    } catch (PlannerPlanParseException e) {
      return e;
    }
  }

  /**
   * A same-package fake {@link PromptCataloguePort} (mirrors {@code FakeModelProviderTest}'s): a
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
        messages.add(
            new ChatMessage(
                com.example.agentflow.ai.application.MessageRole.SYSTEM, "FAKE_PLANNER_POLICY"));
        messages.add(
            new ChatMessage(com.example.agentflow.ai.application.MessageRole.USER, userContent));
        return messages;
      }
    };
  }

  private static String hex64(char digit) {
    return new String(new char[64]).replace('\0', digit);
  }
}
