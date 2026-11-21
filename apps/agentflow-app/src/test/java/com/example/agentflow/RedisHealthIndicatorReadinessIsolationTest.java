package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Acceptance for S12-17: an unavailable Redis must not drop the app from rotation. The {@code
 * redis} contributor is grouped under the non-critical {@code cache} group ({@code
 * application-local.yml}) and is deliberately excluded from the {@code readiness}/{@code liveness}
 * probes, so a DOWN Redis must move the {@code redis} indicator to {@link Status#DOWN} while the
 * readiness group stays {@link Status#UP} (scenario 8, section 13 of the plan).
 *
 * <p>The real local wiring is exercised end to end: {@code RedisConnectionConfiguration} creates
 * the connection and the real {@code redisHealthIndicator} from this test's properties, and the
 * connection is pointed at an unreachable address ({@code 127.0.0.1:1} — the connection is
 * immediately refused) so the bare {@code PING} the indicator performs fails. The assertions read
 * the health document through the {@link HealthEndpoint} component (not a mock, not a raw HTTP
 * call), so they hit the actual exposed health layer exactly as the orchestrator's probes do.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "agentflow.redis.host=127.0.0.1",
      "agentflow.redis.port=1"
    })
class RedisHealthIndicatorReadinessIsolationTest {

  @Autowired private HealthEndpoint healthEndpoint;

  @Test
  @DisplayName(
      "a down Redis moves /health/redis DOWN but leaves the readiness group UP in the cache group")
  void down_redis_is_capped_in_the_non_critical_cache_group() {
    assertThat(healthEndpoint.healthForPath("redis").getStatus()).isEqualTo(Status.DOWN);
    assertThat(healthEndpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
  }
}
