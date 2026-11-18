package com.example.agentflow.usage.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The handle a reservation returns to its caller so the operation can <em>settle</em> it after it
 * runs (S12-04 / {@code V3_3_26}). It carries the idempotency key ({@link #reservationId()}) the
 * settlement re-checks, the dimension and amount reserved, and the run the reservation is charged
 * to for traceability.
 *
 * <p>A reservation is the durable mirror of the {@code ModelBudgetReservation} the {@code ai}
 * module reserves before a model call: {@code usage} owns the durable store, {@code ai} owns the
 * pure pre-call guard that mirrors it.
 *
 * <p>S12-06 reconciles a reservation left floating by a crash (the worker died, or a provider call
 * was ambiguous, so it never settled): {@link #reconciledAt()} is set by the reconciliation flow
 * when it settles a stranded reservation at its best-known amount, making a second reconcile a
 * no-op (invariant 3). {@link #attempt()} is the operation attempt the idempotency key was derived
 * from, so a duplicate attempt reuses the same key (invariant 3, "use attempt ID for idempotency").
 *
 * @param reservationId the idempotency key - a duplicate reservation with the same id is a no-op,
 *     never a second charge (invariant 3)
 * @param tenantId the owning tenant (never {@code null})
 * @param runId the run the reservation is charged to, or {@code null} for a budget not pinned to a
 *     run
 * @param metric the dimension reserved against (never {@code null})
 * @param reservedAmount the estimated amount reserved (never {@code null}; never negative)
 * @param reconciledAt the instant the reservation was reconciled by the S12-06 flow, or {@code
 *     null} when it has not been reconciled (a normally-settled reservation carries {@code
 *     settled_at} but no {@code reconciled_at})
 * @param attempt the operation attempt the {@link #reservationId()} was derived from, or {@code
 *     null} for a reservation keyed only by a caller-provided id (pre-S12-06)
 */
public record BudgetReservation(
    UUID reservationId,
    UUID tenantId,
    UUID runId,
    BudgetMetric metric,
    BigDecimal reservedAmount,
    OffsetDateTime reconciledAt,
    Long attempt) {

  /**
   * Build the idempotent reservation handle for a given operation attempt. The returned {@code
   * reservation_id} is derived deterministically from the attempt components so that a duplicate
   * attempt of the same operation reuses the same idempotency key - a repeated reserve (or a
   * redelivery / crash-retry of the same attempt) is then a no-op on the {@code ON CONFLICT (
   * reservation_id)} primary key, never a second charge (invariant 3, "use attempt ID for
   * idempotency").
   *
   * <p>The id is a name-based UUID (RFC-4122 v3, MD5) over the canonical component order; it is
   * stable across runs and JVM restarts for the same (tenant, run, attempt, metric) tuple.
   *
   * @param tenantId the owning tenant
   * @param runId the run the reservation is charged to
   * @param attempt the operation attempt (the idempotency seed; {@code >= 1})
   * @param metric the budget dimension reserved against
   * @return the deterministic {@code reservation_id} the reservation is stored under
   * @throws IllegalArgumentException when any component is {@code null}
   */
  public static UUID reservationIdForAttempt(
      UUID tenantId, UUID runId, Long attempt, BudgetMetric metric) {
    if (tenantId == null || runId == null || attempt == null || metric == null) {
      throw new IllegalArgumentException(
          "attempt-derived reservation id requires tenant, run, attempt and metric");
    }
    String source = tenantId + "-" + runId + "-" + attempt + "-" + metric.name();
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Whether this reservation has been reconciled by the S12-06 flow ({@link #reconciledAt()}
   * non-null). A normally-settled reservation (the S12-04 path) is not reconciled.
   */
  public boolean isReconciled() {
    return reconciledAt != null;
  }
}
