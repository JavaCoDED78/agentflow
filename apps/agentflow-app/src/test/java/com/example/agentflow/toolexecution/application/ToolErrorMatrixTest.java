package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.toolexecution.domain.CalculationException;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.SchemaValidationException;
import com.example.agentflow.toolexecution.domain.SchemaValidator;
import com.example.agentflow.toolexecution.domain.TicketLookup;
import com.example.agentflow.toolexecution.domain.TicketOutcome;
import com.example.agentflow.toolexecution.domain.TicketResult;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import com.example.agentflow.toolexecution.domain.ToolExecutionContext;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import com.example.agentflow.toolexecution.persistence.ToolInvocationRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The tool error matrix (S08-14): every error class a tool call can hit, driven through the real
 * call pipeline — {@link ToolRegistry} → {@link ToolInvocationPolicy} → {@link SchemaValidator} →
 * {@link BoundedToolExecutor} → {@link ToolInvocationHistoryService} — with the <b>retry / state /
 * audit</b> triple the plan's acceptance asks for pinned per class. The component suites ({@link
 * BoundedToolExecutorTest}, {@link ToolInvocationPolicyTest}, {@link
 * ToolInvocationHistoryServiceTest}) prove each piece in isolation; this suite proves the matrix
 * <em>across</em> the pieces, composed in the order the future {@code TOOL_CALL} {@code
 * StepHandler} will drive them (see {@link #invokeAutomatic}).
 *
 * <table>
 *   <caption>The matrix — each class gives exactly one retry / state / audit result</caption>
 *   <tr><th>Error class</th><th>Retry</th><th>State / outcome</th><th>History</th><th>Audit</th></tr>
 *   <tr><td>Unknown tool</td><td>never — permanent</td><td>{@code DENIED} / {@code unknown_tool},
 *     the call never starts</td><td>none</td><td>none</td></tr>
 *   <tr><td>Denied permission</td><td>never — permanent</td><td>{@code DENIED} / {@code
 *     missing_scope}, the missing scopes named</td><td>none</td><td>none</td></tr>
 *   <tr><td>Approval-required action (no grant)</td><td>never — a retry without a human decision
 *     cannot succeed</td><td>{@code DENIED} / {@code missing_approval} (the taxonomy's {@code
 *     tool_policy_denial})</td><td>none</td><td>none</td></tr>
 *   <tr><td>Invalid input</td><td>never — permanent contract failure</td><td>{@link
 *     SchemaValidationException} before the tool body runs</td><td>none</td><td>none</td></tr>
 *   <tr><td>Invalid output</td><td>never — permanent contract failure</td><td>{@link
 *     SchemaValidationException} after the call; the result is never accepted</td><td>{@code
 *     SUCCEEDED} (the call concluded; the contract gate is separate)</td><td>per the risk
 *     class</td></tr>
 *   <tr><td>Timeout</td><td>yes — ambiguous, bounded backoff (invariant 6)</td><td>{@code
 *     tool_timeout}, {@code retryable == true}</td><td>{@link ToolInvocationOutcome#TIMED_OUT},
 *     no output reference</td><td>yes for a started side-effect call — the effect may have
 *     completed</td></tr>
 *   <tr><td>Transient failure (overload)</td><td>yes — a later attempt with spare capacity</td>
 *     <td>{@code concurrency_limited}, {@code retryable == true}</td><td>{@link
 *     ToolInvocationOutcome#THROTTLED}</td><td>none — the call never started</td></tr>
 *   <tr><td>Transient failure (flapping backend)</td><td>yes — while attempts remain; terminal at
 *     the retry bound</td><td>recovers within the bound or a classified terminal transient
 *     failure</td><td>n/a (tool-level retry)</td><td>n/a</td></tr>
 *   <tr><td>Permanent tool fault</td><td>never — a retry with identical input fails identically
 *     </td><td>the tool's own exception type, rethrown unchanged</td><td>{@link
 *     ToolInvocationOutcome#FAULTED}, redacted stable token</td><td>yes for a started side-effect
 *     call</td></tr>
 * </table>
 *
 * <p>DB-free, no model/Kafka/system-clock dependency: the audit assertions drive the real {@link
 * ToolInvocationHistoryService} against mocked {@link ToolInvocationRepository} / {@link
 * AuditService} (the established convention of {@link ToolInvocationHistoryServiceTest}), the slow
 * calls sleep on the injected pool, and the overload row rendezvous on latches, so every outcome is
 * deterministic.
 */
@ExtendWith(MockitoExtension.class)
class ToolErrorMatrixTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(30);

  private static final String WRITE = "matrix-writer";
  private static final String GATED = "matrix-gated";
  private static final String STRICT = "matrix-strict";

  @Mock private ToolInvocationRepository repository;

  @Mock private AuditService auditService;

  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void shutDownPool() {
    pool.shutdownNow();
  }

  // ---------------------------------------------------------------------------------------------
  // policy denials — the call never starts, so there is no history row and no audit record
  // ---------------------------------------------------------------------------------------------

  @Test
  void an_unknown_tool_is_denied_before_the_call_and_leaves_no_trace() {
    ToolInvocationPolicy guard = new ToolInvocationPolicy(new ToolRegistry());
    RecordingHistory history = new RecordingHistory();
    AtomicBoolean invoked = new AtomicBoolean();

    // The pipeline stops at the policy gate: the tool body never runs and nothing is recorded.
    assertThatThrownBy(
            () ->
                invokeAutomatic(
                    guard,
                    writerTool(GENEROUS_TIMEOUT),
                    context(Set.of("tool:write")),
                    Map.of(),
                    () -> {
                      invoked.set(true);
                      return Map.of();
                    },
                    history))
        .isInstanceOf(PolicyDenial.class)
        .extracting(e -> ((PolicyDenial) e).decision().reason())
        .isEqualTo(ToolInvocationPolicy.REASON_UNKNOWN_TOOL);

    assertThat(invoked).isFalse();
    assertThat(history.records).isEmpty();
    verifyNoInteractions(repository, auditService);
  }

  @Test
  void a_denied_permission_names_the_missing_scopes_and_leaves_no_trace() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(writerTool(GENEROUS_TIMEOUT));
    ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);
    RecordingHistory history = new RecordingHistory();
    AtomicBoolean invoked = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                invokeAutomatic(
                    guard,
                    writerTool(GENEROUS_TIMEOUT),
                    context(Set.of()),
                    Map.of(),
                    () -> {
                      invoked.set(true);
                      return Map.of();
                    },
                    history))
        .isInstanceOf(PolicyDenial.class)
        .satisfies(
            e -> {
              PermissionDecision decision = ((PolicyDenial) e).decision();
              assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_MISSING_SCOPE);
              assertThat(decision.missingScopes()).containsExactly("tool:write");
            });

    assertThat(invoked).isFalse();
    assertThat(history.records).isEmpty();
    verifyNoInteractions(repository, auditService);
  }

  @Test
  void an_approval_required_action_never_runs_on_the_automatic_path() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(gatedTool());
    ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);
    RecordingHistory history = new RecordingHistory();
    AtomicBoolean invoked = new AtomicBoolean();

    // The caller holds every required scope; only the human decision is missing. The automatic
    // path attests no grant, so the guard refuses with the taxonomy's tool_policy_denial — a
    // retry without a human decision cannot succeed, so this is permanent, not retryable.
    assertThatThrownBy(
            () ->
                invokeAutomatic(
                    guard,
                    gatedTool(),
                    context(Set.of("tool:gated")),
                    Map.of(),
                    () -> {
                      invoked.set(true);
                      return Map.of();
                    },
                    history))
        .isInstanceOf(PolicyDenial.class)
        .satisfies(
            e -> {
              PermissionDecision decision = ((PolicyDenial) e).decision();
              assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.DENIED);
              assertThat(decision.reason()).isEqualTo(ToolInvocationPolicy.REASON_MISSING_APPROVAL);
              // Not a scope problem: the missing-scope set is empty.
              assertThat(decision.missingScopes()).isEmpty();
            });

    // The refusal precedes the call: no tool body, no history row, no audit record, no side
    // effect.
    assertThat(invoked).isFalse();
    assertThat(history.records).isEmpty();
    verifyNoInteractions(repository, auditService);
  }

  @Test
  void an_approval_required_action_proceeds_when_the_grant_is_attested() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(gatedTool());
    ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);
    RecordingHistory history = new RecordingHistory();
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, history);

    // The grant attestation is the seam the approval lifecycle (S11) feeds: with the stored
    // decision re-checked, the guard allows the call and the pipeline concludes normally.
    PermissionDecision decision = guard.decide(context(Set.of("tool:gated")), GATED, "1", true);
    assertThat(decision.outcome()).isEqualTo(ToolExecutionOutcome.ALLOWED);

    String result =
        executor.execute(context(Set.of("tool:gated")), gatedTool(), () -> "gated-done");

    assertThat(result).isEqualTo("gated-done");
    assertThat(history.records).hasSize(1);
    assertThat(history.records.get(0).outcome()).isEqualTo(ToolInvocationOutcome.SUCCEEDED);
  }

  // ---------------------------------------------------------------------------------------------
  // contract failures — permanent, classified by the schema gate before/after the call
  // ---------------------------------------------------------------------------------------------

  @Test
  void invalid_input_is_rejected_before_the_tool_body_runs() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(strictTool());
    ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);
    RecordingHistory history = new RecordingHistory();
    AtomicBoolean invoked = new AtomicBoolean();

    // {} violates the strict input contract (missing the required ticketId): the input gate runs
    // before the executor, so the tool body never starts.
    assertThatThrownBy(
            () ->
                invokeAutomatic(
                    guard,
                    strictTool(),
                    context(Set.of()),
                    Map.of(),
                    () -> {
                      invoked.set(true);
                      return Map.of("ticketId", "TKT-1001");
                    },
                    history))
        .isInstanceOf(SchemaValidationException.class)
        .extracting(e -> ((SchemaValidationException) e).reason())
        .isEqualTo("missing-required");

    assertThat(invoked).isFalse();
    assertThat(history.records).isEmpty();
    verifyNoInteractions(repository, auditService);
  }

  @Test
  void an_invalid_output_is_rejected_after_the_call_and_blocks_the_success() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(strictTool());
    ToolInvocationPolicy guard = new ToolInvocationPolicy(registry);
    RecordingHistory history = new RecordingHistory();

    // The tool returns a payload outside its output contract: the call itself concluded (the
    // history records SUCCEEDED with the output reference — the history answers WHICH tool ran
    // and HOW its call concluded), but the output gate rejects the result, so it is never
    // accepted, forwarded or checkpointed.
    assertThatThrownBy(
            () ->
                invokeAutomatic(
                    guard,
                    strictTool(),
                    context(Set.of()),
                    Map.of("ticketId", "TKT-1001"),
                    () -> Map.of("ticketId", "TKT-1001", "unexpected", "value"),
                    history))
        .isInstanceOf(SchemaValidationException.class)
        .extracting(e -> ((SchemaValidationException) e).reason())
        .isEqualTo("unknown-property");

    assertThat(history.records).hasSize(1);
    assertThat(history.records.get(0).outcome()).isEqualTo(ToolInvocationOutcome.SUCCEEDED);
    assertThat(history.records.get(0).outputReference()).isEqualTo("step-run:step-1");
  }

  // ---------------------------------------------------------------------------------------------
  // bounded outcomes — retryable, classified, audited exactly when the call started
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_timeout_is_retryable_ambiguous_and_audited_for_a_side_effect() {
    ToolInvocationHistoryService historyService =
        new ToolInvocationHistoryService(repository, auditService);
    ExecutorService single = Executors.newSingleThreadExecutor();
    try {
      BoundedToolExecutor executor = new BoundedToolExecutor(single, 1, 1, historyService);

      assertThatThrownBy(
              () ->
                  executor.execute(
                      context(Set.of("tool:write")),
                      writerTool(Duration.ofMillis(50)),
                      sleepThenReturn(Duration.ofSeconds(2), Map.of())))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .satisfies(
              e -> {
                ToolExecutionThrottleException throttle = (ToolExecutionThrottleException) e;
                assertThat(throttle.reason())
                    .isEqualTo(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT);
                // Retryable, but the outcome is ambiguous: the interrupt freed the worker, it did
                // not undo a side effect that may have completed (invariant 6).
                assertThat(throttle.retryable()).isTrue();
              });

      // State: the history records the ambiguous outcome with no output reference; audit: a
      // started side-effect call is audited because its effect may have completed.
      verify(repository)
          .insert(
              argThat(
                  record ->
                      record.outcome() == ToolInvocationOutcome.TIMED_OUT
                          && record
                              .errorClass()
                              .equals(ToolExecutionThrottleException.REASON_TOOL_TIMEOUT)
                          && record.outputReference() == null));
      verify(auditService)
          .record(
              eq(TENANT),
              eq("tool-executor"),
              eq(AuditAction.INVOKE),
              eq(AuditResourceType.TOOL),
              isNull(),
              eq("tool=" + WRITE + ":1"));
    } finally {
      single.shutdownNow();
    }
  }

  @Test
  void an_overloaded_call_is_retryable_never_started_and_not_audited() throws Exception {
    ToolInvocationHistoryService historyService =
        new ToolInvocationHistoryService(repository, auditService);
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 1, 8, historyService);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Holder firstResult = new Holder();
    Thread firstCaller =
        runInThread(
            () ->
                firstResult.value =
                    blockedCall(executor, entered, release, context(Set.of("tool:write"))));
    try {
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(
              () ->
                  executor.execute(
                      context(Set.of("tool:write")), writerTool(GENEROUS_TIMEOUT), () -> Map.of()))
          .isInstanceOf(ToolExecutionThrottleException.class)
          .satisfies(
              e -> {
                ToolExecutionThrottleException throttle = (ToolExecutionThrottleException) e;
                assertThat(throttle.reason())
                    .isEqualTo(ToolExecutionThrottleException.REASON_CONCURRENCY_LIMITED);
                assertThat(throttle.retryable()).isTrue();
              });

      // State: rejected before start — the history carries THROTTLED with no output reference;
      // audit: the call never started, so nothing is audited.
      verify(repository)
          .insert(
              argThat(
                  record ->
                      record.outcome() == ToolInvocationOutcome.THROTTLED
                          && record.outputReference() == null));
      verifyNoInteractions(auditService);
    } finally {
      release.countDown();
      firstCaller.join(5_000);
    }
    assertThat(firstResult.value).isEqualTo("first-done");
  }

  // ---------------------------------------------------------------------------------------------
  // transient vs permanent at the tool level — the retry bound decides
  // ---------------------------------------------------------------------------------------------

  @Test
  void a_transient_flap_recovers_within_the_retry_bound() {
    // Draws in [0, 1): below the flap probability the attempt flaps, at/above it answers. A flap
    // then a clean attempt is the transient class a retry exists for — recovery costs the extra
    // attempt.
    AtomicInteger next = new AtomicInteger();
    double[] draws = {0.0, 0.9};
    TicketResult recovered =
        TicketLookup.lookup("TKT-1001", () -> draws[next.getAndIncrement() % draws.length], 3);

    assertThat(recovered.outcome()).isEqualTo(TicketOutcome.SUCCESS);
    assertThat(recovered.attemptsUsed()).isEqualTo(2);
    // While attempts remain, the retry policy allows another attempt; at the bound it does not.
    assertThat(RetryPolicy.ofDefaults().isRetryAllowed(1)).isTrue();
    assertThat(RetryPolicy.ofDefaults().isRetryAllowed(3)).isFalse();
  }

  @Test
  void a_transient_failure_that_never_recovers_is_terminal_at_the_retry_bound() {
    // Every draw flaps: the call exhausts its attempts and concludes as a classified terminal
    // transient failure — retried up to the bound, never beyond it.
    TicketResult exhausted = TicketLookup.lookup("TKT-1001", () -> 0.0, 3);

    assertThat(exhausted.outcome()).isEqualTo(TicketOutcome.TRANSIENT_FAILURE);
    assertThat(exhausted.attemptsUsed()).isEqualTo(3);
  }

  @Test
  void a_permanent_tool_fault_keeps_its_own_type_and_is_audited_for_a_side_effect() {
    ToolInvocationHistoryService historyService =
        new ToolInvocationHistoryService(repository, auditService);
    BoundedToolExecutor executor = new BoundedToolExecutor(pool, 2, 1, historyService);
    CalculationException toolFault = new CalculationException("division-by-zero");

    assertThatThrownBy(
            () ->
                executor.execute(
                    context(Set.of("tool:write")),
                    writerTool(GENEROUS_TIMEOUT),
                    () -> {
                      throw toolFault;
                    }))
        .isSameAs(toolFault);

    // State: the fault keeps its own type (the caller classifies a permanent contract failure
    // without parsing a message); history: FAULTED with the redacted stable token, never the
    // message; audit: the side-effect call started, so it is audited.
    verify(repository)
        .insert(
            argThat(
                record ->
                    record.outcome() == ToolInvocationOutcome.FAULTED
                        && record.errorClass().equals("division-by-zero")
                        && record.errorSummary().equals("CalculationException(division-by-zero)")
                        && record.outputReference() == null));
    verify(auditService)
        .record(
            eq(TENANT),
            eq("tool-executor"),
            eq(AuditAction.INVOKE),
            eq(AuditResourceType.TOOL),
            isNull(),
            eq("tool=" + WRITE + ":1"));
  }

  // ---------------------------------------------------------------------------------------------
  // the composed call pipeline (the order the TOOL_CALL StepHandler will drive)
  // ---------------------------------------------------------------------------------------------

  /**
   * Drive one call through the real pipeline in the handler's order: policy guard (automatic path —
   * no grant attested) → input contract → bounded executor → output contract. A policy refusal
   * surfaces as {@link PolicyDenial} carrying the guard's decision, so a denied call can be told
   * apart from a tool fault.
   */
  private <T> T invokeAutomatic(
      ToolInvocationPolicy guard,
      ToolDescriptor descriptor,
      ToolExecutionContext context,
      Object arguments,
      ToolInvocation<T> call,
      ToolInvocationListener listener) {
    PermissionDecision decision = guard.decide(context, descriptor.name(), descriptor.version());
    if (decision.outcome() == ToolExecutionOutcome.DENIED) {
      throw new PolicyDenial(decision);
    }
    SchemaValidator.of(descriptor.inputSchema()).validate(arguments);
    T result = new BoundedToolExecutor(pool, 2, 1, listener).execute(context, descriptor, call);
    SchemaValidator.of(descriptor.outputSchema()).validate(result);
    return result;
  }

  /** The policy gate's refusal, carrying the {@link PermissionDecision} for the assertions. */
  private static final class PolicyDenial extends RuntimeException {

    private final PermissionDecision decision;

    private PolicyDenial(PermissionDecision decision) {
      super("tool call refused by the invocation policy: " + decision.reason());
      this.decision = decision;
    }

    private PermissionDecision decision() {
      return decision;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  /** In-memory listener capturing every reported record, for the history assertions. */
  private static final class RecordingHistory implements ToolInvocationListener {

    private final List<ToolInvocationRecord> records = new ArrayList<>();

    @Override
    public void record(ToolInvocationRecord record) {
      records.add(record);
    }
  }

  /** A plain holder for the blocked caller's result (the surrounding test is single-threaded). */
  private static final class Holder {
    private String value;
  }

  private String blockedCall(
      BoundedToolExecutor executor,
      CountDownLatch entered,
      CountDownLatch release,
      ToolExecutionContext context) {
    try {
      return executor.execute(
          context,
          writerTool(GENEROUS_TIMEOUT),
          () -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return "first-done";
          });
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Thread runInThread(ThrowingRunnable body) {
    Thread thread =
        new Thread(
            () -> {
              try {
                body.run();
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    thread.start();
    return thread;
  }

  private static ToolInvocation<Map<String, Object>> sleepThenReturn(
      Duration sleep, Map<String, Object> value) {
    return () -> {
      Thread.sleep(sleep.toMillis());
      return value;
    };
  }

  /** A scoped {@code WRITE} tool allowed by the automatic path when the scope is held. */
  private static ToolDescriptor writerTool(Duration timeout) {
    return ToolDescriptor.of(
        "tool-matrix-writer-1",
        WRITE,
        "1",
        "A scoped side-effect tool used only by the error-matrix tests.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of("tool:write"),
        ToolRiskClass.WRITE,
        timeout,
        RetryPolicy.ofDefaults(),
        false);
  }

  /** An approval-gated {@code EXTERNAL_SIDE_EFFECT} tool: scopes plus a human decision. */
  private static ToolDescriptor gatedTool() {
    return ToolDescriptor.of(
        "tool-matrix-gated-1",
        GATED,
        "1",
        "An approval-gated side-effect tool used only by the error-matrix tests.",
        JsonSchema.of(schema("object")),
        JsonSchema.of(schema("object")),
        Set.of("tool:gated"),
        ToolRiskClass.EXTERNAL_SIDE_EFFECT,
        GENEROUS_TIMEOUT,
        RetryPolicy.ofDefaults(),
        true);
  }

  /** A read-only tool with a strict input/output contract (required field, no extra fields). */
  private static ToolDescriptor strictTool() {
    Map<String, Object> strictSchema =
        Map.of(
            "$schema",
            "https://json-schema.org/draft/2020-12/schema",
            "type",
            "object",
            "properties",
            Map.of("ticketId", Map.of("type", "string")),
            "required",
            List.of("ticketId"),
            "additionalProperties",
            false);
    return ToolDescriptor.of(
        "tool-matrix-strict-1",
        STRICT,
        "1",
        "A strict-contract read-only tool used only by the error-matrix tests.",
        JsonSchema.of(strictSchema),
        JsonSchema.of(strictSchema),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        GENEROUS_TIMEOUT,
        RetryPolicy.ofDefaults(),
        false);
  }

  private static Map<String, Object> schema(String type) {
    return Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", type);
  }

  private static ToolExecutionContext context(Set<String> capabilities) {
    return ToolExecutionContext.of(TENANT, "alice", RUN, "step-1", "corr-1", capabilities);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
