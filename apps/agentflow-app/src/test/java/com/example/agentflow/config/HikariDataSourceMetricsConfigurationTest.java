package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

/**
 * Wiring tests for the HikariCP metrics configuration (S13-14): the app's single {@link
 * HikariDataSource} must be handed the {@link MicrometerMetricsTrackerFactory}, so the DB-pool
 * meters ({@code hikaricp.connections.*}) are registered with the app's {@link MeterRegistry} —
 * Boot 4.x registers only the generic {@code jdbc.connections.*} gauges and never wires the
 * HikariCP-specific tracker.
 *
 * <p>The class is guarded by {@code @ConditionalOnBean(HikariDataSource.class)} so the DB-less
 * skeleton (S01-02) and the test profiles never instantiate it — this test pins both halves: the
 * guard is present, and the wiring actually happens when the class is created.
 */
class HikariDataSourceMetricsConfigurationTest {

  @Test
  void the_class_is_guarded_by_a_hikari_data_source_bean_condition() {
    assertThat(HikariDataSourceMetricsConfiguration.class.getAnnotation(ConditionalOnBean.class))
        .as("the wiring must be skipped without a HikariCP pool")
        .isNotNull();
  }

  @Test
  void the_constructor_wires_the_micrometer_tracker_onto_the_pool() {
    HikariDataSource pool = new HikariDataSource();
    new HikariDataSourceMetricsConfiguration(pool, mock(MeterRegistry.class));

    assertThat(pool.getMetricsTrackerFactory())
        .as("the pool must hand its tracker to the app's MeterRegistry")
        .isInstanceOf(MicrometerMetricsTrackerFactory.class);
  }
}
