package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ToolInvocationRecord} (S08-13): the immutable metadata row of one tool
 * call attempt. The acceptance it protects is <em>an operator can determine which tool executed and
 * how its call concluded without seeing secrets</em> — so the row validates the identity {@code
 * (name, version)}, the 1-based attempt, the non-negative latency and the bounded outcome, and the
 * factory assigns the row id the same way {@code AuditEvent.created} does. It never carries
 * arguments or payload: there is nothing to redact because there is nothing sensitive to store
 * (invariant 8).
 */
class ToolInvocationRecordTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Test
  void recorded_assigns_the_id_and_keeps_every_value() {
    ToolInvocationRecord record =
        ToolInvocationRecord.recorded(
            TENANT,
            RUN,
            "step-1",
            "calculator",
            "1.0.0",
            ToolRiskClass.READ_ONLY,
            2,
            ToolInvocationOutcome.TIMED_OUT,
            5,
            null,
            "tool_timeout",
            null);

    assertThat(record.id()).isNotNull();
    assertThat(record.tenantId()).isEqualTo(TENANT);
    assertThat(record.runId()).isEqualTo(RUN);
    assertThat(record.stepRunId()).isEqualTo("step-1");
    assertThat(record.toolName()).isEqualTo("calculator");
    assertThat(record.toolVersion()).isEqualTo("1.0.0");
    assertThat(record.riskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
    assertThat(record.attempt()).isEqualTo(2);
    assertThat(record.outcome()).isEqualTo(ToolInvocationOutcome.TIMED_OUT);
    assertThat(record.latencyMs()).isEqualTo(5);
    assertThat(record.outputReference()).isNull();
    assertThat(record.errorClass()).isEqualTo("tool_timeout");
    assertThat(record.errorSummary()).isNull();
  }

  @Test
  void the_step_run_and_failure_fields_are_optional() {
    ToolInvocationRecord record =
        ToolInvocationRecord.recorded(
            TENANT,
            RUN,
            null,
            "calculator",
            "1.0.0",
            ToolRiskClass.WRITE,
            1,
            ToolInvocationOutcome.SUCCEEDED,
            0,
            "step-run:step-1",
            null,
            null);

    assertThat(record.stepRunId()).isNull();
    assertThat(record.errorClass()).isNull();
    assertThat(record.errorSummary()).isNull();
    assertThat(record.outputReference()).isEqualTo("step-run:step-1");
  }

  @Test
  void a_null_tenant_or_run_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    null,
                    RUN,
                    "step-1",
                    "calculator",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenant");

    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    TENANT,
                    null,
                    "step-1",
                    "calculator",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run");
  }

  @Test
  void a_blank_tool_identity_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    TENANT,
                    RUN,
                    null,
                    " ",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool name");

    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    TENANT,
                    RUN,
                    null,
                    "calculator",
                    "",
                    ToolRiskClass.READ_ONLY,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool version");
  }

  @Test
  void a_missing_risk_class_or_outcome_is_rejected() {
    assertThatThrownBy(
            () ->
                new ToolInvocationRecord(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    "calculator",
                    "1.0.0",
                    null,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("risk class");

    assertThatThrownBy(
            () ->
                new ToolInvocationRecord(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    "calculator",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    1,
                    null,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome");
  }

  @Test
  void a_zero_attempt_or_a_negative_latency_is_rejected() {
    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    TENANT,
                    RUN,
                    null,
                    "calculator",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    0,
                    ToolInvocationOutcome.SUCCEEDED,
                    0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt");

    assertThatThrownBy(
            () ->
                ToolInvocationRecord.recorded(
                    TENANT,
                    RUN,
                    null,
                    "calculator",
                    "1.0.0",
                    ToolRiskClass.READ_ONLY,
                    1,
                    ToolInvocationOutcome.SUCCEEDED,
                    -1,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("latency");
  }
}
