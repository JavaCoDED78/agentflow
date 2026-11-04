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
  void reclaimsAnExpiredRunAndPublishesTheCounter() {
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
  void skipsARunClaimThatReturnsMinusOne() {
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
  void claimsOnlyTheRunsThatMatch() {
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
  void scansOnlyUpToTheConcurrencyLimit() {
    // The bound is passed to the scan as the LIMIT, so a pass re-claims at most `limit` runs.
    when(runRepository.findExpiredLeaseRuns(8)).thenReturn(List.of());
    scheduler().recoverPass();
    verify(runRepository).findExpiredLeaseRuns(8);
  }

  @Test
  void failsLoudlyWhenNoDatabaseConnectionIsConfigured() {
    WorkflowRecoveryScheduler scheduler =
        new WorkflowRecoveryScheduler(null, leaseService, properties, meterRegistry);
    assertThatThrownBy(scheduler::recoverPass).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void publishesTheCounterWithoutTenantOrRunLabels() {
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
