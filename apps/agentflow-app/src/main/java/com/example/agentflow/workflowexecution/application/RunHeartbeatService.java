package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Run-level lease heartbeat (S07-04, the ADR-011 lease model).
 *
 * <p>A worker heartbeats while it is actively executing a run, so its {@code lease_until} stays
 * ahead of the recovery scan and the run is not mistaken for a crashed owner. The service extends
 * the lease through {@link WorkflowRunRepository#heartbeat}, which is a fenced conditional update
 * that renews {@code lease_until} only while the run still belongs to the tenant, is still owned by
 * this worker, and still holds the {@code lease_epoch} the claim returned &mdash; an owner that has
 * been re-claimed therefore cannot extend its lease (the S07-04 acceptance criterion).
 *
 * <p>The lease is renewed only for a run the worker is still actively owning. Once the run reaches
 * a state that releases the lease &mdash; a {@code HUMAN_APPROVAL} pause or a cancelled run, per
 * the cancel policy in {@code step-state-machine.md} §10 and {@code run-state-machine.md} &mdash;
 * the {@code lease_owner} is no longer this worker, so the fenced heartbeat matches zero rows and
 * the extension stops automatically &mdash; the service never extends a lease the run no longer
 * holds.
 *
 * <p>Configuration, not magic numbers: the new {@code lease_until} is {@code clock.instant() +
 * duration-seconds} (bound from {@code agentflow.worker.lease.duration-seconds}), computed through
 * the injected {@link Clock} (invariant 10 — never the system clock in domain/application code).
 *
 * <p>A rejected heartbeat is counted ({@link #failedHeartbeats()}), so the worker loop can surface
 * it (a metric in the S07-09 recovery task). The counter is per-process and not a metric label, so
 * it carries no {@code tenantId}/{@code runId} (invariant 9).
 *
 * <p>The bean is only wired when a {@link WorkflowRunRepository} is available (the repository is
 * {@code @ConditionalOnBean(DataSource.class)}); when no database is configured the dependency is
 * {@link Autowired optional} and the caller reports {@code 503} rather than the service doing
 * nothing silently (S01-02), matching {@link RunLeaseService} and the transition service.
 */
@Service
public class RunHeartbeatService {

  private final WorkflowRunRepository runRepository;
  private final Clock clock;
  private final AgentFlowProperties properties;
  private final AtomicInteger failedHeartbeats = new AtomicInteger(0);

  /**
   * @param runRepository the run repository, {@code null} when no {@link
   *     org.sql.javax.sql.DataSource} is configured (see S01-02)
   * @param clock the injected clock (UTC in production, fixed in time-dependent tests)
   * @param properties the validated worker/lease configuration
   */
  public RunHeartbeatService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) Clock clock,
      AgentFlowProperties properties) {
    this.runRepository = runRepository;
    this.clock = clock != null ? clock : Clock.systemUTC();
    this.properties = properties;
  }

  /**
   * Extend this run's lease for one lifetime (S07-04).
   *
   * @param tenantId the owning tenant (invariant 1)
   * @param runId the run the worker is still executing
   * @param workerId this worker's instance id &mdash; the {@code lease_owner} the claim returned
   * @param epoch the {@code lease_epoch} the claim returned &mdash; the fence the heartbeat echoes
   * @return the {@link HeartbeatOutcome}: {@link HeartbeatOutcome#extended()} when the lease was
   *     renewed (the run is still owned with the matching epoch), otherwise a rejection the worker
   *     counts as a failed heartbeat
   */
  public HeartbeatOutcome heartbeat(UUID tenantId, UUID runId, UUID workerId, int epoch) {
    if (runRepository == null) {
      throw new IllegalStateException(
          "heartbeat is unavailable: no database connection is configured");
    }
    OffsetDateTime leaseUntil =
        OffsetDateTime.ofInstant(clock.instant(), clock.getZone())
            .plusSeconds(properties.getWorker().getLease().getDurationSeconds());
    int matched = runRepository.heartbeat(tenantId, runId, workerId, epoch, leaseUntil);
    if (matched == 0) {
      failedHeartbeats.incrementAndGet();
      return HeartbeatOutcome.rejected();
    }
    return HeartbeatOutcome.extended(leaseUntil);
  }

  /**
   * @return the number of heartbeats this process has had rejected (an old owner, a re-claimed run
   *     or a run that released its lease); a per-process counter for later surfacing as a metric,
   *     carrying no tenant/run identifiers (invariant 9)
   */
  public int failedHeartbeats() {
    return failedHeartbeats.get();
  }
}
