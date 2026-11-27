package com.example.agentflow.storage;

/**
 * The {@code delete} outcome (S07-06B, contract §2.2, §5.5 and §8).
 *
 * <p>Idempotent by construction: {@link #DELETED} means the object existed and was removed; {@link
 * #ALREADY_ABSENT} means it did not exist — an idempotent success, never a failure. A repeat
 * delete, a redrive or a crash-then-retry can never fail the lifecycle.
 */
public enum DeleteResult {

  /** The object existed and was removed. */
  DELETED,

  /** The object did not exist; an idempotent success. */
  ALREADY_ABSENT
}
