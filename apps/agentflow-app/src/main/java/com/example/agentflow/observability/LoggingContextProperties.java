package com.example.agentflow.observability;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Seeds the {@code service} and {@code environment} MDC entries that every JSON log line carries
 * (S01-19). The logstash encoder writes the MDC of the current thread, so putting these values here
 * makes the service identity and the active profile available in stdout / Loki without embedding
 * them in every log call.
 *
 * <p>MDC is thread-local: this runs on the main thread during context startup, so startup and
 * shutdown diagnostics carry the entries. Thread-local correlation across worker threads is
 * completed once tracing is wired (S13).
 */
@Component
public class LoggingContextProperties {

  private static final Logger log = LoggerFactory.getLogger(LoggingContextProperties.class);

  private final String service;
  private final String environment;

  public LoggingContextProperties(
      @Value("${agentflow.app-name:agentflow-app}") String service, Environment environment) {
    this.service = service;
    this.environment =
        environment.getActiveProfiles().length == 0
            ? "base"
            : String.join(",", environment.getActiveProfiles());
  }

  @PostConstruct
  public void populateMdc() {
    MDC.put("service", service);
    MDC.put("environment", environment);
    log.debug("Seeded logging MDC: service={}, environment={}", service, environment);
  }
}
