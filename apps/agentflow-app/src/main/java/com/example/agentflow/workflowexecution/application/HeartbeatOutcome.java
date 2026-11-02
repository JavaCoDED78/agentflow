package com.example.agentflow.workflowexecution.application;

import java.time.OffsetDateTime;

/**
 * Outcome of a run-level lease heartbeat (S07-04, the ADR-011 lease model).
 *
 * <p>The heartbeat renews {@code lease_until} only while the run is still owned by the calling
 * worker with the matching {@code lease_epoch}. A heartbeat the repository rejected (zero rows
 * matched) reports {@link #extended()} as {@code false} with a {@code null} {@link #leaseUntil()}:
 * the run was re-claimed (owner moved and/or the epoch bumped), has reached a state that released
 * the lease (a {@code HUMAN_APPROVAL} pause or a cancelled run), is gone, or is not owned by this
 * tenant. The caller treats a rejected heartbeat as a failure and, per the cancel policy, stops
 * extending once the run is no longer actively owned.
 *
 * @param extended {@code true} when the lease was renewed; {@code false} when the heartbeat was
 *     rejected (a stale owner, a re-claimed run, or a run that released its lease)
 * @param leaseUntil the renewed {@code lease_until} ({@code now + duration-seconds} through the
 *     injected clock) when {@code extended}, otherwise {@code null}
 */
public record HeartbeatOutcome(boolean extended, OffsetDateTime leaseUntil) {

  /**
   * @param leaseUntil the renewed lease expiry (computed through the injected clock)
   */
  public static HeartbeatOutcome extended(OffsetDateTime leaseUntil) {
    return new HeartbeatOutcome(true, leaseUntil);
  }

  /** A factory for a rejected heartbeat (no matching owner/epoch, or the lease was released). */
  public static HeartbeatOutcome rejected() {
    return new HeartbeatOutcome(false, null);
  }
}
