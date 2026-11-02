package com.example.agentflow.workflowexecution.persistence;

/**
 * The persisted step checkpoint (S07-07): the stored output's schema version and its inline output,
 * read from {@code workflow_step_runs.output_schema_version / output_inline}.
 *
 * @param schemaVersion the stored output-schema version (nullable when no output exists)
 * @param outputInline the redacted inline output (nullable when the output was too large to store
 *     inline and was externalized to object storage, S15-06)
 */
public record CheckpointSnapshot(String schemaVersion, String outputInline) {}
