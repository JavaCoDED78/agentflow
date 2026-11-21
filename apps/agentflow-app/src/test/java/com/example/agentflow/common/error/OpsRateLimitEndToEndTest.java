package com.example.agentflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.agentflow.common.ProblemDetails;
import com.example.agentflow.common.RateLimitGroup;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * S12-20 acceptance (handler half of criterion 1): a per-tenant throttle rendered by the real
 * {@link GlobalExceptionHandler#handleRateLimit} (same package, so the package-private handler is
 * exercised directly) is a {@code 429 Too Many Requests} with an {@code api_rate_limited} {@code
 * error_class}, a {@code retryable} flag and a {@code Retry-After} header (RFC 8594).
 *
 * <p>The exception is the ops-group throttle (the same {@link RateLimitExceededException} the
 * {@code common.rate_limit.RateLimitInterceptor} throws - proven by its {@code
 * throttles_the_ops_redrive_route_when_the_ops_budget_is_exceeded} test). It is constructed here
 * directly so the handler mapping stays in the {@code common.error} package and the {@code common}
 * module boundary is respected (no {@code common.error -> common.rate_limit} edge).
 */
@DisplayName("The rate-limit exception renders as a 429 with correlation and Retry-After (S12-20)")
class OpsRateLimitEndToEndTest {

  @Test
  @DisplayName("the OPS throttle maps to 429 + api_rate_limited + correlation_id + Retry-After")
  void the_ops_throttle_maps_to_429_with_correlation_and_retry_after() {
    RateLimitExceededException throttled = new RateLimitExceededException(RateLimitGroup.OPS, 30);
    HttpServletRequest request = mock(HttpServletRequest.class);

    ResponseEntity<ProblemDetails> response =
        new GlobalExceptionHandler().handleRateLimit(throttled, request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");

    ProblemDetails body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.errorClass()).isEqualTo("api_rate_limited");
    assertThat(body.status()).isEqualTo(429);
    assertThat(body.retryable()).isTrue();
    assertThat(body.correlationId()).isNotEmpty();
  }
}
