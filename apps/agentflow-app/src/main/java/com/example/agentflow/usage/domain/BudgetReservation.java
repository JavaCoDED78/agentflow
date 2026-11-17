package com.example.agentflow.usage.domain;

import java.math.BigDecimal;
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
 * @param reservationId the idempotency key - a duplicate reservation with the same id is a no-op,
 *     never a second charge (invariant 3)
 * @param tenantId the owning tenant (never {@code null})
 * @param runId the run the reservation is charged to, or {@code null} for a budget not pinned to a
 *     run
 * @param metric the dimension reserved against (never {@code null})
 * @param reservedAmount the estimated amount reserved (never {@code null}; never negative)
 */
public record BudgetReservation(
    UUID reservationId,
    UUID tenantId,
    UUID runId,
    BudgetMetric metric,
    BigDecimal reservedAmount) {}
