package com.example.agentflow.toolexecution.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S11-10 wiring inside {@link McpClientAdapter}: the per-connection outbound
 * destination policy gates both {@link McpClientAdapter#isReachable(String)} and {@link
 * McpClientAdapter#invoke(String, String, String)} — a refused host is never connected, and the
 * absence of a policy is inert (never a bypass).
 */
class McpClientOutboundPolicyTest {

  private static final McpServerPolicy LAB_POLICY =
      new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ofSeconds(10), 65_536);

  private static final CallToolResult OK_RESULT =
      new CallToolResult(List.of(new TextContent("ok")), false, null, null);

  private ExecutorService pool;
  private McpSyncClient labClient;

  @BeforeEach
  void setUp() {
    pool = Executors.newCachedThreadPool();
    labClient = clientNamed("lab");
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private McpClientAdapter adapter(
      McpAllowlist allowlist,
      Map<String, McpSyncClient> clients,
      Map<String, OutboundDestinationPolicy> destinationPolicies) {
    return new McpClientAdapter(allowlist, clients, destinationPolicies, pool);
  }

  private static McpAllowlist allowlistedLab() {
    return McpAllowlist.of(Map.of("lab", LAB_POLICY));
  }

  private static McpSyncClient clientNamed(String name) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.getClientInfo()).thenReturn(new Implementation(name, "1.0"));
    when(client.listTools()).thenReturn(new ListToolsResult(List.of(tool()), null));
    return client;
  }

  private static Tool tool() {
    return Tool.builder("ticket_lookup").build();
  }

  private static Map<String, OutboundDestinationPolicy> of(String url) {
    return Map.of("lab", OutboundDestinationPolicy.of(url));
  }

  // --- isReachable refuses the refused hosts before a single client call ---

  @Test
  void isReachableRefusesLoopbackBeforeConnecting() {
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://127.0.0.1:9999/mcp"));
    assertThatThrownBy(() -> adapter.isReachable("lab"))
        .isInstanceOfSatisfying(
            OutboundPolicyViolationException.class,
            e -> assertThat(e.reason()).isEqualTo("LOOPBACK"));
    // A server outside the allowlist is simply unreachable (no policy involved).
    assertThat(adapter.isReachable("other")).isFalse();
  }

  @Test
  void isReachableRefusesLinkLocalMetadataEndpoint() {
    McpClientAdapter adapter =
        adapter(
            allowlistedLab(),
            Map.of("lab", labClient),
            of("http://169.254.169.254/latest/meta-data/"));
    assertThatThrownBy(() -> adapter.isReachable("lab"))
        .isInstanceOfSatisfying(
            OutboundPolicyViolationException.class,
            e -> assertThat(e.reason()).isEqualTo("LINK_LOCAL"));
  }

  @Test
  void isReachableRefusesPrivateRange() {
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://10.0.0.5:9999/mcp"));
    assertThatThrownBy(() -> adapter.isReachable("lab"))
        .isInstanceOfSatisfying(
            OutboundPolicyViolationException.class,
            e -> assertThat(e.reason()).isEqualTo("PRIVATE_RANGE"));
  }

  @Test
  void isReachablePermitsPublicHostWhenConnected() {
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://93.184.216.34:8080/mcp"));
    assertThat(adapter.isReachable("lab")).isTrue();
  }

  @Test
  void absentDestinationPolicyIsInertNeverABypass() {
    // The 3-arg entry point (no destination policy) — an allowlisted + connected server stays
    // reachable; the guard's absence never opens a bypass, it simply does not run.
    McpClientAdapter adapter =
        new McpClientAdapter(allowlistedLab(), Map.of("lab", labClient), pool);
    assertThat(adapter.isReachable("lab")).isTrue();
  }

  // --- invoke enforces the guard even when the reachability gate is bypassed ---

  @Test
  void invokeRefusesLoopbackBeforeCallingTheTool() {
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://127.0.0.1:9999/mcp"));
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\":\"A-1\"}"))
        .isInstanceOfSatisfying(
            OutboundPolicyViolationException.class,
            e -> assertThat(e.reason()).isEqualTo("LOOPBACK"));
  }

  @Test
  void invokeProceedsForAPermittedHost() throws Exception {
    when(labClient.callTool(any())).thenReturn(OK_RESULT);
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://93.184.216.34:8080/mcp"));
    String result = adapter.invoke("lab", "ticket_lookup", "{\"ticketId\":\"A-1\"}");
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void violationExceptionLeavesNoUrlInDiagnostics() {
    McpClientAdapter adapter =
        adapter(allowlistedLab(), Map.of("lab", labClient), of("http://127.0.0.1:9999/mcp"));
    assertThatThrownBy(() -> adapter.isReachable("lab"))
        .isInstanceOfSatisfying(
            OutboundPolicyViolationException.class,
            e -> {
              assertThat(e.reason()).isEqualTo("LOOPBACK");
              assertThat(e.policy())
                  .isEqualTo(OutboundDestinationPolicy.of("http://127.0.0.1:9999/mcp"));
              assertThat(e.getMessage()).doesNotContain("127.0.0.1");
              assertThat(e.getMessage()).doesNotContain("9999");
            });
  }
}
