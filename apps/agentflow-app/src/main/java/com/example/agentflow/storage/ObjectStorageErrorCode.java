package com.example.agentflow.storage;

/**
 * Provider-neutral failure taxonomy of {@link ObjectStoragePort} (S07-06B, contract §2.3 of {@code
 * docs/architecture/object-storage.md}).
 *
 * <p>Codes are stable and provider-independent: the adapter ({@code GarageS3Adapter}, S07-06C+)
 * translates provider exceptions into these — no AWS SDK, Garage or provider-specific exception,
 * class name or detail may escape through the contract. Callers never match on retryable-reads
 * beyond {@link #retryable()}.
 *
 * <p>{@link #retryable()} is the single source of truth for the bounded-retry policy: {@code
 * UNAVAILABLE} and {@code TIMEOUT} are transient and may be retried with bounded, jittered backoff
 * (the caller's policy, never this module's — {@code storage} classifies and reports, it never
 * sleeps); everything else is a deterministic or terminal rejection and must not be retried.
 */
public enum ObjectStorageErrorCode {

  /** Deterministic caller-side rejection: blank/oversized/illegal key, or a `..` path segment. */
  INVALID_KEY,

  /**
   * Deterministic caller-side rejection: bad content type, bad limit, unexpected/mismatched
   * declared length, bad sha256, out-of-bounds metadata, or any structural violation of the
   * request/response views.
   */
  INVALID_REQUEST,

  /** Typed miss: {@code get} on a missing key. Never a raw provider exception. */
  NOT_FOUND,

  /** Permission/auth: the credentials lack the operation on the bucket. Terminal. */
  ACCESS_DENIED,

  /** Transient infrastructure failure (network, throttling, 5xx). Retryable with bounded jitter. */
  UNAVAILABLE,

  /** Transient infrastructure failure: a bounded wait exceeded. Retryable with bounded jitter. */
  TIMEOUT,

  /** Non-transient structural provider fault (e.g. malformed bucket config). Terminal. */
  PERMANENT_FAILURE;

  /**
   * Whether a caller may retry the operation with bounded, jittered backoff. Only transient
   * infrastructure codes are retryable — never {@code NOT_FOUND}, {@code ACCESS_DENIED}, {@code
   * INVALID_*} or {@code PERMANENT_FAILURE}. {@code storage} only classifies; the caller owns the
   * backoff policy.
   *
   * @return {@code true} for {@code UNAVAILABLE} and {@code TIMEOUT}; {@code false} otherwise
   */
  public boolean retryable() {
    return this == UNAVAILABLE || this == TIMEOUT;
  }
}
