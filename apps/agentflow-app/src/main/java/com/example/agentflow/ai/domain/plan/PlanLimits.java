package com.example.agentflow.ai.domain.plan;

/**
 * The bounds the {@link PlannerPlan} schema declares and the validation gate enforces (S09-05).
 *
 * <p>Two limits close the plan the model may propose:
 *
 * <ul>
 *   <li>{@code maxSteps} — the maximum number of steps a single plan may contain;
 *   <li>{@code maxOutputSize} — the maximum serialized output size (characters) the Planner may
 *       emit.
 * </ul>
 *
 * <p>Both are system-defined constraints, never values the model sets: the model proposes steps,
 * the backend decides how many are permitted. The validation gate (S09-07) reads these from
 * configuration and rejects a plan that exceeds them, so a runaway plan stays bounded (invariant
 * 7). The {@link #DEFAULT} values are conservative fallbacks used when configuration has not tuned
 * them; they are meant to be overridden from {@code agentflow.planner.*} in a later task, not
 * treated as a final policy.
 *
 * <p>A non-positive component normalizes back to its {@code DEFAULT}, so a zero or negative bound
 * cannot accidentally disable the limit. This is a pure, immutable value type (no Spring / I/O,
 * reads neither time nor randomness, invariant 10).
 *
 * @param maxSteps the maximum number of steps a plan may contain; a non-positive value falls back
 *     to {@link #DEFAULT_MAX_STEPS}
 * @param maxOutputSize the maximum serialized plan output in characters; a non-positive value falls
 *     back to {@link #DEFAULT_MAX_OUTPUT_SIZE}
 */
public record PlanLimits(int maxSteps, int maxOutputSize) {

  /** The conservative default step bound (see {@link #DEFAULT}). */
  public static final int DEFAULT_MAX_STEPS = 50;

  /** The conservative default serialized-output bound, in characters (see {@link #DEFAULT}). */
  public static final int DEFAULT_MAX_OUTPUT_SIZE = 8_192;

  /** The default limits used when configuration has not tuned {@code agentflow.planner.*}. */
  public static final PlanLimits DEFAULT =
      new PlanLimits(DEFAULT_MAX_STEPS, DEFAULT_MAX_OUTPUT_SIZE);

  /**
   * Canonical constructor.
   *
   * @param maxSteps the maximum number of steps; a non-positive value falls back to {@link
   *     #DEFAULT_MAX_STEPS}
   * @param maxOutputSize the maximum serialized output size in characters; a non-positive value
   *     falls back to {@link #DEFAULT_MAX_OUTPUT_SIZE}
   */
  public PlanLimits {
    maxSteps = maxSteps <= 0 ? DEFAULT_MAX_STEPS : maxSteps;
    maxOutputSize = maxOutputSize <= 0 ? DEFAULT_MAX_OUTPUT_SIZE : maxOutputSize;
  }
}
