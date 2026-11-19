package com.example.agentflow.common.error;

import com.example.agentflow.common.ErrorClass;
import com.example.agentflow.common.RateLimitGroup;

/**
 * Thrown when a {@code (tenant, principal)} exceeds the configured per-minute budget for a {@link
 * RateLimitGroup}. The {@link GlobalExceptionHandler} renders it as {@code 429 Too Many Requests}
 * with a {@code Retry-After} header (RFC 8594). It is a protective throttle at the API edge &mdash;
 * never an authentication/authorization signal (invariant 1) and never a substitute for the request
 * size limit or the timeout (plan §9).
 */
public class RateLimitExceededException extends RuntimeException {

  private final RateLimitGroup group;
  private final int retryAfterSeconds;

  /**
   * @param group the group whose budget was exceeded
   * @param retryAfterSeconds how long a client should wait before re-issuing (minimum {@code 0})
   */
  public RateLimitExceededException(RateLimitGroup group, int retryAfterSeconds) {
    super("rate limit exceeded for group " + group.configKey());
    this.group = group;
    this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
  }

  /**
   * @return the group whose budget was exceeded
   */
  public RateLimitGroup group() {
    return group;
  }

  /**
   * @return the {@code error_class} token rendered for this throttle ({@code api_rate_limited})
   */
  public ErrorClass errorClass() {
    return ErrorClass.API_RATE_LIMIT;
  }

  /**
   * @return how long a client should wait before re-issuing the request, in seconds
   */
  public int retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
