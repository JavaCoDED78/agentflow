package com.example.agentflow.workflowexecution.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.RunLeaseService;
import com.example.agentflow.workflowexecution.persistence.ExpiredLeaseRun;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the stale-run recovery loop ({@link WorkflowRecoveryScheduler}) — the reclaim-only
 * behaviour that does not need a database: the scan, the atomic re-claim, the concurrency bound and
 * the {@code recoveries} counter. The end-to-end recovery behaviour (expired lease, real claim,
 * terminal-skip) is covered by {@link WorkflowRecoverySchedulerIT}.
 */
class WorkflowRecoverySchedulerTest {

  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final RunLeaseService leaseService = mock(RunLeaseService.class);
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final AgentFlowProperties properties = new AgentFlowProperties();

  private WorkflowRecoveryScheduler scheduler() {
    return new WorkflowRecoveryScheduler(runRepository, leaseService, properties, meterRegistry);
  }

  @Test
  void reclaims_an_expired_run_and_publishes_the_counter() {
    UUID runId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    when(runRepository.findExpiredLeaseRuns(8))
        .thenReturn(List.of(new ExpiredLeaseRun(runId, tenantId)));
    when(leaseService.claim(tenantId, runId)).thenReturn(1);

    int reclaimed = scheduler().recoverPass();

    assertThat(reclaimed).isOne();
    verify(leaseService).claim(tenantId, runId);
    Counter recoveries = meterRegistry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter();
    assertThat(recoveries).isNotNull();
    assertThat(recoveries.count()).isOne();
  }

  @Test
  void skips_a_run_claim_that_returns_minus_one() {
    // A claim of -1 means the run is terminal, was just taken by another worker, or a cross-tenant
    // / missing id — the recovery skips it (idempotent re-claim, never a lost run).
    UUID runId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    when(runRepository.findExpiredLeaseRuns(8))
        .thenReturn(List.of(new ExpiredLeaseRun(runId, tenantId)));
    when(leaseService.claim(tenantId, runId)).thenReturn(-1);

    int reclaimed = scheduler().recoverPass();

    assertThat(reclaimed).isZero();
    // The counter exists (registered at construction) but is not incremented for the skipped run.
    assertThat(meterRegistry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter().count())
        .isZero();
  }

  @Test
  void claims_only_the_runs_that_match() {
    UUID firstRun = UUID.randomUUID();
    UUID firstTenant = UUID.randomUUID();
    UUID secondRun = UUID.randomUUID();
    UUID secondTenant = UUID.randomUUID();
    when(runRepository.findExpiredLeaseRuns(8))
        .thenReturn(
            List.of(
                new ExpiredLeaseRun(firstRun, firstTenant),
                new ExpiredLeaseRun(secondRun, secondTenant)));
    when(leaseService.claim(firstTenant, firstRun)).thenReturn(1);
    when(leaseService.claim(secondTenant, secondRun)).thenReturn(-1);

    int reclaimed = scheduler().recoverPass();

    assertThat(reclaimed).isOne();
    verify(leaseService).claim(firstTenant, firstRun);
    verify(leaseService).claim(secondTenant, secondRun);
    assertThat(meterRegistry.find(WorkflowRecoveryScheduler.RECOVERIES_METRIC).counter().count())
        .isOne();
  }

  @Test
  void scans_only_up_to_the_concurrency_limit() {
    // The bound is passed to the scan as the LIMIT, so a pass re-claims at most `limit` runs.
    when(runRepository.findExpiredLeaseRuns(8)).thenReturn(List.of());
    scheduler().recoverPass();
    verify(runRepository).findExpiredLeaseRuns(8);
  }

  @Test
  void fails_loudly_when_no_database_connection_is_configured() {
    WorkflowRecoveryScheduler scheduler =
        new WorkflowRecoveryScheduler(null, leaseService, properties, meterRegistry);
    assertThatThrownBy(scheduler::recoverPass).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void publishes_the_counter_without_tenant_or_run_labels() {
    // invariant 9 — the counter carries no high-cardinality label.
    UUID runId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    when(runRepository.findExpiredLeaseRuns(8))
        .thenReturn(List.of(new ExpiredLeaseRun(runId, tenantId)));
    when(leaseService.claim(tenantId, runId)).thenReturn(1);

    scheduler().recoverPass();

    // No tenant / run / id label is attached to the counter (invariant 9).
    assertThat(
            meterRegistry
                .find(WorkflowRecoveryScheduler.RECOVERIES_METRIC)
                .counter()
                .getId()
                .getTags())
        .isEmpty();
  }
}
