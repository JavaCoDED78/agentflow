package com.example.agentflow.ai.application;

/**
 * The reason a {@link PlannerService#propose} model attempt did not produce a usable plan (S09-08).
 *
 * <p>It classifies the four ways a model attempt can fail so the caller can tell them apart and
 * decide whether a corrective retry is worth taking:
 *
 * <ul>
 *   <li>{@link #MALFORMED_JSON} — the provider returned content that is not well-formed JSON;
 *   <li>{@link #SCHEMA_MISMATCH} — the content was valid JSON but not a structurally valid plan
 *       (for example a duplicated step id);
 *   <li>{@link #PROVIDER_REFUSAL} — the provider returned no content (a refusal or an empty
 *       completion);
 *   <li>{@link #TRANSPORT_FAILURE} — the provider call itself failed (a network / timeout / server
 *       error), before a response was produced.
 * </ul>
 *
 * <p>This is the {@code ai} module's own, finer-grained signal (distinct from the HTTP-level {@link
 * com.example.agentflow.common.ErrorClass} taxonomy): it is what a repair policy inspects to decide
 * whether to retry, and what observability surfaces to explain why a plan was never produced.
 *
 * <p>{@link #isRepairable()} marks the failures a corrective retry can fix. A retry gives the model
 * another chance, so it is allowed for {@link #MALFORMED_JSON}, {@link #SCHEMA_MISMATCH} and {@link
 * #TRANSPORT_FAILURE}; a {@link #PROVIDER_REFUSAL} will not self-correct by retrying, so the repair
 * policy never retries it (a retry would only burn the budget, S09-08).
 */
public enum PlannerFailure {

  /** The response was present but not well-formed JSON. */
  MALFORMED_JSON(true),

  /** The response was valid JSON but not a structurally valid plan. */
  SCHEMA_MISMATCH(true),

  /** The provider returned no content: a refusal or an empty completion. */
  PROVIDER_REFUSAL(false),

  /** The provider call itself failed (network / timeout / server error) before a response. */
  TRANSPORT_FAILURE(true);

  private final boolean repairable;

  PlannerFailure(boolean repairable) {
    this.repairable = repairable;
  }

  /**
   * @return {@code true} when a corrective retry can fix this failure, so the bounded repair policy
   *     (S09-08) may retry it; {@code false} when a retry would not help (for example a provider
   *     refusal)
   */
  public boolean isRepairable() {
    return repairable;
  }
}
