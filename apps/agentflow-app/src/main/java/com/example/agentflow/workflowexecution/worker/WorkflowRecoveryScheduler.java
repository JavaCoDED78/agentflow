package com.example.agentflow.workflowexecution.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.RunLeaseService;
import com.example.agentflow.workflowexecution.persistence.ExpiredLeaseRun;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * The stale-run recovery loop (S07-09, ADR-011): on a fixed cadence it re-claims the runs whose
 * run-level lease has expired, so a run whose worker crashed, drained or was terminated is
 * <em>paused, never lost</em> (invariant 2). The lease model makes this possible: PostgreSQL is the
 * source of truth for the lease, so the scheduler re-derives the current owner on every scan rather
 * than trusting a remembered one (ADR-011, alternative 5).
 *
 * <p>Each recovery pass (see {@link #recoverPass}):
 *
 * <ol>
 *   <li><b>Scans</b> for expired leases through {@link WorkflowRunRepository#findExpiredLeaseRuns}
 *       — runs whose {@code lease_owner} is set but {@code lease_until} is already past, bounded by
 *       the concurrency limit;
 *   <li><b>Re-claims</b> each atomically through {@link RunLeaseService#claim} (the claim is one
 *       conditional {@code UPDATE ... RETURNING lease_epoch}: it skips terminal states, is tenant
 *       scoped, and bumps {@code lease_epoch} so the stale owner becomes fenced); a claim that
 *       matches no row &mdash; the run is terminal, was just taken by another worker, or a cross
 *       tenant / missing id &mdash; returns {@code -1} and is skipped, so a duplicate re-claim is a
 *       no-op, not a lost run (invariant 3);
 *   <li><b>Bounds</b> the pass by {@code agentflow.worker.max-concurrent-runs}, so one scan takes
 *       ownership of a limited batch rather than table-scanning every run (ADR-011 operational
 *       consequence);
 *   <li><b>Publishes</b> the {@code agentflow.workflow.recoveries} counter once per reclaimed run
 *       (invariant 9 — the counter carries no tenant / run identifier).
 * </ol>
 *
 * <p>The scheduler owns the reclaim only, not the step execution: once a run is re-claimed it is
 * owned by this worker and is driven to progress by the {@link
 * com.example.agentflow.workflowexecution.application.WorkflowWorker} poll loop. This keeps the
 * recovery loop a pure lease operation, independent of the graph loader, step handlers and the
 * crash-window handling that {@code S07-07}…{@code S07-16} add.
 *
 * <p>The bean is enabled explicitly by the {@code workflow-worker} role ({@code
 * agentflow.worker.recovery-enabled=true}); it is disabled by default (the API role and the
 * skeleton do not reclaim runs), so lease recovery lives only where a live worker can drive a
 * re-claimed run. Like {@link com.example.agentflow.workflowexecution.application.RunLeaseService}
 * and {@link com.example.agentflow.workflowexecution.application.RunHeartbeatService}, it needs a
 * {@link WorkflowRunRepository}, which is {@code @ConditionalOnBean(DataSource.class)}; when no
 * database is configured the repository dependency is {@link
 * org.springframework.beans.factory.annotation.Autowired optional} and {@link #recoverPass} fails
 * loudly rather than doing nothing silently (S01-02).
 *
 * @see RunLeaseService the atomic claim the recovery re-uses
 * @see WorkflowRunRepository#findExpiredLeaseRuns the expired-lease scan
 * @see ADR-011 the lease model this scheduler serves
 */
@Component
@ConditionalOnProperty(prefix = "agentflow.worker", name = "recovery-enabled", havingValue = "true")
@ConditionalOnBean(DataSource.class)
public class WorkflowRecoveryScheduler {

  /** The recovery metric name (invariant 9: the counter carries no tenant / run label). */
  static final String RECOVERIES_METRIC = "agentflow.workflow.recoveries";

  private static final Logger log = LoggerFactory.getLogger(WorkflowRecoveryScheduler.class);

  private final WorkflowRunRepository runRepository;
  private final RunLeaseService leaseService;
  private final AgentFlowProperties properties;
  private final Counter recoveries;

  /**
   * @param runRepository the run repository, {@code null} when no {@link javax.sql.DataSource} is
   *     configured (see S01-02)
   * @param leaseService the atomic claim service (S07-03) — the re-claim the recovery loop re-uses
   * @param properties the validated worker/lease configuration (the concurrency bound, S07-09)
   * @param meterRegistry the Micrometer registry the {@code recoveries} counter is registered on
   */
  public WorkflowRecoveryScheduler(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      RunLeaseService leaseService,
      AgentFlowProperties properties,
      MeterRegistry meterRegistry) {
    this.runRepository = runRepository;
    this.leaseService = leaseService;
    this.properties = properties;
    this.recoveries =
        Counter.builder(RECOVERIES_METRIC)
            .description(
                "Stale runs re-claimed by the recovery scheduler (S07-09); one increment per "
                    + "successful re-claim")
            .register(meterRegistry);
  }

  /**
   * Recover expired leases once (S07-09). Driven by the {@link Scheduled} {@link
   * #recoverExpiredLeases()} cadence; exposed for the unit and integration tests so the pass can be
   * asserted directly.
   *
   * <p>Scans for expired leases, re-claims each atomically (skipping terminal states and any run
   * lost to another worker), bounds the pass by the configured concurrency limit, and publishes the
   * {@code recoveries} counter per reclaimed run.
   *
   * @return the number of runs this pass reclaimed (zero when none was expired, or a claim was
   *     lost)
   * @throws IllegalStateException when no database connection is configured (S01-02)
   */
  public int recoverPass() {
    if (runRepository == null) {
      throw new IllegalStateException(
          "recovery is unavailable: no database connection is configured");
    }
    int limit = properties.getWorker().getMaxConcurrentRuns();
    List<ExpiredLeaseRun> candidates = runRepository.findExpiredLeaseRuns(limit);
    int recovered = 0;
    for (ExpiredLeaseRun candidate : candidates) {
      // Atomic re-claim: the claim skips terminal runs and is tenant scoped (invariant 1); a -1
      // means the run is terminal, was just taken by another worker, or a cross-tenant / missing id
      // — skip it (idempotent re-claim, invariant 3).
      int epoch = leaseService.claim(candidate.tenantId(), candidate.runId());
      if (epoch == -1) {
        continue;
      }
      recoveries.increment();
      recovered++;
    }
    return recovered;
  }

  /**
   * Recover expired leases on the configured cadence (S07-09, driven by {@code
   * agentflow.worker.recovery-fixed-rate-ms}).
   *
   * <p>A failing pass is logged and swallowed so one transient error (a busy database, a dead
   * connection) does not break the schedule: the next cadence retries the scan. The recovery is
   * idempotent, so a re-run after a crash re-claims nothing new rather than double-processing.
   */
  @Scheduled(fixedRateString = "${agentflow.worker.recovery-fixed-rate-ms}")
  public void recoverExpiredLeases() {
    try {
      int recovered = recoverPass();
      if (recovered > 0) {
        log.info("recovery pass reclaimed {} stale run(s)", recovered);
      }
    } catch (RuntimeException e) {
      log.warn("recovery pass failed; it will be retried on the next cadence", e);
    }
  }
}
