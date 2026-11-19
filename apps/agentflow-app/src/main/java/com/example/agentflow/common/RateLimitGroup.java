package com.example.agentflow.common;

/**
 * The API rate-limit groups: the {@code (tenant, principal)} throttle boundaries. It is the single,
 * storage-agnostic owner of the group identity &mdash; the {@code RateLimit} configuration group
 * ({@code S04-16}) keys its budgets by it, and the adapter that enforces them ({@code S04-17}
 * in-memory, {@code S12-19} Redis) derives the per-minute budget from it.
 *
 * <p>{@link #configKey()} is the human-facing group name (matching the config group in {@code
 * docs/architecture/api-conventions.md}); it is deliberately distinct from the enum constant name,
 * which is the valid Java identifier.
 */
public enum RateLimitGroup {

  /** Start a workflow run ({@code POST /api/v1/runs}). */
  CREATE_RUN("create-run"),

  /** Upload / ingest a document. */
  UPLOAD("upload"),

  /** Knowledge-base hybrid search (RAG retrieval). */
  SEARCH_DEBUG("search/debug"),

  /** Operational endpoints (diagnostics, health-adjacent). */
  OPS("ops");

  private final String configKey;

  RateLimitGroup(String configKey) {
    this.configKey = configKey;
  }

  /**
   * @return the human-facing group name (matching the config group), e.g. {@code create-run}
   */
  public String configKey() {
    return configKey;
  }
}
