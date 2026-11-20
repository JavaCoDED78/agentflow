package com.example.agentflow.common.rate_limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.common.RateLimitGroup;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Proves {@link EndpointGroupResolver} maps every operator DLQ endpoint to the separate {@link
 * RateLimitGroup#OPS} group (S12-13) and leaves everything else unthrottled. The mapping is the
 * data the {@link RateLimitInterceptor} consumes, so a path not listed here is never throttled.
 *
 * <p>The resolver is a pure data object: a mock {@link HttpServletRequest} supplies the method and
 * the {@link HandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} the resolver reads first. It
 * needs no database, Kafka or Redis.
 */
class EndpointGroupResolverTest {

  private static final HttpServletRequest request(HttpMethod method, String path) {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getMethod()).thenReturn(method.name());
    when(req.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE)).thenReturn(path);
    return req;
  }

  private final EndpointGroupResolver resolver = new EndpointGroupResolver();

  @Test
  void the_ops_list_detail_and_redrive_routes_resolve_to_the_ops_group() {
    assertThat(resolver.groupFor(request(GET, "/api/v1/ops/dlq"))).contains(RateLimitGroup.OPS);
    assertThat(resolver.groupFor(request(GET, "/api/v1/ops/dlq/" + sampleUuid())))
        .contains(RateLimitGroup.OPS);
    assertThat(resolver.groupFor(request(POST, "/api/v1/ops/dlq/" + sampleUuid() + "/redrive")))
        .contains(RateLimitGroup.OPS);
  }

  @Test
  void a_run_route_still_resolves_to_create_run() {
    assertThat(resolver.groupFor(request(POST, "/api/v1/runs")))
        .contains(RateLimitGroup.CREATE_RUN);
  }

  @Test
  void an_unthrottled_route_resolves_to_no_group() {
    assertThat(resolver.groupFor(request(GET, "/actuator/health"))).isEmpty();
    assertThat(resolver.groupFor(request(GET, "/api/v1/workflows"))).isEmpty();
  }

  @Test
  void a_post_to_ops_list_does_not_match_the_get_rule() {
    // The resolver matches on method + path: a POST to the collection route is not the GET rule.
    assertThat(resolver.groupFor(request(POST, "/api/v1/ops/dlq"))).isEmpty();
  }

  private static HttpMethod GET = HttpMethod.GET;

  private static HttpMethod POST = HttpMethod.POST;

  private static String sampleUuid() {
    return "00000000-0000-0000-0000-000000000000";
  }
}
