package com.example.agentflow.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Seeds a {@code correlationId} in the MDC for the duration of one HTTP request (S13-09).
 *
 * <p>Log lines are emitted through the {@link StructuredJsonLogstashEncoder}, which renders {@code
 * correlationId} inline (via the whitelisted MDC keys) — so every log line of a request can be
 * found in the logs by exactly one id. The id is:
 *
 * <ul>
 *   <li>the {@code traceId} when a tracing context is already active (e.g. an upstream gateway or
 *       the OTel bridge put it in the MDC), so the log line and the trace id agree;
 *   <li>a fresh UUID otherwise.
 * </ul>
 *
 * <p>The value is removed from the MDC when the request finishes — it never leaks into unrelated
 * requests on a thread pool. The filter is deliberately <em>observability only</em>: it never reads
 * identity or business state, never turns an error, and never changes what a controller does. It is
 * registered by Spring Boot (auto-detected {@code Filter} bean), which is why it lives in the
 * observability module rather than the web layer.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String CORRELATION_ID_KEY = "correlationId";
  public static final String TRACE_ID_KEY = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, java.io.IOException {
    String correlationId = MDC.get(TRACE_ID_KEY);
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(CORRELATION_ID_KEY, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(CORRELATION_ID_KEY);
    }
  }
}
