package com.example.agentflow.toolexecution.domain;

import java.math.BigDecimal;

/**
 * One row of the {@link TicketLookup} fixture dataset (S08-07): a demo support ticket and the
 * read-only fields a lookup returns.
 *
 * <p>It is an immutable value object with value equality, held in the fixture the {@link
 * TicketLookup} reads. It carries a typed {@link TicketStatus} and an exact {@link BigDecimal}
 * total, so the lookup result is deterministic and never a loosely-typed string. It reads no time,
 * no randomness, no Spring or I/O (invariant 10). The ticket ids in the fixture ({@code TKT-1001}
 * …) are demo identifiers, not persisted business keys — the fixture is an in-memory demo, not a
 * PostgreSQL entity — so they are intentionally human-readable.
 *
 * @param ticketId the demo ticket id the lookup is keyed on
 * @param customer the customer the ticket belongs to
 * @param status the closed-lifecycle status ({@link TicketStatus#OPEN}/{@link
 *     TicketStatus#ON_HOLD}/{@link TicketStatus#CLOSED})
 * @param amount the ticket total, as an exact {@link BigDecimal} (never a float)
 */
public record TicketRecord(
    String ticketId, String customer, TicketStatus status, BigDecimal amount) {}
