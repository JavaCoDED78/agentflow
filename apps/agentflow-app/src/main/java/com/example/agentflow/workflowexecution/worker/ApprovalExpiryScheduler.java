package com.example.agentflow.workflowexecution.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.ApprovalService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import com.example.agentflow.workflowexecution.persistence.ApprovalRepository;
import com.example.agentflow.workflowexecution.persistence.OverdueApprovalRow;
import com.example.agentflow.workflowexecution.persistence.RunStateSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The approval-expiry sweep (S11-05): on a fixed cadence it expires the {@code PENDING} approvals
 * whose {@code expiry} has already passed, so a deadline nobody decided on is honoured by the
 * platform rather than by whoever happens to call the endpoint — a rejected/expired request never
 * launches a side effect, and an expired request is failed exactly once.
 *
 * <p>Each sweep pass (see {@link #expirePass}):
 *
 * <ol>
 *   <li><b>Scans</b> for overdue approvals through {@link ApprovalRepository#findOverduePending} —
 *       {@code PENDING} rows with {@code expiry} strictly before the cutoff computed from the
 *       injectable {@link Clock} (invariant 10), ordered oldest-first and bounded by {@code
 *       agentflow.worker.approval-expiry-batch-size};
 *   <li><b>Pre-checks</b> the gated run is still {@code WAITING_FOR_APPROVAL}: a run that already
 *       moved on (cancellation in flight, …) owns its own lifecycle, and the sweep must not fail a
 *       run the refusal transition no longer reaches — the item is skipped and left to that
 *       lifecycle;
 *   <li><b>Expires</b> each remaining approval through {@link ApprovalService#expireOverdue}: the
 *       guarded {@code EXPIRED} write, the system-actor audit row and the refusal completion (run
 *       and gated step {@code -> FAILED}, {@code workflow.run.rejected.v1} through the outbox) in
 *       one transaction. A lost race against a concurrent human decision answers {@code false} and
 *       moves on, so one bad or raced item never stops the rest of the batch;
 *   <li><b>Publishes</b> the {@code agentflow.workflow.approvals.expired} counter once per expired
 *       approval (invariant 9 — the counter carries no tenant / run / approval label).
 * </ol>
 *
 * <p>The bean is enabled explicitly by the {@code workflow-worker} role ({@code
 * agentflow.worker.approval-expiry-enabled=true}); it is disabled by default (the API role and the
 * skeleton do not expire approvals), matching the other per-role listeners and schedulers. Like
 * {@link WorkflowRecoveryScheduler}, it needs a {@link DataSource}-gated repository; when no
 * database is configured the dependency is {@link Autowired optional} and {@link #expirePass} fails
 * loudly rather than doing nothing silently (S01-02).
 *
 * @see ApprovalService#expireOverdue the transactional refusal completion the sweep drives
 * @see WorkflowRecoveryScheduler the sibling lease-recovery sweep this mirrors in shape
 */
@Component
@ConditionalOnProperty(
    prefix = "agentflow.worker",
    name = "approval-expiry-enabled",
    havingValue = "true")
@ConditionalOnBean(DataSource.class)
public class ApprovalExpiryScheduler {

  /** The expiry metric name (invariant 9: the counter carries no tenant / run / approval label). */
  static final String EXPIRED_METRIC = "agentflow.workflow.approvals.expired";

  private static final Logger log = LoggerFactory.getLogger(ApprovalExpiryScheduler.class);

  private final ApprovalRepository approvalRepository;
  private final WorkflowRunRepository runRepository;
  private final ApprovalService approvalService;
  private final AgentFlowProperties properties;
  private final Clock clock;
  private final Counter expired;

  /**
   * @param approvalRepository the approvals owner, {@code null} when no {@link DataSource} is
   *     configured (see S01-02)
   * @param runRepository the run owner, {@code null} when no {@link DataSource} is configured — the
   *     pre-check that gates the sweep on the run still waiting
   * @param approvalService the refusal completion the sweep drives ({@link
   *     ApprovalService#expireOverdue})
   * @param properties the validated worker configuration (the cadence and the per-pass bound)
   * @param clock the injectable clock (UTC in production, fixed in time-dependent tests); falls
   *     back to the system clock when absent
   * @param meterRegistry the Micrometer registry the {@code expired} counter is registered on
   */
  public ApprovalExpiryScheduler(
      @Autowired(required = false) ApprovalRepository approvalRepository,
      @Autowired(required = false) WorkflowRunRepository runRepository,
      ApprovalService approvalService,
      AgentFlowProperties properties,
      @Autowired(required = false) Clock clock,
      MeterRegistry meterRegistry) {
    this.approvalRepository = approvalRepository;
    this.runRepository = runRepository;
    this.approvalService = approvalService;
    this.properties = properties;
    this.clock = clock != null ? clock : Clock.systemUTC();
    this.expired =
        Counter.builder(EXPIRED_METRIC)
            .description(
                "Overdue human approvals expired by the approval-expiry sweep (S11-05); one "
                    + "increment per approval the sweep won")
            .register(meterRegistry);
  }

  /**
   * Expire overdue approvals once (S11-05). Driven by the {@link Scheduled} {@link
   * #expireOverdueApprovals()} cadence; exposed for the unit and integration tests so the pass can
   * be asserted directly.
   *
   * @return the number of approvals this pass expired (zero when none was overdue, every item lost
   *     a race or its run had already moved on)
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public int expirePass() {
    if (approvalRepository == null || runRepository == null) {
      throw new IllegalStateException(
          "approval expiry is unavailable: no database connection is configured");
    }
    OffsetDateTime cutoff = OffsetDateTime.now(clock);
    int limit = properties.getWorker().getApprovalExpiryBatchSize();
    List<OverdueApprovalRow> overdue = approvalRepository.findOverduePending(cutoff, limit);
    int expiredCount = 0;
    for (OverdueApprovalRow candidate : overdue) {
      // The pre-check: only a run still parked at the pause belongs to the sweep. A run that
      // moved on (cancellation in flight, a recovery in progress, …) owns its own lifecycle; the
      // refusal transition would refuse the edge and roll the item back, so the sweep skips it
      // up front. A race in the gap is still fenced — the transition service re-reads and the
      // whole item rolls back, loud, never half-written.
      if (!runStillWaiting(candidate)) {
        log.debug(
            "approval {} on run {} skipped: the run is no longer WAITING_FOR_APPROVAL",
            candidate.id(),
            candidate.runId());
        continue;
      }
      try {
        if (approvalService.expireOverdue(candidate)) {
          expired.increment();
          expiredCount++;
        }
      } catch (RuntimeException e) {
        // One raced or faulted item never stops the batch: the item's transaction rolled back
        // whole, and the next cadence re-scans it (the sweep is idempotent).
        log.warn(
            "approval {} on run {} could not be expired this pass; it will be re-scanned",
            candidate.id(),
            candidate.runId(),
            e);
      }
    }
    return expiredCount;
  }

  /**
   * Expire overdue approvals on the configured cadence (S11-05, driven by {@code
   * agentflow.worker.approval-expiry-fixed-rate-ms}).
   *
   * <p>A failing pass is logged and swallowed so one transient error (a busy database, a dead
   * connection) does not break the schedule: the next cadence retries the scan. The sweep is
   * idempotent, so a re-run after a crash expires nothing twice (at-least-once, ADR-003).
   */
  @Scheduled(fixedRateString = "${agentflow.worker.approval-expiry-fixed-rate-ms}")
  public void expireOverdueApprovals() {
    try {
      int expiredCount = expirePass();
      if (expiredCount > 0) {
        log.info("approval-expiry pass expired {} overdue approval(s)", expiredCount);
      }
    } catch (RuntimeException e) {
      log.warn("approval-expiry pass failed; it will be retried on the next cadence", e);
    }
  }

  /**
   * The pre-check gate: the candidate's run is still parked in {@code WAITING_FOR_APPROVAL}, so the
   * refusal completion's transition is an allowed edge. A missing or cross-tenant run answers
   * {@code false} (indistinguishable from moved-on, invariant 1).
   */
  private boolean runStillWaiting(OverdueApprovalRow candidate) {
    return runRepository
        .findState(candidate.tenantId(), candidate.runId())
        .map(RunStateSnapshot::status)
        .map(WorkflowRunState.WAITING_FOR_APPROVAL::equals)
        .orElse(false);
  }
}
