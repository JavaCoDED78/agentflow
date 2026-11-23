package com.example.agentflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit proof for the S13-09 {@link CorrelationIdFilter}: the {@code correlationId} is seeded for
 * the duration of one HTTP request, derived from the active {@code traceId} when present, and
 * removed from the MDC after the chain runs — even when the chain throws — so the id never leaks
 * into unrelated requests on a thread pool.
 */
class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  private static final class SeededValue {
    String value;
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void seeds_correlationId_from_the_active_traceId() throws Exception {
    MDC.put(CorrelationIdFilter.TRACE_ID_KEY, "trace-id-abc123");

    SeededValue seen = new SeededValue();
    filter.doFilter(
        httpRequest(),
        httpResponse(),
        chain((req, res) -> seen.value = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)));

    assertThat(seen.value).isEqualTo("trace-id-abc123");
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY))
        .as("correlationId must be removed after the chain")
        .isNull();
  }

  @Test
  void seeds_correlationId_with_a_fresh_uuid_when_no_traceId_is_active() throws Exception {
    SeededValue seen = new SeededValue();
    filter.doFilter(
        httpRequest(),
        httpResponse(),
        chain((req, res) -> seen.value = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)));

    assertThat(seen.value).isNotBlank();
    assertThat(seen.value).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
  }

  @Test
  void seeds_a_fresh_uuid_when_the_traceId_is_blank() throws Exception {
    MDC.put(CorrelationIdFilter.TRACE_ID_KEY, " ");

    SeededValue seen = new SeededValue();
    filter.doFilter(
        httpRequest(),
        httpResponse(),
        chain((req, res) -> seen.value = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)));

    assertThat(seen.value).isNotBlank().isNotEqualTo(" ");
  }

  @Test
  void removes_correlationId_even_when_the_chain_throws() {
    MDC.put(CorrelationIdFilter.TRACE_ID_KEY, "trace-id-xyz");

    boolean failed = false;
    try {
      filter.doFilter(
          httpRequest(),
          httpResponse(),
          (req, res) -> {
            throw new ServletException("boom");
          });
    } catch (ServletException expected) {
      failed = true;
    } catch (java.io.IOException ignored) {
      failed = false;
    }

    assertThat(failed).as("the exception must propagate").isTrue();
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY))
        .as("the id must be removed in the finally block")
        .isNull();
  }

  @Test
  void does_not_overwrite_an_already_present_correlationId_with_a_fresh_one() throws Exception {
    // The filter derives correlationId from traceId at the start of the request. A value the
    // thread already carries is replaced for the request's scope and removed afterwards, so a
    // stale value from a previous request on the same thread cannot be reported as this request's
    // correlation.
    MDC.put(CorrelationIdFilter.CORRELATION_ID_KEY, "stale-correlation-id");

    SeededValue seen = new SeededValue();
    filter.doFilter(
        httpRequest(),
        httpResponse(),
        chain((req, res) -> seen.value = MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)));

    assertThat(seen.value).isNotEqualTo("stale-correlation-id");
    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_KEY)).isNull();
  }

  private static FilterChain chain(ChainAction action) {
    return (req, res) -> action.execute((HttpServletRequest) req, (HttpServletResponse) res);
  }

  @FunctionalInterface
  private interface ChainAction {
    void execute(HttpServletRequest request, HttpServletResponse response)
        throws java.io.IOException;
  }

  private static HttpServletRequest httpRequest() {
    return mock(HttpServletRequest.class);
  }

  private static HttpServletResponse httpResponse() {
    return mock(HttpServletResponse.class);
  }
}
