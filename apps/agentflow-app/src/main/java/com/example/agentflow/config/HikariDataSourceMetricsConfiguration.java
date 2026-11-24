package com.example.agentflow.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * DB pool metrics wiring (S13-14).
 *
 * <p>Spring Boot 4.x registers the generic {@code jdbc.connections.*} gauges (max/min) from the
 * pool metadata provider, but it deliberately does not wire the HikariCP connection-level meters —
 * the active/idle/pending/timeout gauges and the acquire/usage/creation timers live in HikariCP's
 * own {@code MicrometerMetricsTracker} and are opt-in. This class wires them: it hands the app's
 * {@link MeterRegistry} to the one {@link HikariDataSource} Boot builds, so the DB-pool half of the
 * dashboard (active/max, pending, timeouts, and the acquire/usage timers) is a real, scraped meter
 * instead of an aspiration.
 *
 * <p>The wiring is guarded by {@link ConditionalOnBean} so it only activates when a HikariCP pool
 * exists — the DB-less skeleton (S01-02) and the test profiles exclude the DataSource
 * auto-configuration, so no pool, no tracker, no failure.
 *
 * <p>The meters carry one bounded tag — the pool name (a single static value per pool) — and no
 * identifier; hikaricp's own documentation recommends exactly this tag for per-pool discrimination.
 * The pool name never carries tenant/run/user data.
 */
@Configuration
@ConditionalOnBean(HikariDataSource.class)
public class HikariDataSourceMetricsConfiguration {

  /**
   * Wire the HikariCP micrometer tracker onto the pool Boot created. The constructor runs once
   * during context refresh when the condition holds; the tracker is reused for the lifetime of the
   * pool and cleaned up by HikariCP when the pool closes.
   *
   * @param dataSource the HikariCP pool created by Spring Boot's DataSource auto-configuration
   * @param registry the app's {@link MeterRegistry} (the Prometheus registry in every profile)
   */
  public HikariDataSourceMetricsConfiguration(HikariDataSource dataSource, MeterRegistry registry) {
    dataSource.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
  }
}
