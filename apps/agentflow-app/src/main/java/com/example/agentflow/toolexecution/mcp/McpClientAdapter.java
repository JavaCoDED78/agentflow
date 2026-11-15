package com.example.agentflow.toolexecution.mcp;

import com.example.agentflow.toolexecution.application.ToolCallTimeout;
import com.example.agentflow.toolexecution.application.ToolExecutionError;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.SchemaValidator;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP client adapter (S11-09, ADR-019): the <em>only</em> path from the platform to an MCP
 * server, and a fail-closed one — a call reaches the network only after it has passed the {@link
 * McpAllowlist}, matched a server-advertised tool, and validated its arguments against the
 * advertised input schema.
 *
 * <p>The S11-09 acceptance — <b>the model cannot call an MCP target outside the allowlist
 * configuration</b> — holds because the planner (an untrusted planner, {@code ADR-006}) only ever
 * proposes a {@code (server, tool)} pair; this adapter decides, server-side, from the operator's
 * configuration alone. There is no path that takes a target from the model, a document, or a server
 * response and calls it.
 *
 * <p>Every call is bounded the way a built-in tool call is: the per-server timeout from the {@link
 * McpServerPolicy} is enforced through {@link ToolCallTimeout} (a timed-out call is ambiguous and
 * the pool thread is released, S08-10 semantics), and the result is refused when it exceeds the
 * configured response bound — an MCP server is untrusted input, so its answer is bounded exactly
 * like an upload or a provider completion.
 *
 * <p>The advertised tool catalogue of each server is fetched once per server (lazily, on the first
 * allowed call) and cached: the allowlist is policy, the advertisement is the server's claim, and
 * only their intersection is callable. The adapter holds no tenant state; callers pass the verified
 * context in when a task needs it.
 */
public class McpClientAdapter {

  private final McpAllowlist allowlist;
  private final Map<String, McpSyncClient> clients;
  private final ExecutorService pool;
  private final JsonMapper jsonMapper;
  private final ConcurrentMap<String, Map<String, Tool>> advertisedTools =
      new ConcurrentHashMap<>();

  /**
   * @param allowlist the operator-configured allowlist; the only authorization source
   * @param clients the connected MCP sync clients, keyed by connection name (empty when no
   *     connection is configured); a server allowlisted but absent here is refused at call time
   * @param pool the shared tool-call pool the bounded call runs on
   */
  public McpClientAdapter(
      McpAllowlist allowlist, Map<String, McpSyncClient> clients, ExecutorService pool) {
    if (allowlist == null) {
      throw new IllegalArgumentException("allowlist must not be null");
    }
    if (clients == null) {
      throw new IllegalArgumentException("clients must not be null");
    }
    if (pool == null) {
      throw new IllegalArgumentException("pool must not be null");
    }
    this.allowlist = allowlist;
    this.clients = Map.copyOf(clients);
    this.pool = pool;
    this.jsonMapper = JsonMapper.builder().build();
  }

