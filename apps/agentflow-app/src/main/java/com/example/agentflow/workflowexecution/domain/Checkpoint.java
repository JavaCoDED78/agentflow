package com.example.agentflow.workflowexecution.domain;

/**
 * A completed step's result — the checkpoint persisted by S07-07 (glossary "Checkpoint").
 *
 * <p>A checkpoint answers the question "is this step's result still valid?" without re-executing
 * the step. It is the idempotency-key pattern of S07-01 (dedupe by <em>input</em>) extended to the
 * <em>output</em>: recovery compares the stored {@link #schemaVersion()} with the step's current
 * schema version and, when they match, can accept the stored {@link #output()} as the step result.
 *
 * <p>The value is stored inline in {@code workflow_step_runs.output_inline}; it is redacted before
 * it is written (invariant 8 — secrets never land in a row), and its size is bounded to {@code
 * agentflow.checkpoint.max-inline-bytes}. A step whose redacted output is too large to store inline
 * is not checkpointed inline (its output is externalized to object storage by the large-payload
 * path, S15-06); such a step is treated as non-reusable and is re-executed on recovery.
 *
 * @param schemaVersion the version of the step's output schema used when the output was produced
 *     (the "still valid?" key); never {@code null} for a live checkpoint
 * @param output the step output itself, already redacted
 */
public record Checkpoint(String schemaVersion, String output) {

  /**
   * @param schemaVersion the step output-schema version (the "still valid?" key)
   * @param output the redacted step output
   * @return a checkpoint holding a small result, ready to be persisted inline
   */
  public static Checkpoint of(String schemaVersion, String output) {
    return new Checkpoint(schemaVersion, output);
  }
}
