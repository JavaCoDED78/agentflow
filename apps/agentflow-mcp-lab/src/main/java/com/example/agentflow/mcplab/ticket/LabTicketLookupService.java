package com.example.agentflow.mcplab.ticket;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * The lab's single demo tool (S11-08): a read-only support-ticket lookup answering from a fixed,
 * in-memory fixture dataset.
 *
 * <p>The data is fictitious and compiled in — there is no database, no network call and no clock,
 * so every answer is deterministic and the tool is safe to expose over MCP. The fixture mirrors the
 * story of the monolith's in-process {@code ticket.lookup} tool (S08-07) so the demo reads
 * consistently across both surfaces, but this class is deliberately self-contained: the lab shares
 * no code with {@code agentflow-app}.
 *
 * <p>The MCP tool name is {@code ticket_lookup}: the MCP specification restricts tool names to
 * letters, digits, underscores and hyphens, so the in-process dot name cannot cross the wire.
 */
@Service
public class LabTicketLookupService {

  /** One fixture row: a fictitious support ticket. */
  record FixtureTicket(String ticketId, String status, BigDecimal amount) {}

  private static final String CURRENCY = "USD";

  private static final FixtureTicket TKT_1001 =
      new FixtureTicket("TKT-1001", "OPEN", new BigDecimal("49.99"));
  private static final FixtureTicket TKT_1002 =
      new FixtureTicket("TKT-1002", "ON_HOLD", new BigDecimal("129.00"));
  private static final FixtureTicket TKT_1003 =
      new FixtureTicket("TKT-1003", "CLOSED", new BigDecimal("42.50"));

  /** The fixed dataset. Deliberately small and immutable. */
  static final Map<String, FixtureTicket> FIXTURE =
      Map.of(
          TKT_1001.ticketId(), TKT_1001,
          TKT_1002.ticketId(), TKT_1002,
          TKT_1003.ticketId(), TKT_1003);

  /**
   * Looks up a fictitious support ticket by id.
   *
   * @param ticketId the ticket id, e.g. {@code TKT-1001}; exact match, case-sensitive
   * @return the matching ticket, or a {@link TicketLookupView#notFound(String)} view when the id is
   *     absent from the fixture — a normal answer, never an exception across the wire
   */
  @Tool(
      name = "ticket_lookup",
      description =
          "Look up a fictitious demo support ticket by id (read-only fixture data; ids"
              + " like TKT-1001). Returns the ticket status and amount, or NOT_FOUND.")
  public TicketLookupView lookup(
      @ToolParam(description = "the ticket id, e.g. TKT-1001") String ticketId) {
    if (ticketId == null || ticketId.isBlank()) {
      throw new IllegalArgumentException("ticketId must not be null or blank");
    }
    FixtureTicket ticket = FIXTURE.get(ticketId);
    if (ticket == null) {
      return TicketLookupView.notFound(ticketId);
    }
    return TicketLookupView.found(ticket.ticketId(), ticket.status(), ticket.amount(), CURRENCY);
  }
}
