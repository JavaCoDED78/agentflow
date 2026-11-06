package com.example.agentflow.toolexecution.domain;

/**
 * The result of a {@link TicketLookup#lookup(String, java.util.function.Supplier, int)} call
 * (S08-07): the {@link #outcome()}, the ticket id it was attempted for, how many attempts ran, and
 * (only on {@link TicketOutcome#SUCCESS}) the found {@link #record()}.
 *
 * <p>It is an immutable value object with value equality, the result-layer counterpart of {@link
 * ReportResult} / {@link com.example.agentflow.toolexecution.domain.CalculationException}: the
 * outcome and, when found, the record are everything the executor needs to classify and audit the
 * call without parsing a message. {@link #record()} is {@code null} for {@link
 * TicketOutcome#NOT_FOUND} and {@link TicketOutcome#TRANSIENT_FAILURE}. It reads no time, no
 * randomness, no Spring or I/O (invariant 10).
 *
 * @param outcome the classified outcome (never {@code null})
 * @param ticketId the ticket id the lookup was attempted with
 * @param attemptsUsed the attempts already made: {@code 0} for a not-found (no attempt ran), the
 *     number of flaps + 1 for a success, or the exhausted maximum for a transient failure
 * @param record the found ticket, present only for {@link TicketOutcome#SUCCESS}; {@code null}
 *     otherwise
 */
public record TicketResult(
    TicketOutcome outcome, String ticketId, int attemptsUsed, TicketRecord record) {

  /**
   * @param ticketId the ticket id the lookup was attempted with
   * @param attemptsUsed the attempts made (0 for a not-found)
   * @return a {@link TicketResult} classified {@link TicketOutcome#NOT_FOUND}
   */
  public static TicketResult notFound(String ticketId, int attemptsUsed) {
    return new TicketResult(TicketOutcome.NOT_FOUND, ticketId, attemptsUsed, null);
  }

  /**
   * @param ticketId the ticket id the lookup was attempted with
   * @param record the ticket that answered
   * @param attemptsUsed the attempts made before the ticket answered
   * @return a {@link TicketResult} classified {@link TicketOutcome#SUCCESS}
   */
  public static TicketResult success(String ticketId, TicketRecord record, int attemptsUsed) {
    return new TicketResult(TicketOutcome.SUCCESS, ticketId, attemptsUsed, record);
  }

  /**
   * @param ticketId the ticket id the lookup was attempted with
   * @param attemptsUsed the (exhausted) number of attempts that all flapped
   * @return a {@link TicketResult} classified {@link TicketOutcome#TRANSIENT_FAILURE}
   */
  public static TicketResult transientFailure(String ticketId, int attemptsUsed) {
    return new TicketResult(TicketOutcome.TRANSIENT_FAILURE, ticketId, attemptsUsed, null);
  }
}
