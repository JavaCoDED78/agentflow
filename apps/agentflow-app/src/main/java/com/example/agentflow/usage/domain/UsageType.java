package com.example.agentflow.usage.domain;

/**
 * What a {@code usage_records} measurement charged at (S12-01 / {@code V3_3_24}). Bounded by the
 * {@code usage_records_usage_type_check} CHECK at the storage layer, so the value is a fixed
 * vocabulary, never caller input.
 *
 * <ul>
 *   <li>{@link #MODEL} — a single text-generation (planning) attempt; the {@link
 *       com.example.agentflow.ai.application.ChatResponse#usage()} it produced.
 *   <li>{@link #TOOL} — a single tool invocation; no model, the {@link #toolName()} identifies it.
 *   <li>{@link #EMBEDDING} — a single embedding generation for a document chunk.
 * </ul>
 */
public enum UsageType {

  /**
   * A model (planning) call, identified by its {@link
   * com.example.agentflow.usage.domain.UsageRecordDto#model() model}.
   */
  MODEL,

  /**
   * A tool invocation, identified by its {@link
   * com.example.agentflow.usage.domain.UsageRecordDto#toolName() tool name}.
   */
  TOOL,

  /** An embedding-generation call for a knowledge-base chunk. */
  EMBEDDING
}