  /**
   * Invokes one allowlisted tool on one allowlisted server.
   *
   * @param server the connection name to call
   * @param tool the tool name to call
   * @param argumentsJson the tool arguments as a JSON object string
   * @return the tool's textual result
   * @throws McpAccessDeniedException when the target is outside the allowlist, the tool is not
   *     advertised, or the result exceeds the response bound
   * @throws com.example.agentflow.toolexecution.domain.SchemaValidationException when the arguments
   *     do not match the advertised input schema
   * @throws TimeoutException when the call does not answer within the per-server timeout — the
   *     outcome is ambiguous (an external call is not exactly-once, invariant 6)
   * @throws InterruptedException when the waiting thread is interrupted
   */
  public String invoke(String server, String tool, String argumentsJson)
      throws InterruptedException, TimeoutException {
    McpServerPolicy policy =
        allowlist
            .policyFor(server)
            .orElseThrow(
                () ->
                    new McpAccessDeniedException(
                        McpAccessDeniedException.Reason.UNKNOWN_SERVER, "server '" + server + "'"));
    if (!policy.tools().contains(tool)) {
      throw new McpAccessDeniedException(
          McpAccessDeniedException.Reason.TOOL_NOT_ALLOWLISTED,
          "tool '" + tool + "' on server '" + server + "'");
    }
    McpSyncClient client = clients.get(server);
    if (client == null) {
      // Allowlisted in policy but not connected: reachable only when BOTH name the server
      // (the transport URL lives in the Spring AI connection map).
      throw new McpAccessDeniedException(
          McpAccessDeniedException.Reason.UNKNOWN_SERVER,
          "server '" + server + "' is allowlisted but not connected");
    }
    Tool advertised = advertisedTools(client, server).get(tool);
    if (advertised == null) {
      throw new McpAccessDeniedException(
          McpAccessDeniedException.Reason.TOOL_NOT_ADVERTISED,
          "tool '" + tool + "' on server '" + server + "'");
    }
    // Arguments must be a JSON object first (the MCP call contract); the server's advertised
    // schema — which may legally be empty, meaning "any object" — then types the properties.
    Map<String, Object> arguments = asArgumentMap(parseArguments(argumentsJson));
    Map<String, Object> schema =
        advertised.inputSchema() == null ? Map.of() : advertised.inputSchema();
    SchemaValidator.of(JsonSchema.of(schema)).validate(arguments);

    CallToolRequest request = CallToolRequest.builder(tool).arguments(arguments).build();
    CallToolResult result;
    try {
      result = ToolCallTimeout.enforce(pool, policy.timeout(), () -> client.callTool(request));
    } catch (ExecutionException e) {
      // The MCP client's own fault crosses the pool boundary wrapped; surface it unchanged so
      // the caller classifies the tool's genuine failure by its own type.
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new ToolExecutionError(cause);
    }

    String text = extractText(result);
    if (text.getBytes(StandardCharsets.UTF_8).length > policy.maxResponseBytes()) {
      throw new McpAccessDeniedException(
          McpAccessDeniedException.Reason.RESPONSE_LIMIT_EXCEEDED,
          "tool '"
              + tool
              + "' on server '"
              + server
              + "' answered "
              + text.getBytes(StandardCharsets.UTF_8).length
              + " bytes (limit "
              + policy.maxResponseBytes()
              + ")");
    }
    if (Boolean.TRUE.equals(result.isError())) {
      throw new ToolExecutionError(new IllegalStateException("MCP tool returned isError: " + text));
    }
    return text;
  }

  /**
   * The advertised tool catalogue of one server, fetched once and cached. A server that cannot list
   * its tools is a tool fault, wrapped for classification like any other.
   */
  private Map<String, Tool> advertisedTools(McpSyncClient client, String server) {
    return advertisedTools.computeIfAbsent(
        server,
        name -> {
          List<Tool> tools;
          try {
            tools = client.listTools().tools();
          } catch (RuntimeException e) {
            throw new ToolExecutionError(e);
          }
          if (tools == null) {
            return Map.of();
          }
          Map<String, Tool> byName = new java.util.LinkedHashMap<>();
          tools.forEach(t -> byName.putIfAbsent(t.name(), t));
          return Map.copyOf(byName);
        });
  }

  private Object parseArguments(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("arguments must be a JSON object, not null or blank");
    }
    try {
      return jsonMapper.readValue(argumentsJson, Object.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("arguments must be valid JSON: " + e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asArgumentMap(Object arguments) {
    if (!(arguments instanceof Map)) {
      throw new IllegalArgumentException("arguments must be a JSON object");
    }
    return (Map<String, Object>) arguments;
  }

  /** Renders the result as text: the joined text content blocks, or the structured content. */
  private String extractText(CallToolResult result) {
    StringBuilder text = new StringBuilder();
    List<Content> content = result.content();
    if (content != null) {
      for (Content block : content) {
        if (block instanceof TextContent textBlock) {
          if (text.length() > 0) {
            text.append('\n');
          }
          text.append(textBlock.text());
        }
      }
    }
    if (text.length() > 0) {
      return text.toString();
    }
    if (result.structuredContent() != null) {
      try {
        return jsonMapper.writeValueAsString(result.structuredContent());
      } catch (Exception e) {
        throw new ToolExecutionError(e);
      }
    }
    return "";
  }

  /**
   * @param server the connection name
   * @return whether the server is allowlisted and connected — the only servers that can ever be
   *     invoked
   */
  public boolean isReachable(String server) {
    return allowlist.containsServer(server) && clients.containsKey(server);
  }

  /**
   * @return the operator-configured allowlist this adapter enforces
   */
  public McpAllowlist allowlist() {
    return allowlist;
  }
}
