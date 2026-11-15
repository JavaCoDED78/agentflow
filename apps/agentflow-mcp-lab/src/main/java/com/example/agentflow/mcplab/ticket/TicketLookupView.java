package com.example.agentflow.mcplab.ticket;

import java.math.BigDecimal;

/**
 * The MCP-facing answer of the lab's {@code ticket_lookup} tool (S11-08): a flat, JSON serializable
 * view of a fictitious fixture ticket.
 *
 * <p>An absent id is a first-class answer — {@link #notFound(String)} — not an error, so a model
 * client sees one stable schema for every outcome and cannot be tricked into treating an exception
 * message as data.
 *
 * @param ticketId the requested ticket id (echoed back verbatim)
 * @param found whether the id exists in the fixture
 * @param status the ticket status ({@code OPEN} / {@code ON_HOLD} / {@code CLOSED}), or {@code
 *     NOT_FOUND} when {@code found} is false
 * @param amount the exact fixture amount, or null when not found
 * @param currency the fixture currency, or null when not found
 */
public record TicketLookupView(
    String ticketId, boolean found, String status, BigDecimal amount, String currency) {

  static TicketLookupView found(
      String ticketId, String status, BigDecimal amount, String currency) {
    return new TicketLookupView(ticketId, true, status, amount, currency);
  }

  static TicketLookupView notFound(String ticketId) {
    return new TicketLookupView(ticketId, false, "NOT_FOUND", null, null);
  }
}
