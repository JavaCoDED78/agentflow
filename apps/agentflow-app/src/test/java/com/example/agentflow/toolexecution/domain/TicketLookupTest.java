package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic {@link TicketLookup} (S08-07).
 *
 * <p>They close the S08-07 acceptance criterion — <em>all outcomes are reproduced by a test</em> —
 * by driving {@link TicketLookup#lookup(String, Supplier, int)} with an injected flap draw so each
 * outcome is deterministic: {@link TicketOutcome#SUCCESS} for a known ticket that answers, {@link
 * TicketOutcome#NOT_FOUND} for a ticket id absent from the fixture, and {@link
 * TicketOutcome#TRANSIENT_FAILURE} for a known ticket that flaps on every attempt (recovered after
 * retries when a later draw clears). They also cover the input guards (blank id, null source,
 * {@code maxAttempts < 1}, an out-of-range draw) and the deterministic shape of the found record.
 * All tests are DB-free and read neither system time nor a system random source (the flap draw is
 * injected, invariant 10).
 */
class TicketLookupTest {

  // A draw below 0.5 flaps (the domain's FLAP_PROBABILITY); >= 0.5 answers.

  /** A source that always flaps, so a known ticket exhausts every attempt. */
  private static final Supplier<Double> ALWAYS_FLAPS = () -> 0.0;

  /** A source that never flaps, so a known ticket answers on the first attempt. */
  private static final Supplier<Double> NEVER_FLAPS = () -> 0.9;

  @Test
  void a_known_ticket_answers_success_on_a_clean_attempt() {
    TicketResult result = TicketLookup.lookup("TKT-1001", NEVER_FLAPS, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.SUCCESS);
    assertThat(result.ticketId()).isEqualTo("TKT-1001");
    assertThat(result.attemptsUsed()).isEqualTo(1);
    assertThat(result.record()).isNotNull();
    assertThat(result.record().customer()).isEqualTo("Alice Nguyen");
    assertThat(result.record().status()).isEqualTo(TicketStatus.OPEN);
    assertThat(result.record().amount().compareTo(new BigDecimal("49.99"))).isZero();
  }

  @Test
  void a_ticket_that_flaps_then_clears_succeeds_after_retries() {
    // Flaps twice (0.0, 0.0) then answers (0.9); a transient failure a retry recovered.
    Supplier<Double> flapsThenClears = flapping(0.0, 0.0, 0.9);
    TicketResult result = TicketLookup.lookup("TKT-1003", flapsThenClears, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.SUCCESS);
    assertThat(result.ticketId()).isEqualTo("TKT-1003");
    assertThat(result.record().customer()).isEqualTo("Carol Diaz");
    assertThat(result.attemptsUsed()).isEqualTo(3);
  }

  @Test
  void a_ticket_that_flaps_on_every_attempt_is_a_transient_failure() {
    TicketResult result = TicketLookup.lookup("TKT-1002", ALWAYS_FLAPS, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.TRANSIENT_FAILURE);
    assertThat(result.ticketId()).isEqualTo("TKT-1002");
    assertThat(result.record()).isNull();
    // The terminal failure reports the exhausted attempt count, so the caller sees the cost.
    assertThat(result.attemptsUsed()).isEqualTo(3);
  }

  @Test
  void an_absent_ticket_is_a_deterministic_not_found_without_a_flap_draw() {
    // A not-found must not flap: the absent id answers before any draw is consumed.
    TicketResult result = TicketLookup.lookup("TKT-9999", ALWAYS_FLAPS, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.NOT_FOUND);
    assertThat(result.ticketId()).isEqualTo("TKT-9999");
    assertThat(result.attemptsUsed()).isEqualTo(0);
    assertThat(result.record()).isNull();
  }

  @Test
  void a_clean_attempt_at_the_boundary_draw_succeeds() {
    // Exactly FLAP_PROBABILITY is a clean attempt (draw >= FLAP_PROBABILITY answers).
    TicketResult result = TicketLookup.lookup("TKT-1001", () -> 0.5, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.SUCCESS);
    assertThat(result.attemptsUsed()).isEqualTo(1);
  }

  @Test
  void a_draw_just_below_the_boundary_flaps_once_then_answers() {
    Supplier<Double> flapsOnce = flapping(0.49, 0.9);
    TicketResult result = TicketLookup.lookup("TKT-1001", flapsOnce, 3);

    assertThat(result.outcome()).isEqualTo(TicketOutcome.SUCCESS);
    assertThat(result.attemptsUsed()).isEqualTo(2);
  }

  @Test
  void the_production_default_entry_point_never_throws_for_known_or_unknown_tickets() {
    // The no-seam entry point uses the shared flapping source, so a known ticket answers or
    // (rarely)
    // flaps, and an absent ticket answers not-found; neither throws.
    TicketResult known = TicketLookup.lookup("TKT-1001");
    assertThat(known.outcome()).isIn(TicketOutcome.SUCCESS, TicketOutcome.TRANSIENT_FAILURE);
    assertThat(known.record()).isNotNull();

    TicketResult missing = TicketLookup.lookup("TKT-DOES-NOT-EXIST");
    assertThat(missing.outcome()).isEqualTo(TicketOutcome.NOT_FOUND);
  }

  @Test
  void the_outcome_is_deterministic_for_the_same_inputs() {
    TicketResult first = TicketLookup.lookup("TKT-1002", ALWAYS_FLAPS, 3);
    TicketResult second = TicketLookup.lookup("TKT-1002", ALWAYS_FLAPS, 3);

    assertThat(first.outcome()).isEqualTo(second.outcome());
    assertThat(first.attemptsUsed()).isEqualTo(second.attemptsUsed());
  }

  // ---- input guards -----------------------------------------------------

  @Test
  void a_blank_ticket_id_is_rejected() {
    assertThatThrownBy(() -> TicketLookup.lookup("   ", NEVER_FLAPS, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ticketId");
    assertThatThrownBy(() -> TicketLookup.lookup(null, NEVER_FLAPS, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ticketId");
  }

  @Test
  void a_null_flap_source_is_rejected() {
    assertThatThrownBy(() -> TicketLookup.lookup("TKT-1001", null, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("randomness");
  }

  @Test
  void a_max_attempts_below_one_is_rejected() {
    assertThatThrownBy(() -> TicketLookup.lookup("TKT-1001", NEVER_FLAPS, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxAttempts");
  }

  @Test
  void a_flap_draw_outside_zero_one_is_rejected() {
    assertThatThrownBy(() -> TicketLookup.lookup("TKT-1001", () -> 1.0, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("flap draw");
    assertThatThrownBy(() -> TicketLookup.lookup("TKT-1001", () -> -0.1, 3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("flap draw");
  }

  /**
   * A source whose first {@code draws} returns each value in order, then always returns the last
   * value — so a lookup consumes them one per attempt.
   *
   * @param draws the per-attempt draws to return in order
   * @return a stateful flap source that hands out the draws
   */
  private static Supplier<Double> flapping(double... draws) {
    int[] index = {0};
    return () -> {
      int i = index[0];
      if (i < draws.length) {
        index[0] = i + 1;
      }
      return draws[i];
    };
  }
}
