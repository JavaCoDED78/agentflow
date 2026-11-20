package com.example.agentflow.common.rate_limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.error.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitInterceptorTest {

  private static final Instant BASE = Instant.ofEpochMilli(1_700_000_000_000L);
  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private RateLimiter limiter;
  private EndpointGroupResolver resolver;

  @BeforeEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  private RateLimitPolicy policy(int budget, RateLimitGroup... groups) {
    var budgets = new EnumMap<RateLimitGroup, Integer>(RateLimitGroup.class);
    for (RateLimitGroup group : groups) {
      budgets.put(group, budget);
    }
    return new RateLimitPolicy(true, budgets);
  }

  private RateLimitInterceptor interceptor(int budget) {
    limiter =
        new InMemoryRateLimiter(
            policy(budget, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC), 10_000);
    resolver = new EndpointGroupResolver();
    return new RateLimitInterceptor(
        limiter, policy(budget, RateLimitGroup.CREATE_RUN), resolver, TENANT, "local");
  }

  private static HttpServletRequest postRunRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    // HandlerMapping attribute is unset in this unit test, so the resolver falls back to the URI.
    when(request.getRequestURI()).thenReturn("/api/v1/runs");
    return request;
  }

  @Test
  void rejectsTheRequestAfterTheBudgetIsExceeded() {
    RateLimitInterceptor interceptor = interceptor(3);

    assertThat(interceptor.preHandle(postRunRequest(), null, null)).isTrue();
    assertThat(interceptor.preHandle(postRunRequest(), null, null)).isTrue();
    assertThat(interceptor.preHandle(postRunRequest(), null, null)).isTrue();
    assertThatThrownBy(() -> interceptor.preHandle(postRunRequest(), null, null))
        .isInstanceOf(RateLimitExceededException.class)
        .satisfies(
            ex ->
                assertThat(((RateLimitExceededException) ex).group())
                    .isEqualTo(RateLimitGroup.CREATE_RUN));
  }

  @Test
  void allowsEveryRequestWhenTheGroupHasNoBudget() {
    // CREATE_RUN is absent from the policy -> unthrottled -> every request passes.
    RateLimitPolicy policy = new RateLimitPolicy(true, Map.of());
    limiter = new InMemoryRateLimiter(policy, Clock.fixed(BASE, UTC), 10_000);
    resolver = new EndpointGroupResolver();
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(limiter, policy, resolver, TENANT, "local");

    for (int i = 0; i < 10; i++) {
      assertThat(interceptor.preHandle(postRunRequest(), null, null)).isTrue();
    }
  }

  @Test
  void doesNotThrottleNonPostMethodsToTheRunEndpoint() {
    RateLimitInterceptor interceptor = interceptor(1);
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/api/v1/runs");

    assertThat(interceptor.preHandle(request, null, null)).isTrue();
  }

  @Test
  void doesNotThrottleEndpointsThatMapToNoGroup() {
    RateLimitInterceptor interceptor = interceptor(1);
    // Health / actuator path matches no declared binding, so it is never throttled.
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/actuator/health");

    assertThat(interceptor.preHandle(request, null, null)).isTrue();
  }

  @Test
  void enforcesSeparateBudgetsPerTenant() {
    limiter =
        new InMemoryRateLimiter(
            policy(1, RateLimitGroup.CREATE_RUN), Clock.fixed(BASE, UTC), 10_000);
    resolver = new EndpointGroupResolver();
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(
            limiter, policy(1, RateLimitGroup.CREATE_RUN), resolver, TENANT, "local");

    // First tenant consumes its single slot.
    assertThat(interceptor.preHandle(postRunRequest(), null, null)).isTrue();
    // A different tenant still has its own budget for the same endpoint.
    RateLimitInterceptor otherTenant =
        new RateLimitInterceptor(
            limiter, policy(1, RateLimitGroup.CREATE_RUN), resolver, UUID.randomUUID(), "local");
    assertThat(otherTenant.preHandle(postRunRequest(), null, null)).isTrue();
  }

  private static HttpServletRequest opsRequest(String path) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    String method = path.endsWith("/redrive") ? "POST" : "GET";
    when(request.getMethod()).thenReturn(method);
    // HandlerMapping attribute is unset in this unit test, so the resolver falls back to the URI.
    when(request.getRequestURI()).thenReturn(path);
    return request;
  }

  @Test
  void throttles_the_ops_redrive_route_when_the_ops_budget_is_exceeded() {
    resolver = new EndpointGroupResolver();
    limiter =
        new InMemoryRateLimiter(policy(1, RateLimitGroup.OPS), Clock.fixed(BASE, UTC), 10_000);
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(limiter, policy(1, RateLimitGroup.OPS), resolver, TENANT, "local");

    // The ops list route resolves to the OPS group and its single slot is consumed, so the next
    // redrive (a POST to the same group) is throttled.
    assertThat(interceptor.preHandle(opsRequest("/api/v1/ops/dlq"), null, null)).isTrue();
    assertThatThrownBy(
            () ->
                interceptor.preHandle(
                    opsRequest("/api/v1/ops/dlq/" + sampleUuid() + "/redrive"), null, null))
        .isInstanceOf(RateLimitExceededException.class)
        .satisfies(
            ex ->
                assertThat(((RateLimitExceededException) ex).group())
                    .isEqualTo(RateLimitGroup.OPS));
  }

  @Test
  void leaves_the_ops_route_when_the_policy_has_no_ops_budget() {
    // A policy with no OPS entry (the old behaviour) leaves the ops surface unthrottled; the
    // production RateLimitConfiguration always puts OPS, so this only documents the data-driven
    // resolver, not a policy the app ships.
    resolver = new EndpointGroupResolver();
    limiter =
        new InMemoryRateLimiter(
            new RateLimitPolicy(true, Map.of()), Clock.fixed(BASE, UTC), 10_000);
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(
            limiter, new RateLimitPolicy(true, Map.of()), resolver, TENANT, "local");

    for (int i = 0; i < 10; i++) {
      assertThat(interceptor.preHandle(opsRequest("/api/v1/ops/dlq"), null, null)).isTrue();
    }
  }

  private static String sampleUuid() {
    return "00000000-0000-0000-0000-000000000000";
  }
}
