package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * DB-free tests for {@link RunLeaseService} (S07-03). The {@link WorkflowRunRepository} is a mock,
 * so the atomic claim SQL runs in the integration suite ({@link RunLeaseClaimIT}, Testcontainers);
 * here the service's own behaviour is asserted: the {@code lease_until} is produced through the
 * injected {@link Clock} (invariant 10), the worker instance id comes from configuration (or is
 * generated when absent), and a zero-rows claim is reported as "not claimed" ({@code -1}).
 */
class RunLeaseServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

  private static final OffsetDateTime EXPECTED_LEASE_UNTIL =
      OffsetDateTime.parse("2026-01-01T00:00:30Z");

  @Test
  @SuppressWarnings("unchecked")
  void claim_computes_lease_until_from_the_injected_clock_and_passes_the_epoch_through() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));
    when(repository.claim(any(), any(), any(), any())).thenReturn(Optional.of(7));

    RunLeaseService service = new RunLeaseService(repository, CLOCK, properties);

    ArgumentCaptor<OffsetDateTime> leaseUntil = ArgumentCaptor.forClass(OffsetDateTime.class);
    ArgumentCaptor<UUID> workerArg = ArgumentCaptor.forClass(UUID.class);

    int epoch = service.claim(tenant, run);

    assertThat(epoch).isEqualTo(7); // the bumped epoch is returned to the caller for fencing
    verify(repository).claim(any(), any(), workerArg.capture(), leaseUntil.capture());
    assertThat(workerArg.getValue()).isNotNull(); // a worker identity is always produced
    assertThat(leaseUntil.getValue()).isEqualTo(EXPECTED_LEASE_UNTIL); // clock instant + 30s
  }

  @Test
  void claim_returns_not_claimed_when_the_conditional_update_matches_no_row() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerProperties(30));
    // zero rows matched: the run is owned by a live worker, terminal, or a cross-tenant id
    when(repository.claim(any(), any(), any(), any())).thenReturn(Optional.empty());

    RunLeaseService service = new RunLeaseService(repository, CLOCK, properties);

    assertThat(service.claim(tenant, run)).isEqualTo(-1);
  }

  @Test
  void claim_uses_the_configured_worker_instance_id() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID configured = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerPropertiesWithId(configured.toString(), 30));
    when(repository.claim(any(), any(), any(), any())).thenReturn(Optional.of(1));

    RunLeaseService service = new RunLeaseService(repository, CLOCK, properties);

    ArgumentCaptor<UUID> workerArg = ArgumentCaptor.forClass(UUID.class);
    service.claim(tenant, run);

    verify(repository).claim(any(), any(), workerArg.capture(), any());
    assertThat(workerArg.getValue()).isEqualTo(configured); // the configured id is passed through
  }

  @Test
  void claim_generates_a_worker_id_when_none_is_configured() {
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();

    WorkflowRunRepository repository = mock(WorkflowRunRepository.class);
    AgentFlowProperties properties = mock(AgentFlowProperties.class);
    when(properties.getWorker()).thenReturn(workerPropertiesWithId(null, 30));
    when(repository.claim(any(), any(), any(), any())).thenReturn(Optional.of(1));

    RunLeaseService service = new RunLeaseService(repository, CLOCK, properties);

    ArgumentCaptor<UUID> workerArg = ArgumentCaptor.forClass(UUID.class);
    // must not throw and must pass a non-null worker id (a generated one)
    service.claim(tenant, run);

    verify(repository).claim(any(), any(), workerArg.capture(), any());
    assertThat(workerArg.getValue()).isNotNull();
  }

  /** Builds a {@link AgentFlowProperties.Worker} with a given instance id and lease duration. */
  private static AgentFlowProperties.Worker workerProperties(int durationSeconds) {
    return workerPropertiesWithId("worker-1", durationSeconds);
  }

  private static AgentFlowProperties.Worker workerPropertiesWithId(
      String instanceId, int durationSeconds) {
    AgentFlowProperties.Worker worker = new AgentFlowProperties.Worker();
    if (instanceId != null) {
      worker.setInstanceId(instanceId);
    }
    worker.getLease().setDurationSeconds(durationSeconds);
    return worker;
  }
}
