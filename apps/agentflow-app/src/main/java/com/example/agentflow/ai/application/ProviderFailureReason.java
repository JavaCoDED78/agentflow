package com.example.agentflow.ai.application;

/**
 * A neutral classification of why a text-generation call to the provider failed (S09-09).
 *
 * <p>This enum is deliberately SDK-agnostic: it carries no {@code com.openai} types, so it can live
 * in {@code ai.application} and be consumed by the resilient wrapper without leaking the provider
 * SDK across the module boundary (the {@code ai.application} package must not import the provider
 * SDK, ADR-006). The provider-specific mapping from a thrown exception to a {@code
 * ProviderFailureReason} lives in {@code ai.provider.ProviderExceptionClassifier}, the only code
 * that knows the SDK's exception hierarchy.
 *
 * <p>Whether a reason is retryable is the single decision the retry loop makes: only transient
 * faults (rate limited, server error, transport-level timeout or I/O) are retried, and only while
 * the {@link ProviderResiliencePolicy} allows another attempt. Every other reason is a permanent
 * fault that fails the call immediately.
 */
public enum ProviderFailureReason {

  /** The provider rate-limited the call (HTTP 429). Retryable; wait and retry. */
  RATE_LIMIT(true),

  /** The provider returned a 5xx server error. Retryable; wait and retry. */
  SERVER_ERROR(true),

  /**
   * The call exceeded the overall or read timeout (the provider was too slow, or the request was
   * aborted). Retryable; wait and retry.
   */
  TIMEOUT(true),

  /**
   * A transport-level failure: the connection could not be established or was reset. Retryable;
   * wait and retry.
   */
  CONNECTION(true),

  /**
   * A client error: the request was malformed or rejected for a permanent reason (HTTP 4xx such as
   * 400/401/403/404/422). Not retryable; fixing the request is required.
   */
  CLIENT_ERROR(false),

  /**
   * Any failure that could not be mapped to a known reason. Not retryable by default: a provider
   * call that fails for an unknown reason fails loudly rather than retrying blindly, so a real bug
   * surfaces instead of being hidden behind a retry storm.
   */
  UNKNOWN(false);

  private final boolean retryable;

  ProviderFailureReason(boolean retryable) {
    this.retryable = retryable;
  }

  /**
   * @return {@code true} for the transient faults that are safe to retry (rate limited, server
   *     error, timeout, connection), {@code false} for the permanent faults (client error, unknown)
   */
  public boolean isRetryable() {
    return retryable;
  }
}
