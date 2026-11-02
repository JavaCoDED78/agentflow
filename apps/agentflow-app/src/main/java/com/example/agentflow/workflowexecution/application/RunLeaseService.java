package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.persistence.WorkflowRunRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Run-level lease claim (S07-03, the ADR-011 lease model).
 *
 * <p>A worker claims a run before executing it: the service computes {@code lease_until} through
 * the injected {@link Clock} (invariant 10 — never the system clock in domain/application code),
 * resolves this worker's identity from configuration and performs the atomic claim through {@link
 * WorkflowRunRepository#claim}. The returned {@code lease_epoch} is the fence the worker must send
 * on every subsequent run/step write; a mismatch fences a stale or in-flight owner (invariant 5).
 *
 * <p>Configuration, not magic numbers: the lease lifetime is {@link
 * AgentFlowProperties.Worker.Lease#getDurationSeconds()} (bound from {@code agentflow.worker.*})
 * and this worker's identity is {@link AgentFlowProperties.Worker#getInstanceId()} (bound from
 * {@code agentflow.worker.instance-id}), or a generated UUID when unset so {@code lease_owner} is
 * never blank on the recovery scan.
 *
 * <p>The bean is only wired when a {@link WorkflowRunRepository} is available (the repository is
 * {@code @ConditionalOnBean(DataSource.class)}); when no database is configured the dependency is
 * {@link Autowired optional} and the caller reports {@code 503} rather than the service doing
 * nothing silently (S01-02), matching the transition service.
 */
@Service
public class RunLeaseService {

  private final WorkflowRunRepository runRepository;
  private final Clock clock;
  private final AgentFlowProperties properties;

  /**
   * @param runRepository the run repository, {@code null} when no {@link
   *     org.sql.javax.sql.DataSource} is configured (see S01-02)
   * @param clock the injected clock (UTC in production, fixed in time-dependent tests)
   * @param properties the validated worker/lease configuration
   */
  public RunLeaseService(
      @Autowired(required = false) WorkflowRunRepository runRepository,
      @Autowired(required = false) Clock clock,
      AgentFlowProperties properties) {
    this.runRepository = runRepository;
    this.clock = clock != null ? clock : Clock.systemUTC();
    this.properties = properties;
  }

  /**
   * Claim the run for this worker instance (S07-03).
   *
   * @param tenantId the owning tenant (invariant 1)
   * @param runId the run to claim
   * @return the new {@code lease_epoch} the caller must use as its fence, or {@code -1} when no
   *     claimable row matched (terminal run, owned by a live worker or a cross-tenant id)
   */
  public int claim(UUID tenantId, UUID runId) {
    if (runRepository == null) {
      throw new IllegalStateException(
          "lease claim is unavailable: no database connection is configured");
    }
    OffsetDateTime leaseUntil =
        OffsetDateTime.ofInstant(clock.instant(), clock.getZone())
            .plusSeconds(properties.getWorker().getLease().getDurationSeconds());
    Optional<Integer> epoch = runRepository.claim(tenantId, runId, resolveWorkerId(), leaseUntil);
    return epoch.orElse(-1);
  }

  /**
   * @return this worker's instance id as a {@link UUID}: the configured {@code
   *     agentflow.worker.instance-id} when it is a valid UUID, otherwise a freshly generated one so
   *     {@code lease_owner} is never blank on the recovery scan. A configured id that is not a
   *     valid UUID falls through to generation rather than failing the claim.
   */
  private UUID resolveWorkerId() {
    String configured = properties.getWorker().getInstanceId();
    if (configured != null && !configured.isBlank()) {
      try {
        return UUID.fromString(configured);
      } catch (IllegalArgumentException ignored) {
        // fall through to a generated id below
      }
    }
    return UUID.randomUUID();
  }
}
