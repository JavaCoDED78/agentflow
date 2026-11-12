package com.example.agentflow.ingestion.domain;

/**
 * What a redelivered {@code ingestion-requested} event does with an already-indexed document
 * (S10-13): the redelivery policy the plan fixes ("на redelivery обновлять или пропускать
 * существующую индексацию по политике").
 *
 * <p>Delivery is at-least-once ({@code ADR-003}), so the same event can arrive more than once:
 *
 * <ul>
 *   <li>{@link #SKIP} (the default) — a document that is already {@code INDEXED} (or otherwise not
 *       claimable) is left untouched: the pass is a no-op and the existing index stays exactly as
 *       it is. Repeating one ingestion event never creates duplicate logical chunks and never
 *       re-spends an embedding call.
 *   <li>{@link #UPDATE} — an already-indexed document is claimed again and its chunk rows are
 *       overwritten in place ({@code ON CONFLICT DO UPDATE} on the {@code (document_id,
 *       chunk_index, pipeline_version)} key), so a reindex refreshes fragments and vectors without
 *       duplicating or deleting rows.
 * </ul>
 *
 * <p>Pure domain value: the configured name is mapped by {@link #fromConfig(String)}, never by
 * {@code valueOf} on external input, so an unknown policy fails loudly at wiring time.
 */
public enum RedeliveryPolicy {

  /** Leave an already-indexed document untouched on redelivery (the default). */
  SKIP,

  /** Re-embed and overwrite the chunk rows in place on redelivery (reindex). */
  UPDATE;

  /**
   * Map the configured policy name to the enum.
   *
   * @param name the configured name ({@code "skip"} or {@code "update"})
   * @return the matching policy
   * @throws IllegalArgumentException when the name is not a known policy (a misconfiguration fails
   *     at wiring time, never silently defaults)
   */
  public static RedeliveryPolicy fromConfig(String name) {
    if ("skip".equalsIgnoreCase(name)) {
      return SKIP;
    }
    if ("update".equalsIgnoreCase(name)) {
      return UPDATE;
    }
    throw new IllegalArgumentException(
        "Unknown indexing redelivery policy '" + name + "', expected 'skip' or 'update'");
  }

  /**
   * @return {@code true} when a conflicting chunk row is overwritten in place ({@link #UPDATE});
   *     {@code false} when it is left untouched ({@link #SKIP})
   */
  public boolean updatesOnConflict() {
    return this == UPDATE;
  }
}
