package com.example.agentflow.workflowexecution.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.ApprovalService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.OverdueApprovalRow;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Expiry sweep for overdue human approvals (S11-05): one pass lists the overdue {@code PENDING}
 * approvals through {@link ApprovalRepository#findOverduePending}, pre-checks each gated run is
 * still {@code WAITING_FOR_APPROVAL} (a run that moved on owns its own lifecycle) and hands the
 * rest to {@link ApprovalService#expireOverdue}, which guards the conditional write and drives the
 * refusal. The scheduler owns no state and writes nothing: a lost race, a faulted item or a run
 * that moved on never stops the rest of the batch.
 */
class ApprovalExpirySchedulerTest {

  private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

  private final ApprovalRepository approvalRepository = mock(ApprovalRepository.class);
  private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
  private final ApprovalService approvalService = mock(ApprovalService.class);
  private final AgentFlowProperties properties = new AgentFlowProperties();
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final ApprovalExpiryScheduler scheduler =
      new ApprovalExpiryScheduler(
          approvalRepository,
          runRepository,
          approvalService,
          properties,
          Clock.fixed(NOW, ZoneOffset.UTC),
          meterRegistry);

  @Test
  void an_overdue_approval_on_a_run_still_waiting_is_expired_and_counted() {
    UUID tenant = UUID.randomUUID();
    OverdueApprovalRow overdue =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "send-report", 0);
    when(approvalRepository.findOverduePending(
            eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC)), anyInt()))
        .thenReturn(List.of(overdue));
    when(runRepository.findState(tenant, overdue.runId()))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.WAITING_FOR_APPROVAL, 7, 0)));
    when(approvalService.expireOverdue(overdue)).thenReturn(true);

    int expired = scheduler.expirePass();

    assertThat(expired).isOne();
    verify(approvalService).expireOverdue(overdue);
    // invariant 9: the counter carries no tenant / run / approval label
    assertThat(meterRegistry.find(ApprovalExpiryScheduler.EXPIRED_METRIC).counter()).isNotNull();
    assertThat(meterRegistry.find(ApprovalExpiryScheduler.EXPIRED_METRIC).counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void a_candidate_whose_run_moved_on_is_skipped_without_expiring_anything() {
    UUID tenant = UUID.randomUUID();
    OverdueApprovalRow overdue =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "send-report", 0);
    when(approvalRepository.findOverduePending(any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of(overdue));
    // the run is no longer parked at the pause (cancellation in flight, a recovery, …): it owns
    // its own lifecycle and the sweep must not fail it
    when(runRepository.findState(tenant, overdue.runId()))
        .thenReturn(Optional.of(new RunStateSnapshot(WorkflowRunState.RUNNING, 8, 1)));

    assertThat(scheduler.expirePass()).isZero();

    verify(approvalService, never()).expireOverdue(any());
  }

  @Test
  void a_lost_race_leaves_the_row_to_the_winner_and_keeps_sweeping() {
    UUID tenant = UUID.randomUUID();
    OverdueApprovalRow raced =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "a", 0);
    OverdueApprovalRow won =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "b", 0);
    when(approvalRepository.findOverduePending(any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of(raced, won));
    when(runRepository.findState(eq(tenant), any(UUID.class)))
        .thenAnswer(
            inv -> Optional.of(new RunStateSnapshot(WorkflowRunState.WAITING_FOR_APPROVAL, 7, 0)));
    when(approvalService.expireOverdue(raced)).thenReturn(false);
    when(approvalService.expireOverdue(won)).thenReturn(true);

    assertThat(scheduler.expirePass()).isOne();

    verify(approvalService).expireOverdue(raced);
    verify(approvalService).expireOverdue(won);
  }

  @Test
  void a_faulted_item_rolls_back_whole_and_never_stops_the_batch() {
    UUID tenant = UUID.randomUUID();
    OverdueApprovalRow faulted =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "a", 0);
    OverdueApprovalRow won =
        new OverdueApprovalRow(UUID.randomUUID(), tenant, UUID.randomUUID(), "b", 0);
    when(approvalRepository.findOverduePending(any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of(faulted, won));
    when(runRepository.findState(eq(tenant), any(UUID.class)))
        .thenAnswer(
            inv -> Optional.of(new RunStateSnapshot(WorkflowRunState.WAITING_FOR_APPROVAL, 7, 0)));
    when(approvalService.expireOverdue(faulted))
        .thenThrow(new IllegalStateException("the item's transaction rolled back whole"));
    when(approvalService.expireOverdue(won)).thenReturn(true);

    assertThat(scheduler.expirePass()).isOne();

    verify(approvalService).expireOverdue(faulted);
    verify(approvalService).expireOverdue(won);
  }

  @Test
  void a_pass_with_nothing_overdue_expires_nothing() {
    when(approvalRepository.findOverduePending(any(OffsetDateTime.class), anyInt()))
        .thenReturn(List.of());

    assertThat(scheduler.expirePass()).isZero();

    verify(approvalService, never()).expireOverdue(any());
    verify(runRepository, never()).findState(any(), any());
  }
}
