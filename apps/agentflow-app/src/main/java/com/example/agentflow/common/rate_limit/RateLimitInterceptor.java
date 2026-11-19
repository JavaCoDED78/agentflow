package com.example.agentflow.common.rate_limit;

import com.example.agentflow.common.RateLimitGroup;
import com.example.agentflow.common.RateLimitKey;
import com.example.agentflow.common.RateLimitPolicy;
import com.example.agentflow.common.RateLimiter;
import com.example.agentflow.common.error.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies rate limiting to throttled API endpoints, wired after authentication.
 *
 * <p>This interceptor runs inside the Spring MVC chain, which fires after the servlet {@code
 * FilterChain} has authenticated the request. The tenant and actor are therefore read from the
 * trusted security context, never from the request body or headers. In this skeleton the tenant
 * comes from the same verified default the controllers use ({@code agentflow.local.tenant-id}); the
 * actor comes from the verified {@code Jwt} subject when present, otherwise the configured default.
 * The {@code Jwt} token itself is never placed in the key or the logs — only the subject.
 *
 * <p>Only the endpoints matched by {@link EndpointGroupResolver} are throttled; health, liveness,
 * readiness, {@code GET}, documentation, and unknown paths are excluded. When the group is
 * throttled but no budget remains, the interceptor rejects with {@link RateLimitExceededException}
 * (handled to a {@code 429} by {@code GlobalExceptionHandler}).
 *
 * <p>The interceptor itself has no business rule: it delegates both the endpoint grouping and the
 * budget to {@link EndpointGroupResolver} and {@link RateLimiter}.
 */
@Component
public final class RateLimitInterceptor implements HandlerInterceptor {

  private final RateLimiter rateLimiter;
  private final RateLimitPolicy policy;
  private final EndpointGroupResolver resolver;
  private final UUID tenant;
  private final String actor;

  public RateLimitInterceptor(
      RateLimiter rateLimiter,
      RateLimitPolicy policy,
      EndpointGroupResolver resolver,
      @Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}") UUID tenant,
      @Value("${agentflow.local.actor:local}") String actor) {
    this.rateLimiter = rateLimiter;
    this.policy = policy;
    this.resolver = resolver;
    this.tenant = tenant;
    this.actor = actor;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    RateLimitGroup group = resolver.groupFor(request).orElse(null);
    if (group == null) {
      // Health, liveness, readiness, GET, documentation, unknown paths: not throttled.
      return true;
    }
    if (!policy.isThrottled(group)) {
      return true;
    }
    RateLimitKey key = new RateLimitKey(tenant, principal(), group);
    Integer remaining = rateLimiter.remaining(key).orElse(null);
    // S04-16: the caller owns the reject decision and the Retry-After window. A budget of N serves
    // exactly N requests per window: a returned value of 0 is the Nth (allowed) request, and a
    // negative value means the budget was exceeded, so this request is rejected.
    if (remaining != null && remaining < 0) {
      throw new RateLimitExceededException(group, 60);
    }
    return true;
  }

  /**
   * Returns the trusted actor for throttling: the verified {@code Jwt} subject when the request was
   * authenticated with a token, otherwise the configured default. Never returns the token itself.
   */
  private static String principal() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      String subject = jwt.getSubject();
      if (subject != null && !subject.isBlank()) {
        return subject;
      }
    }
    return "anonymous";
  }
}
