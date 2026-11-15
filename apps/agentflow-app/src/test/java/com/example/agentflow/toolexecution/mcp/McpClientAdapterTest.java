package com.example.agentflow.toolexecution.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.toolexecution.application.ToolExecutionError;
import com.example.agentflow.toolexecution.domain.SchemaValidationException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
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
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link McpClientAdapter} (S11-09). Deny paths come first: the S11-09 acceptance is
 * that the model cannot reach an MCP target outside the allowlist configuration, and every deny
 * must happen without a network call beyond the (cached) capability listing.
 */
class McpClientAdapterTest {

  private static final Map<String, Object> TICKET_INPUT_SCHEMA =
      Map.of(
          "type", "object",
          "required", List.of("ticketId"),
          "properties", Map.of("ticketId", Map.of("type", "string")));

  private static final McpServerPolicy LAB_POLICY =
      new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ofSeconds(10), 65_536);

  private static final CallToolResult OK_RESULT =
      new CallToolResult(List.of(new TextContent("PROJ-1: fixed")), false, null, null);

  private ExecutorService pool;
  private McpSyncClient labClient;

  @BeforeEach
  void setUp() {
    pool = Executors.newCachedThreadPool();
    labClient = clientNamed("lab", tool("ticket_lookup", TICKET_INPUT_SCHEMA));
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  private McpClientAdapter adapter(McpAllowlist allowlist, Map<String, McpSyncClient> clients) {
    return new McpClientAdapter(allowlist, clients, pool);
  }

  private static McpSyncClient clientNamed(String name, Tool... tools) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.getClientInfo()).thenReturn(new Implementation(name, "1.0"));
    when(client.listTools()).thenReturn(new ListToolsResult(List.of(tools), null));
    return client;
  }

  private static Tool tool(String name, Map<String, Object> inputSchema) {
    Tool.Builder builder = Tool.builder(name);
    if (inputSchema != null) {
      builder.inputSchema(inputSchema);
    }
    return builder.build();
  }

  // --- deny paths: nothing outside the operator's allowlist may reach a server ---

  @Test
  void unknownServerIsDeniedBeforeAnyClientInteraction() {
    McpClientAdapter adapter = adapter(McpAllowlist.of(Map.of()), Map.of("lab", labClient));
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{}"))
        .isInstanceOfSatisfying(
            McpAccessDeniedException.class,
            e -> assertThat(e.reason()).isEqualTo(McpAccessDeniedException.Reason.UNKNOWN_SERVER));
    verifyNoInteractions(labClient);
  }

  @Test
  void toolOutsideTheAllowlistIsDeniedWithoutListingCapabilities() {
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));
    assertThatThrownBy(() -> adapter.invoke("lab", "delete_everything", "{}"))
        .isInstanceOfSatisfying(
            McpAccessDeniedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(McpAccessDeniedException.Reason.TOOL_NOT_ALLOWLISTED));
    verify(labClient, never()).listTools();
    verify(labClient, never()).callTool(any());
  }

  @Test
  void allowlistedButUnadvertisedToolIsDeniedWithoutCallingIt() {
    McpSyncClient client = clientNamed("lab", tool("other_tool", TICKET_INPUT_SCHEMA));
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", client));
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{}"))
        .isInstanceOfSatisfying(
            McpAccessDeniedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(McpAccessDeniedException.Reason.TOOL_NOT_ADVERTISED));
    verify(client, never()).callTool(any());
  }

  @Test
  void advertisedToolWithoutAnExplicitSchemaAcceptsAnyJsonObject() throws Exception {
    // MCP permits a tool to advertise no input schema; the call contract still requires the
    // arguments to be a JSON object, and the (empty) schema then accepts it.
    McpSyncClient client = clientNamed("lab", tool("ticket_lookup", null));
    when(client.callTool(any())).thenReturn(OK_RESULT);
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", client));

    String result = adapter.invoke("lab", "ticket_lookup", "{\"anything\": true}");

    assertThat(result).isEqualTo("PROJ-1: fixed");
  }

  @Test
  void allowlistedButUnconnectedServerIsDenied() {
    // Policy names the server; no connection exists (the transport URL lives in the Spring AI
    // connection map). Both must name a server for it to be reachable — fail closed.
    McpClientAdapter adapter = adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of());
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{}"))
        .isInstanceOfSatisfying(
            McpAccessDeniedException.class,
            e -> assertThat(e.reason()).isEqualTo(McpAccessDeniedException.Reason.UNKNOWN_SERVER));
  }

  // --- argument validation: only schema-valid arguments cross the seam ---

  @Test
  void malformedJsonArgumentsAreAContractViolation() {
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "not json"))
        .withMessageContaining("JSON");
    verify(labClient, never()).callTool(any());
  }

  @Test
  void nonObjectJsonArgumentsAreAContractViolation() {
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "[1,2]"))
        .withMessageContaining("object");
    verify(labClient, never()).callTool(any());
  }

  @Test
  void argumentsViolatingTheAdvertisedSchemaAreRejectedBeforeTheCall() {
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": 42}"))
        .isInstanceOf(SchemaValidationException.class);
    verify(labClient, never()).callTool(any());
  }

  // --- the happy path and its bounded outcomes ---

  @Test
  void allowlistedToolWithValidArgumentsIsInvokedAndReturnsText() throws Exception {
    when(labClient.callTool(any())).thenReturn(OK_RESULT);
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));

    String result = adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"PROJ-1\"}");

    assertThat(result).isEqualTo("PROJ-1: fixed");
    org.mockito.ArgumentCaptor<CallToolRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CallToolRequest.class);
    verify(labClient).callTool(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("ticket_lookup");
    assertThat(captor.getValue().arguments()).containsEntry("ticketId", "PROJ-1");
  }

  @Test
  void capabilityListingIsFetchedOncePerServer() throws Exception {
    when(labClient.callTool(any())).thenReturn(OK_RESULT);
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));

    adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-1\"}");
    adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-2\"}");

    verify(labClient).listTools();
  }

  @Test
  void oversizedResultIsRefused() throws Exception {
    McpServerPolicy tightPolicy =
        new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ofSeconds(10), 4);
    when(labClient.callTool(any()))
        .thenReturn(new CallToolResult(List.of(new TextContent("hello world")), false, null, null));
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", tightPolicy)), Map.of("lab", labClient));

    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-1\"}"))
        .isInstanceOfSatisfying(
            McpAccessDeniedException.class,
            e ->
                assertThat(e.reason())
                    .isEqualTo(McpAccessDeniedException.Reason.RESPONSE_LIMIT_EXCEEDED));
  }

  @Test
  void errorResultSurfacesAsToolExecutionError() throws Exception {
    when(labClient.callTool(any()))
        .thenReturn(new CallToolResult(List.of(new TextContent("boom")), true, null, null));
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));

    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-1\"}"))
        .isInstanceOf(ToolExecutionError.class);
  }

  @Test
  void structuredContentIsRenderedAsJsonWhenNoTextBlocksExist() throws Exception {
    when(labClient.callTool(any()))
        .thenReturn(
            new CallToolResult(List.of(), false, Map.of("id", "PROJ-1", "status", "fixed"), null));
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));

    String result = adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"PROJ-1\"}");

    assertThat(result).contains("PROJ-1").contains("fixed");
  }

  @Test
  void slowServerTimesOutWithAnAmbiguousOutcome() throws Exception {
    McpServerPolicy fastPolicy =
        new McpServerPolicy("lab", Set.of("ticket_lookup"), Duration.ofMillis(100), 65_536);
    when(labClient.callTool(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(30_000);
              return OK_RESULT;
            });
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", fastPolicy)), Map.of("lab", labClient));

    // An external call is not exactly-once (invariant 6): the outcome is ambiguous and the
    // waiting thread is released; the per-server timeout bounds it.
    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-1\"}"))
        .isInstanceOf(TimeoutException.class);
  }

  @Test
  void capabilityListingFaultSurfacesAsToolExecutionError() {
    McpSyncClient failing = mock(McpSyncClient.class);
    when(failing.getClientInfo()).thenReturn(new Implementation("lab", "1.0"));
    when(failing.listTools()).thenThrow(new RuntimeException("server down"));
    McpClientAdapter adapter =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", failing));

    assertThatThrownBy(() -> adapter.invoke("lab", "ticket_lookup", "{\"ticketId\": \"A-1\"}"))
        .isInstanceOf(ToolExecutionError.class);
  }

  @Test
  void reachabilityRequiresBothPolicyAndConnection() {
    McpClientAdapter connected =
        adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of("lab", labClient));
    assertThat(connected.isReachable("lab")).isTrue();
    assertThat(connected.isReachable("other")).isFalse();

    McpClientAdapter policyOnly = adapter(McpAllowlist.of(Map.of("lab", LAB_POLICY)), Map.of());
    assertThat(policyOnly.isReachable("lab")).isFalse();
  }
}
