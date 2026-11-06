package com.example.agentflow.toolexecution.domain;

/**
 * The closed lifecycle status of a demo support ticket ({@code ticket.lookup}, S08-07).
 *
 * <p>It is a fixed vocabulary, exactly as {@link
 * com.example.agentflow.agentcatalog.domain.ToolRiskClass} is for tool risk, so a ticket status is
 * always one of these three values and never an unclassified string: a lookup against the fixture
 * dataset can therefore return a typed, deterministic status without a schema that accepts
 * anything. The value is a plain enum (not persisted — the fixture is an in-memory demo dataset,
 * not a PostgreSQL entity), so it carries no Spring, I/O or time/randomness dependency (invariant
 * 10).
 */
public enum TicketStatus {
  OPEN,
  ON_HOLD,
  CLOSED
}
