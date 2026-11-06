package com.example.agentflow.toolexecution.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;

/**
 * A deterministic, dependency-free demo of a read-only tool: {@code ticket.lookup} (S08-07).
 *
 * <p>It answers a ticket id against a fixed <em>fixture dataset</em> ({@link #FIXTURE}) and returns
 * exactly the three outcomes the plan task asks for, <b>without any external network</b>:
 *
 * <ul>
 *   <li><b>not-found.</b> A ticket id that is not in the fixture answers {@link
 *       TicketOutcome#NOT_FOUND} deterministically — a missing record is not flapped for.
 *   <li><b>transient failure.</b> A known ticket's demo backend may <em>flap</em> on an attempt; a
 *       flap is a transient fault, retried up to {@code maxAttempts}, so a retry can recover. Only
 *       when every attempt flaps is the call a {@link TicketOutcome#TRANSIENT_FAILURE}.
 *   <li><b>success.</b> The first non-flapped attempt against a known ticket answers {@link
 *       TicketOutcome#SUCCESS} with the found {@link TicketRecord}.
 * </ul>
 *
 * <p>Like {@link Calculator} and {@link ReportFormatter} it is a <b>pure value</b>: it reads
 * neither system time nor a system random source and carries no Spring or I/O dependency (invariant
 * 10), so it is fully testable in isolation. The one thing it does read — the per-attempt flap draw
 * — is <em>injected</em> as a {@link Supplier<Double>} in {@code [0, 1)}, exactly the seam {@link
 * com.example.agentflow.toolexecution.domain.RetryPolicy} uses for its jitter: a fixed supplier
 * makes every outcome reproducible under a fixed draw, and a shared {@link SplittableRandom}
 * ({@link #DEFAULT_FLAP_SOURCE}) models realistic flapping in production.
 *
 * <p><b>How the three outcomes are driven by an injected draw.</b> The lookup first checks the
 * fixture: an absent id is {@link TicketOutcome#NOT_FOUND} before any draw is consulted. For a
 * known id it retries up to {@code maxAttempts}, drawing once per attempt; an attempt
 * <em>flaps</em> (transient failure) when its draw is below {@link #FLAP_PROBABILITY} and otherwise
 * answers {@link TicketOutcome#SUCCESS}. So a supplier returning a draw {@code >= FLAP_PROBABILITY}
 * answers success on the first attempt, a supplier returning a draw {@code < FLAP_PROBABILITY} on
 * every attempt exhausts the retries and yields {@link TicketOutcome#TRANSIENT_FAILURE}, and a
 * supplier that flaps a few times then clears yields success after the retries — a transient
 * failure that a retry recovered.
 *
 * <p>{@link #lookup(String)} is the production entry point (shared flapping source, {@link
 * #DEFAULT_MAX_ATTEMPTS} attempts); {@link #lookup(String, Supplier)} and {@link #lookup(String,
 * Supplier, int)} are the deterministic seams a test drives. The method is a static entry point,
 * the lookup-layer mirror of {@code ticket.lookup} in the built-in tool descriptor ({@link
 * com.example.agentflow.toolexecution.application.BuiltInTools#ticketLookup()}).
 */
public final class TicketLookup {

  /**
   * The fixed demo dataset the lookup reads. The keys are human-readable demo ids (not persisted
   * business keys — the fixture is an in-memory demo, not a PostgreSQL entity) and the values are
   * the tickets a lookup can answer. Built with {@link Map#of} so it is immutable and read-only.
   */
  private static final Map<String, TicketRecord> FIXTURE =
      Map.of(
          "TKT-1001",
              new TicketRecord(
                  "TKT-1001", "Alice Nguyen", TicketStatus.OPEN, new BigDecimal("49.99")),
          "TKT-1002",
              new TicketRecord(
                  "TKT-1002", "Bob Petrov", TicketStatus.ON_HOLD, new BigDecimal("120.00")),
          "TKT-1003",
              new TicketRecord(
                  "TKT-1003", "Carol Diaz", TicketStatus.CLOSED, new BigDecimal("15.50")));

  /**
   * The probability a known ticket's demo backend flaps on a single attempt (an attempt flaps when
   * its injected draw is below this value). Chosen to be a visible-but-not-dominant flapping rate;
   * it is a demo constant only, not a configured SLA.
   */
  private static final double FLAP_PROBABILITY = 0.5;

  /**
   * The default number of attempts the production {@link #lookup(String)} makes before a known
   * ticket's repeated flaps become a terminal {@link TicketOutcome#TRANSIENT_FAILURE}: matches the
   * {@link com.example.agentflow.toolexecution.domain.RetryPolicy#ofDefaults()} maximum, so the
   * demo retries the same way a tool call retried under its retry policy does (S08-10).
   */
  private static final int DEFAULT_MAX_ATTEMPTS = 3;

  /**
   * The shared, thread-safe production flapping source: an unbounded {@link SplittableRandom} draw
   * in {@code [0, 1)}, consulted once per attempt by the no-seam {@link #lookup(String)} entry
   * point. The testable seam is the injected {@link Supplier<Double>} instead.
   */
  private static final Supplier<Double> DEFAULT_FLAP_SOURCE = new SplittableRandom()::nextDouble;

