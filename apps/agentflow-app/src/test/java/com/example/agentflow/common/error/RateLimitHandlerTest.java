package com.example.agentflow.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.RateLimitGroup;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the {@link GlobalExceptionHandler} renders a {@code RateLimitExceededException} as a
 * {@code 429 Too Many Requests} Problem Details with a {@code Retry-After} header (S04-16): the
 * {@code error_class} is {@code api_rate_limited} and {@code retryable} mirrors the taxonomy. It
 * runs as a MockMvc {@code standaloneSetup} with the real {@link GlobalExceptionHandler} wired as
 * the controller advice, so it needs no database, Kafka or Redis.
 *
 * <p>The throwaway {@link TestController} depends only on {@code common} types, so {@code common}
 * stays compilable on itself (the architecture gate stays green).
 */
class RateLimitHandlerTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new TestController())
          .setControllerAdvice(new GlobalExceptionHandler())
          .build();

  /**
   * A throwaway endpoint whose only job is to throw a {@link RateLimitExceededException}, so the
   * handler is exercised end-to-end. Depends only on {@code common} types, so {@code common} stays
   * compilable on itself (the architecture gate stays green).
   */
  @RestController
  static class TestController {

    @PostMapping("/api/v1/test/rate-limit")
    void rateLimited() {
      throw new RateLimitExceededException(RateLimitGroup.CREATE_RUN, 42);
    }
  }

  @Test
  void rate_limit_exceeded_maps_to_429_with_retry_after_and_api_rate_limited() throws Exception {
    mockMvc
        .perform(post("/api/v1/test/rate-limit"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "42"))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.error_class").value("api_rate_limited"))
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(jsonPath("$.type").value("about:blank"))
        // S12-13: the 429 carries a correlation id so an operator can quote it when raising a
        // support ticket about the throttle (the same id threads the structured log and any retry).
        .andExpect(jsonPath("$.correlation_id").isNotEmpty());
  }
}
