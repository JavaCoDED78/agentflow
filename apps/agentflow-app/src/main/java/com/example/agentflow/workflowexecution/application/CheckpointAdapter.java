package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.domain.Checkpoint;
import com.example.agentflow.workflowexecution.persistence.CheckpointSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-step checkpoint seam (S07-07, glossary "Checkpoint"). It is the single place a completed
 * step's output is turned into a reusable, redacted, size-bounded checkpoint, and the single place
 * recovery asks "is this step's result still valid?". The future step executor calls {@link
 * #saveCheckpoint} after it transitions a step to {@code SUCCEEDED}, and {@link
 * #canReuseCheckpoint} before it runs one.
 *
 * <p>Each {@link #saveCheckpoint} call:
 *
 * <ol>
 *   <li>redacts the raw output through {@link CheckpointRedactor} (invariant 8) so no secret lands
 *       in the row;
 *   <li>bounds the redacted output to {@code agentflow.checkpoint.max-inline-bytes}; an output that
 *       fits is persisted inline (the small, hot path a pure step such as a calculator result
 *       takes); one that does not fit is <em>not</em> persisted inline (it is externalized to
 *       object storage by S15-06) and is therefore treated as non-reusable; and
 *   <li>persists the checkpoint through {@link WorkflowStepRunRepository#saveCheckpoint}, which
 *       guards the write by the tenant, the {@code lease_epoch} fence and the step being {@code
 *       SUCCEEDED} (invariants 1 / 4 / 5).
 * </ol>
 *
 * <p>Each {@link #canReuseCheckpoint} call answers the reuse decision (S07-07 acceptance): the
 * stored checkpoint exists for {@code (tenant, run, step)} <em>and</em> its schema version matches
 * the step's current output schema version <em>and</em> it carries an inline output. Only then can
 * recovery skip re-executing the step and return the stored output.
 *
 * <p>Reuse is deliberately strict &mdash; a schema-version mismatch (a step whose output contract
 * changed) is NOT a hit, so recovery re-executes rather than return a stale value (invariant 13:
 * the schema version is the "is the output still valid?" key).
 */
@Service
public class CheckpointAdapter {

  private final WorkflowStepRunRepository stepRepository;
  private final AgentFlowProperties properties;

  /**
   * @param stepRepository the tenant-scoped step repository (null when no database is configured)
   * @param properties the validated application configuration
   */
  public CheckpointAdapter(
      WorkflowStepRunRepository stepRepository, AgentFlowProperties properties) {
    this.stepRepository = stepRepository;
    this.properties = properties;
  }

  /**
   * Redact, size-bound and persist a completed step's output as a checkpoint.
   *
   * <p>No-op (nothing is persisted) when the output is {@code null} or larger than {@code
   * agentflow.checkpoint.max-inline-bytes} (the large-payload path is S15-06); such a step is
   * treated as non-reusable and will be re-executed on recovery.
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param expectedLeaseEpoch the fence the row must still hold
   * @param schemaVersion the current step output-schema version (the "still valid?" key)
   * @param rawOutput the raw step output (untrusted content); may be {@code null}
   */
  @Transactional
  public void saveCheckpoint(
      UUID tenantId,
      UUID runId,
      String stepKey,
      int expectedLeaseEpoch,
      String schemaVersion,
      String rawOutput) {
    String safe = CheckpointRedactor.redact(rawOutput);
    if (safe == null || safe.isEmpty()) {
      return;
    }
    int maxInline = properties.getCheckpoint().getMaxInlineBytes();
    if (safe.length() > maxInline) {
      // The redacted output is too large to store inline (S15-06). It is not persisted inline and
      // is therefore treated as non-reusable: recovery re-executes the step rather than return a
      // value this task does not persist.
      return;
    }
    stepRepository.saveCheckpoint(
        tenantId, runId, stepKey, expectedLeaseEpoch, Checkpoint.of(schemaVersion, safe));
  }

  /**
   * Decide whether a completed step's stored checkpoint can satisfy the step again (S07-07
   * acceptance).
   *
   * @param tenantId the owning tenant, from the verified security context (invariant 1)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param currentSchemaVersion the step's current output-schema version
   * @return {@code true} only when a checkpoint exists for this tenant whose stored schema version
   *     matches {@code currentSchemaVersion} and whose output is stored inline
   */
  @Transactional(readOnly = true)
  public boolean canReuseCheckpoint(
      UUID tenantId, UUID runId, String stepKey, String currentSchemaVersion) {
    Optional<CheckpointSnapshot> stored = stepRepository.findCheckpoint(tenantId, runId, stepKey);
    return stored
        .map(
            cp ->
                cp.schemaVersion() != null
                    && currentSchemaVersion != null
                    && cp.schemaVersion().equals(currentSchemaVersion)
                    && cp.outputInline() != null)
        .orElse(false);
  }
}
