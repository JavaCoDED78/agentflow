package com.example.agentflow.toolexecution.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpAllowlist} (S11-09): the policy seam is deny-by-default, and an empty
 * allowlist is a valid state (the app has no MCP surface at all).
 */
class McpAllowlistTest {

  private static final McpServerPolicy LAB =
      new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ofSeconds(10), 65_536);

  @Test
  void emptyAllowlistResolvesNothing() {
    McpAllowlist allowlist = McpAllowlist.of(null);
    assertThat(allowlist.isEmpty()).isTrue();
    assertThat(allowlist.policyFor("lab")).isEmpty();
    assertThat(allowlist.containsServer("lab")).isFalse();
  }

  @Test
  void unknownServerResolvesEmpty() {
    McpAllowlist allowlist = McpAllowlist.of(Map.of("lab", LAB));
    assertThat(allowlist.isEmpty()).isFalse();
    assertThat(allowlist.policyFor("other")).isEmpty();
    assertThat(allowlist.containsServer("other")).isFalse();
  }

  @Test
  void knownServerResolvesItsPolicy() {
    McpAllowlist allowlist = McpAllowlist.of(Map.of("lab", LAB));
    assertThat(allowlist.policyFor("lab")).contains(LAB);
    assertThat(allowlist.containsServer("lab")).isTrue();
  }

  @Test
  void policyKeyMismatchIsRejected() {
    Map<String, McpServerPolicy> mismatched = new LinkedHashMap<>();
    mismatched.put("lab", LAB);
    mismatched.put("other", LAB);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> McpAllowlist.of(mismatched))
        .withMessageContaining("does not match");
  }

  @Test
  void nullPolicyIsRejected() {
    Map<String, McpServerPolicy> withNull = new LinkedHashMap<>();
    withNull.put("lab", null);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> McpAllowlist.of(withNull))
        .withMessageContaining("must not be null");
  }
}
