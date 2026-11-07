package com.example.agentflow.toolexecution.application;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.toolexecution.domain.ToolInvocationOutcome;
import com.example.agentflow.toolexecution.domain.ToolInvocationRecord;
import com.example.agentflow.toolexecution.persistence.ToolInvocationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The tool invocation history (S08-13): the {@link ToolInvocationListener} the bounded executor
 * reports every concluded call to. It appends one metadata row to the {@code tool_invocations}
 * table the {@code toolexecution} module owns, and — because the plan requires side-effect calls to
 * be audited — appends an {@code audit_events} record for every <em>started</em> call whose
 * descriptor's risk class is not {@link ToolRiskClass#READ_ONLY}.
 *
 * <p>The audit decision is deliberately narrow: a read-only tool (the calculator, the report
 * formatter, the ticket lookup) changes nothing, so auditing it would only grow the trail with
 * noise; a {@code WRITE} or {@code EXTERNAL_SIDE_EFFECT} call that was <em>started</em> ({@link
 * ToolInvocationOutcome#SUCCEEDED}, {@link ToolInvocationOutcome#TIMED_OUT} — the ambiguous
 * outcome, whose side effect may have completed — or {@link ToolInvocationOutcome#FAULTED}) is
 * audited with the tool's {@code (name, version)} identity in the redacted metadata slot. A {@link
 * ToolInvocationOutcome#THROTTLED} call never started: there is nothing to audit, and the retry
 * that follows is the designed path.
 *
 * <p>The bean is always registered; the {@link ToolInvocationRepository} it persists through is
 * only present with a {@code DataSource}, so {@link #record} fails loudly (like {@link
 * AuditService}) when no database is configured — the bounded executor catches that, logs a warning
 * and keeps the call's outcome, because history is observability, not business state. The caller's
 * context (not this service) owns the transaction boundary: the history row commits on its own
 * connection when no ambient transaction exists.
 */
@Service
public class ToolInvocationHistoryService implements ToolInvocationListener {

  private final ToolInvocationRepository repository;
  private final AuditService auditService;

  public ToolInvocationHistoryService(
      @Autowired(required = false) ToolInvocationRepository repository, AuditService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  /**
   * Append the invocation-history row and, for a started side-effect call, the audit record.
   *
   * @param record the concluded call's metadata row; never {@code null}
   * @throws IllegalStateException if no database is configured
   */
  @Override
  public void record(ToolInvocationRecord record) {
    if (record == null) {
      throw new IllegalArgumentException("tool invocation record must not be null");
    }
    if (repository == null) {
      throw new IllegalStateException(
          "tool invocation history unavailable: no database connection is configured");
    }
    repository.insert(record);
    if (auditable(record)) {
      auditService.record(
          record.tenantId(),
          actorOf(record),
          AuditAction.INVOKE,
          AuditResourceType.TOOL,
          null,
          "tool=" + record.toolName() + ":" + record.toolVersion());
    }
  }

  /**
   * @return {@code true} when the call was started (not throttled) and its risk class can change
   *     state — the plan's "side-effect calls are audited"
   */
  private static boolean auditable(ToolInvocationRecord record) {
    return record.riskClass() != ToolRiskClass.READ_ONLY
        && record.outcome() != ToolInvocationOutcome.THROTTLED;
  }

  /**
   * The audited actor: tool calls are executed by the platform on the run's behalf, so there is no
   * human subject to name; the tenant and run stay in the invocation row itself.
   */
  private static String actorOf(ToolInvocationRecord record) {
    return "tool-executor";
  }
}
