package com.example.agentflow.toolexecution.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import com.example.agentflow.toolexecution.persistence.ToolInvocationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the {@link ToolInvocationHistoryService} (S08-13): every concluded call appends
 * exactly one invocation-history row, and only a <em>started</em> call whose risk class can change
 * state ({@code WRITE} / {@code EXTERNAL_SIDE_EFFECT}) is audited — a read-only call changes
 * nothing, and a throttled call never started, so auditing either would only grow the trail with
 * noise. The service fails loudly without a database (S01-02), like {@link AuditService} does.
 */
@ExtendWith(MockitoExtension.class)
class ToolInvocationHistoryServiceTest {

  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Mock private ToolInvocationRepository repository;

  @Mock private AuditService auditService;

  @Test
  void a_read_only_call_appends_history_but_is_not_audited() {
    ToolInvocationHistoryService service = service();
    ToolInvocationRecord record = record(ToolRiskClass.READ_ONLY, ToolInvocationOutcome.SUCCEEDED);

    service.record(record);

    verify(repository).insert(record);
    verifyNoInteractions(auditService);
  }

  @Test
  void a_succeeded_side_effect_call_appends_history_and_is_audited_with_the_tool_identity() {
    ToolInvocationHistoryService service = service();
    ToolInvocationRecord record = record(ToolRiskClass.WRITE, ToolInvocationOutcome.SUCCEEDED);

    service.record(record);

    verify(repository).insert(record);
    // The audit names the platform actor and the (name, version) identity — never arguments or a
    // payload (invariant 8).
    verify(auditService)
        .record(
            eq(TENANT),
            eq("tool-executor"),
            eq(AuditAction.INVOKE),
            eq(AuditResourceType.TOOL),
            isNull(),
            eq("tool=calculator:1.0.0"));
  }

  @Test
  void a_timed_out_side_effect_call_is_audited_because_the_side_effect_may_have_completed() {
    ToolInvocationHistoryService service = service();
    ToolInvocationRecord record =
        record(ToolRiskClass.EXTERNAL_SIDE_EFFECT, ToolInvocationOutcome.TIMED_OUT);

    service.record(record);

    verify(repository).insert(record);
    verify(auditService)
        .record(
            eq(TENANT),
            eq("tool-executor"),
            eq(AuditAction.INVOKE),
            eq(AuditResourceType.TOOL),
            isNull(),
            eq("tool=calculator:1.0.0"));
  }

  @Test
  void a_throttled_side_effect_call_is_not_audited_because_it_never_started() {
    ToolInvocationHistoryService service = service();
    ToolInvocationRecord record = record(ToolRiskClass.WRITE, ToolInvocationOutcome.THROTTLED);

    service.record(record);

    verify(repository).insert(record);
    verifyNoInteractions(auditService);
  }

  @Test
  void a_null_record_is_rejected() {
    ToolInvocationHistoryService service = service();

    assertThatThrownBy(() -> service.record(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("record");

    verifyNoInteractions(repository, auditService);
  }

  @Test
  void without_a_database_the_service_fails_loudly_instead_of_dropping_history() {
    ToolInvocationHistoryService service = new ToolInvocationHistoryService(null, auditService);

    assertThatThrownBy(
            () -> service.record(record(ToolRiskClass.READ_ONLY, ToolInvocationOutcome.SUCCEEDED)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");

    // The bounded executor catches this and degrades to a warning — the decision is its, not ours.
    verifyNoInteractions(auditService);
  }

  private ToolInvocationHistoryService service() {
    return new ToolInvocationHistoryService(repository, auditService);
  }

  private static ToolInvocationRecord record(
      ToolRiskClass riskClass, ToolInvocationOutcome outcome) {
    return ToolInvocationRecord.recorded(
        TENANT,
        RUN,
        "step-1",
        "calculator",
        "1.0.0",
        riskClass,
        1,
        outcome,
        5,
        outcome == ToolInvocationOutcome.SUCCEEDED ? "step-run:step-1" : null,
        null,
        null);
  }
}
