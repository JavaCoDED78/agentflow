package com.example.agentflow.common.rate_limit;

import com.example.agentflow.common.RateLimitGroup;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Maps a request to the {@link RateLimitGroup} it belongs to, or {@code null} when the request is
 * not throttled.
 *
 * <p>Only the endpoints named in {@code RateLimitPolicy} are matched, so every other request
 * (health/liveness/readiness, {@code GET}, documentation, and unknown paths) is excluded from
 * throttling. Path templates are matched with the {@link AntPathMatcher} so group members such as
 * {@code /api/v1/runs/{runId}/approvals/{approvalId}} can be added later without changing matching.
 *
 * <p>The mapping is declared as data, keeping this class free of any business rules: a request
 * belongs to a group only when it is explicitly listed here.
 */
@Component
public final class EndpointGroupResolver {

  private static final class Binding {
    private final org.springframework.http.HttpMethod method;
    private final String pattern;
    private final RateLimitGroup group;

    private Binding(
        org.springframework.http.HttpMethod method, String pattern, RateLimitGroup group) {
      this.method = method;
      this.pattern = pattern;
      this.group = group;
    }
  }

  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final List<Binding> bindings;

  public EndpointGroupResolver() {
    List<Binding> declared = new ArrayList<>();
    // S04-16: only the endpoints named in RateLimitPolicy are throttled.
    declared.add(
        new Binding(
            org.springframework.http.HttpMethod.POST, "/api/v1/runs", RateLimitGroup.CREATE_RUN));
    // S12-13: the operator DLQ inspection / redrive surface (the {@code ops:dlq} endpoints) is
    // throttled under the separate OPS group, so an operator cannot exhaust the API or the inbox
    // it feeds by hammering redrive. The budget, the correlation id on the 429 and the ingress
    // rule complete the secured ops surface.
    declared.add(
        new Binding(
            org.springframework.http.HttpMethod.GET, "/api/v1/ops/dlq", RateLimitGroup.OPS));
    declared.add(
        new Binding(
            org.springframework.http.HttpMethod.GET, "/api/v1/ops/dlq/*", RateLimitGroup.OPS));
    declared.add(
        new Binding(
            org.springframework.http.HttpMethod.POST,
            "/api/v1/ops/dlq/*/redrive",
            RateLimitGroup.OPS));
    this.bindings = List.copyOf(declared);
  }

  /**
   * Resolves the group for the request, or {@code null} when it is not throttled.
   *
   * @return the matched {@link RateLimitGroup}, or an empty {@link java.util.Optional} when the
   *     request does not belong to any throttled group (health, GET, docs, unknown paths)
   */
  public Optional<RateLimitGroup> groupFor(HttpServletRequest request) {
    String path = pathOf(request);
    org.springframework.http.HttpMethod method = methodOf(request);
    for (Binding binding : bindings) {
      if (binding.method == method && PATH_MATCHER.match(binding.pattern, path)) {
        return Optional.of(binding.group);
      }
    }
    return Optional.empty();
  }

  private static String pathOf(HttpServletRequest request) {
    Object lookup = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
    if (lookup != null) {
      return lookup.toString();
    }
    return request.getRequestURI();
  }

  private static org.springframework.http.HttpMethod methodOf(HttpServletRequest request) {
    try {
      return org.springframework.http.HttpMethod.valueOf(request.getMethod());
    } catch (IllegalArgumentException ex) {
      return org.springframework.http.HttpMethod.GET;
    }
  }
}
