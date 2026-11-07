package com.example.agentflow.toolexecution.domain;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import java.util.UUID;

/**
 * One durable row of the tool invocation history (S08-13, {@code tool_invocations}): the metadata
 * of a single tool call attempt that ran through the bounded executor — which tool, which attempt,
 * how long it occupied a worker, how it concluded, and where its output lives.
 *
 * <p>The record is <b>metadata by design</b>: it never carries the call arguments, the tool output
 * payload, a prompt or a document, so the redaction the plan asks for is structural, not a
 * scrubbing pass (invariant 8). The output is <em>referenced</em>, not stored — {@code
 * outputReference} points at the owning step run's checkpoint (a {@code step-run:<step-id>}
 * pointer), keeping the payload's single owner the module that checkpoints it. The {@code
 * errorSummary} is a short redacted reason (the faulting type plus its stable reason code, bounded
 * by the writer), never raw error text — an exception message can quote a secret.
 *
 * <p>The value is immutable, and its canonical constructor validates the invariants the history
 * relies on: the tenant and run come from the verified call context (never {@code null}), the tool
 * identity is the registry's {@code (name, version)} pair, the attempt is 1-based and the latency
 * non-negative. It reads no time, no randomness and no I/O (invariant 10); the {@code id} is
 * assigned by {@link #recorded} (the same seam {@code AuditEvent.created} uses).
 */
public record ToolInvocationRecord(
    UUID id,
    UUID tenantId,
    UUID runId,
    String stepRunId,
    String toolName,
    String toolVersion,
    ToolRiskClass riskClass,
    int attempt,
    ToolInvocationOutcome outcome,
    long latencyMs,
    String outputReference,
    String errorClass,
    String errorSummary) {

  /**
   * Builds a history row for one concluded call, assigning the row id.
   *
   * @param tenantId the owning tenant (from the verified call context); never {@code null}
   * @param runId the run the call belongs to (from the verified call context); never {@code null}
   * @param stepRunId the step run the call belongs to, or {@code null} when the context carries
   *     none
   * @param toolName the tool's registered name; non-blank
   * @param toolVersion the tool's registered version; non-blank
   * @param riskClass the descriptor's risk class at call time; never {@code null}
   * @param attempt the per-call attempt number (1-based)
   * @param outcome the bounded outcome class; never {@code null}
   * @param latencyMs wall time the admitted call occupied, in milliseconds; non-negative
   * @param outputReference {@code step-run:<step-id>} pointer to the checkpoint holding the output,
   *     or {@code null} when the call produced no output
   * @param errorClass the safe failure token, or {@code null} on success
   * @param errorSummary the short redacted reason, or {@code null} on success
   * @return the immutable history row
   */
  public static ToolInvocationRecord recorded(
      UUID tenantId,
      UUID runId,
      String stepRunId,
      String toolName,
      String toolVersion,
      ToolRiskClass riskClass,
      int attempt,
      ToolInvocationOutcome outcome,
      long latencyMs,
      String outputReference,
      String errorClass,
      String errorSummary) {
    return new ToolInvocationRecord(
        UUID.randomUUID(),
        tenantId,
        runId,
        stepRunId,
        toolName,
        toolVersion,
        riskClass,
        attempt,
        outcome,
        latencyMs,
        outputReference,
        errorClass,
        errorSummary);
  }

  /**
   * @param id the row id; never {@code null}
   * @param tenantId the owning tenant (from the verified call context); never {@code null}
   * @param runId the run the call belongs to; never {@code null}
   * @param stepRunId the step run the call belongs to, or {@code null}
   * @param toolName the tool's registered name; non-blank
   * @param toolVersion the tool's registered version; non-blank
   * @param riskClass the descriptor's risk class at call time; never {@code null}
   * @param attempt the per-call attempt number; at least {@code 1}
   * @param outcome the bounded outcome class; never {@code null}
   * @param latencyMs wall time the call occupied, in milliseconds; non-negative
   * @param outputReference the checkpoint pointer, or {@code null} when there is no output
   * @param errorClass the safe failure token, or {@code null} on success
   * @param errorSummary the short redacted reason, or {@code null} on success
   */
  public ToolInvocationRecord {
    if (id == null) {
      throw new IllegalArgumentException("tool invocation id must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("tool invocation tenant must not be null");
    }
    if (runId == null) {
      throw new IllegalArgumentException("tool invocation run must not be null");
    }
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("tool invocation tool name must not be blank");
    }
    if (toolVersion == null || toolVersion.isBlank()) {
      throw new IllegalArgumentException("tool invocation tool version must not be blank");
    }
    if (riskClass == null) {
      throw new IllegalArgumentException("tool invocation risk class must not be null");
    }
    if (attempt < 1) {
      throw new IllegalArgumentException(
          "tool invocation attempt must be at least 1 but was " + attempt);
    }
    if (outcome == null) {
      throw new IllegalArgumentException("tool invocation outcome must not be null");
    }
    if (latencyMs < 0) {
      throw new IllegalArgumentException(
          "tool invocation latency must not be negative but was " + latencyMs);
    }
  }
}
