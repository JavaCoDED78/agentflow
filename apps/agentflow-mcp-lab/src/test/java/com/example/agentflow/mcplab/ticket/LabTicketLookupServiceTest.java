package com.example.agentflow.mcplab.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The lab's demo tool (S11-08): pure, deterministic, DB-free — the fixture answers from memory, so
 * every outcome is exact. Test-method naming follows the repository's snake_case convention.
 */
class LabTicketLookupServiceTest {

  private final LabTicketLookupService service = new LabTicketLookupService();

  @Test
  void a_known_ticket_returns_its_exact_fixture_values() {
    TicketLookupView view = service.lookup("TKT-1001");

    assertThat(view.found()).isTrue();
    assertThat(view.ticketId()).isEqualTo("TKT-1001");
    assertThat(view.status()).isEqualTo("OPEN");
    assertThat(view.amount()).isEqualByComparingTo(new BigDecimal("49.99"));
    assertThat(view.currency()).isEqualTo("USD");
  }

  @Test
  void every_fixture_ticket_is_answered() {
    assertThat(service.lookup("TKT-1002").status()).isEqualTo("ON_HOLD");
    assertThat(service.lookup("TKT-1002").amount()).isEqualByComparingTo(new BigDecimal("129.00"));
    assertThat(service.lookup("TKT-1003").status()).isEqualTo("CLOSED");
    assertThat(service.lookup("TKT-1003").amount()).isEqualByComparingTo(new BigDecimal("42.50"));
  }

  @Test
  void an_absent_ticket_is_a_first_class_not_found_answer_not_an_error() {
    TicketLookupView view = service.lookup("TKT-9999");

    assertThat(view.found()).isFalse();
    assertThat(view.status()).isEqualTo("NOT_FOUND");
    assertThat(view.amount()).isNull();
    assertThat(view.currency()).isNull();
  }

  @Test
  void lookup_is_exact_and_case_sensitive() {
    assertThat(service.lookup("tkt-1001").found()).isFalse();
    assertThat(service.lookup("TKT-1001 ").found()).isFalse();
  }

  @Test
  void a_null_or_blank_ticket_id_is_a_contract_violation() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.lookup(null));
    assertThatIllegalArgumentException().isThrownBy(() -> service.lookup(" "));
  }

  @Test
  void repeated_lookups_return_equal_views() {
    assertThat(service.lookup("TKT-1002")).isEqualTo(service.lookup("TKT-1002"));
  }
}
