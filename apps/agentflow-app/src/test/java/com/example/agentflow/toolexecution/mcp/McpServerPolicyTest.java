package com.example.agentflow.toolexecution.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link McpServerPolicy} (S11-09): an invalid policy must fail, not soften. */
class McpServerPolicyTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Test
  void validPolicyIsImmutable() {
    McpServerPolicy policy = new McpServerPolicy("lab", Set.of("ticket_lookup"), TIMEOUT, 65_536);
    assertThat(policy.server()).isEqualTo("lab");
    assertThat(policy.tools()).containsExactly("ticket_lookup");
    assertThat(policy.timeout()).isEqualTo(TIMEOUT);
    assertThat(policy.maxResponseBytes()).isEqualTo(65_536);
  }

  @Test
  void blankServerIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new McpServerPolicy(" ", Set.of("ticket_lookup"), TIMEOUT, 65_536))
        .withMessageContaining("server");
  }

  @Test
  void emptyToolSetIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new McpServerPolicy("lab", Set.of(), TIMEOUT, 65_536))
        .withMessageContaining("tools");
  }

  @Test
  void blankToolNameIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new McpServerPolicy("lab", Set.of("ticket_lookup", " "), TIMEOUT, 65_536))
        .withMessageContaining("blank");
  }

  @Test
  void nullOrNonPositiveTimeoutIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new McpServerPolicy("lab", Set.of("ticket_lookup"), null, 65_536));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ZERO, 65_536));
  }

  @Test
  void nonPositiveResponseBoundIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new McpServerPolicy("lab", Set.of("ticket_lookup"), TIMEOUT, 0))
        .withMessageContaining("maxResponseBytes");
  }
}
