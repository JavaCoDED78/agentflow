package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for {@link RunHeartbeatService} (S07-04). The {@link WorkflowRunRepository} is a
 * mock, so the fenced conditional update runs in the integration suite ({@link
 * RunLeaseHeartbeatIT}, Testcontainers); here the service's own behaviour is asserted: the renewed
 * {@code lease_until} is produced through the injected {@link Clock} (invariant 10), the outcome is
 * {@code extended} only when the repository confirms a matching owner + epoch, and the service
 * reports {@code 503} rather than silently doing nothing when no database is configured (S01-02).
 *
 * <p>The S07-04 acceptance criterion &mdash; an old owner cannot renew its lease after it is
 * re-claimed &mdash; is the {@code
 * heartbeat_is_rejected_when_the_run_was_reclaimed_by_another_worker} case: the repository returns
 * {@code 0} rows, so the outcome is not extended.
 */
class RunHeartbeatServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private static final OffsetDateTime EXPECTED_LEASE_UNTIL =
      OffsetDateTime.parse("2026-01-01T00:00:30Z");

  @Test
  void heartbeat_extends_the_lease_and_renews_it_from_the_injected_clock() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));
    when(repository.heartbeat(any(), any(), any(), anyInt(), any())).thenReturn(1);

    RunHeartbeatService service = new RunHeartbeatService(repository, CLOCK, properties);

    ArgumentCaptor<OffsetDateTime> leaseUntil = ArgumentCaptor.forClass(OffsetDateTime.class);
    HeartbeatOutcome outcome = service.heartbeat(tenant, run, worker, 3);

    assertThat(outcome.extended()).isTrue();
    assertThat(outcome.leaseUntil()).isEqualTo(EXPECTED_LEASE_UNTIL); // clock instant + 30s
    verify(repository).heartbeat(any(), any(), any(), anyInt(), leaseUntil.capture());
    // lease_until is computed through the injected Clock inside the service, then bound on the
    // write
    assertThat(leaseUntil.getValue()).isEqualTo(EXPECTED_LEASE_UNTIL);
  }

  @Test
  void heartbeat_is_rejected_when_the_run_was_reclaimed_by_another_worker() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));
    // zero rows matched: the run was re-claimed (owner moved and/or lease_epoch bumped)
    when(repository.heartbeat(any(), any(), any(), anyInt(), any())).thenReturn(0);

    RunHeartbeatService service = new RunHeartbeatService(repository, CLOCK, properties);

    HeartbeatOutcome outcome = service.heartbeat(tenant, run, worker, 3);

    assertThat(outcome.extended()).isFalse(); // the old owner cannot renew its lease
    assertThat(outcome.leaseUntil()).isNull();
  }

  @Test
  void heartbeat_counts_rejections_as_failures() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID worker = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));
    when(repository.heartbeat(any(), any(), any(), anyInt(), any())).thenReturn(1).thenReturn(0);

    RunHeartbeatService service = new RunHeartbeatService(repository, CLOCK, properties);

    service.heartbeat(tenant, run, worker, 3); // extended
    service.heartbeat(tenant, run, worker, 3); // rejected (reclaimed)

    assertThat(service.failedHeartbeats()).isEqualTo(1);
  }

  @Test
  void heartbeat_throws_when_no_database_is_configured() {
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));

    RunHeartbeatService service = new RunHeartbeatService(null, CLOCK, properties);

    assertThatThrownBy(
            () -> service.heartbeat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection");
  }

  /** Builds a {@link AgentFlowProperties.Worker} with a given lease duration. */
  private static AgentFlowProperties.Worker workerProperties(int durationSeconds) {
    AgentFlowProperties.Worker worker = new AgentFlowProperties.Worker();
    worker.getLease().setDurationSeconds(durationSeconds);
    return worker;
  }
}