  private TicketLookup() {}

  /**
   * The production entry point ({@code ticket.lookup}): look up a ticket id against the fixture
   * with the shared flapping source and the default attempt count. A missing id answers not-found;
   * a known id answers success or, if it flaps on every attempt, a transient failure.
   *
   * <p>Use the {@link #lookup(String, Supplier, int)} overload to drive the outcome
   * deterministically in a test.
   *
   * @param ticketId the ticket id to look up; never {@code null} or blank
   * @return the classified {@link TicketResult}
   * @throws IllegalArgumentException if {@code ticketId} is {@code null} or blank
   */
  public static TicketResult lookup(String ticketId) {
    return lookup(ticketId, DEFAULT_FLAP_SOURCE, DEFAULT_MAX_ATTEMPTS);
  }

  /**
   * The deterministic entry point: look up a ticket id with a caller-supplied flap draw and the
   * default attempt count. A fixed supplier makes the outcome reproducible in a test.
   *
   * @param ticketId the ticket id to look up; never {@code null} or blank
   * @param randomness the per-attempt flap draw in {@code [0, 1)}; never {@code null}
   * @return the classified {@link TicketResult}
   * @throws IllegalArgumentException if {@code ticketId} is {@code null} or blank, or a draw is
   *     outside {@code [0, 1)}
   */
  public static TicketResult lookup(String ticketId, Supplier<Double> randomness) {
    return lookup(ticketId, randomness, DEFAULT_MAX_ATTEMPTS);
  }

  /**
   * The fully controllable entry point: look up a ticket id against the fixture, retrying a known
   * ticket's transient flaps up to {@code maxAttempts} with an injected per-attempt flap draw.
   *
   * <p>The three outcomes are all reachable and deterministic for a given draw:
   *
   * <ul>
   *   <li><b>not-found.</b> An id absent from the fixture returns {@link TicketOutcome#NOT_FOUND}
   *       before any draw is consulted (no attempt runs, {@code attemptsUsed == 0}).
   *   <li><b>success.</b> A known id answers {@link TicketOutcome#SUCCESS} on the first attempt
   *       whose draw is {@code >= FLAP_PROBABILITY}, returning the found {@link TicketRecord} and
   *       the number of attempts that ran.
   *   <li><b>transient failure.</b> A known id whose every draw is {@code < FLAP_PROBABILITY}
   *       exhausts the retries and returns {@link TicketOutcome#TRANSIENT_FAILURE} with {@code
   *       attemptsUsed == maxAttempts}.
   * </ul>
   *
   * @param ticketId the ticket id to look up; never {@code null} or blank
   * @param randomness a per-attempt flap draw in {@code [0, 1)}; never {@code null}; a draw below
   *     {@link #FLAP_PROBABILITY} flaps (transient) this attempt, otherwise the ticket answers
   * @param maxAttempts the total attempts allowed for a known ticket (initial attempt plus
   *     retries), at least 1; the {@code maxAttempts}-th flap is terminal
   * @return the classified {@link TicketResult}
   * @throws IllegalArgumentException if {@code ticketId} is {@code null} or blank, {@code
   *     randomness} is {@code null}, {@code maxAttempts} is below 1, or a draw is outside {@code
   *     [0, 1)}
   */
  public static TicketResult lookup(String ticketId, Supplier<Double> randomness, int maxAttempts) {
    if (ticketId == null || ticketId.isBlank()) {
      throw new IllegalArgumentException("ticketId must not be null or blank");
    }
    if (randomness == null) {
      throw new IllegalArgumentException("randomness must not be null");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1 but was " + maxAttempts);
    }

    TicketRecord record = FIXTURE.get(ticketId);
    // A missing ticket is a deterministic not-found: the demo backend answers immediately, it does
    // not flap for a record that does not exist, so no flap draw is consumed.
    if (record == null) {
      return TicketResult.notFound(ticketId, 0);
    }

    int attemptsUsed = 0;
    while (attemptsUsed < maxAttempts) {
      attemptsUsed++;
      double draw = randomness.get();
      if (draw < 0.0 || draw >= 1.0) {
        // A draw outside [0, 1) is a misuse of the injection seam, not a tool outcome: reject it
        // loudly so a test pins its inputs rather than silently classifying a bad draw.
        throw new IllegalArgumentException("flap draw must be in [0, 1) but was " + draw);
      }
      // draw < FLAP_PROBABILITY: a transient flap this attempt — retry (bounded by maxAttempts).
      if (draw < FLAP_PROBABILITY) {
        continue;
      }
      // A clean attempt answers the ticket; the attempts used so far are the recovery cost.
      return TicketResult.success(ticketId, record, attemptsUsed);
    }
    // Every attempt flapped: a terminal transient failure (not a contract failure), so the caller
    // sees how many attempts were made before giving up.
    return TicketResult.transientFailure(ticketId, attemptsUsed);
  }
}
