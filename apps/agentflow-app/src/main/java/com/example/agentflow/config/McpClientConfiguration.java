package com.example.agentflow.config;

import com.example.agentflow.toolexecution.mcp.McpAllowlist;
import com.example.agentflow.toolexecution.mcp.McpClientAdapter;
import com.example.agentflow.toolexecution.mcp.McpServerPolicy;
import com.example.agentflow.toolexecution.mcp.OutboundDestinationPolicy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP client wiring (S11-09, ADR-019).
 *
 * <p>Three beans close the allowlist seam:
 *
 * <ul>
 *   <li>{@link McpAllowlist} — the pure policy value built from {@code agentflow.mcp.*}; empty by
 *       default, so an unconfigured app has no MCP surface (S01-02 — the skeleton boots unchanged).
 *   <li>{@link McpClientCustomizer} — names every connection's client info after its connection
 *       name (so the adapter can map a client back to its policy entry deterministically) and
 *       applies the per-server request timeout from the allowlist at the transport level.
 *   <li>{@link McpClientAdapter} — the only invocation path; it receives the connected {@link
 *       McpSyncClient}s keyed by connection name and the shared tool-call pool. With no connections
 *       configured the client list is empty and the adapter is inert.
 *   <li>The outbound destination policy (S11-10): each transport URL in the Spring AI connection
 *       map is bound to an {@link OutboundDestinationPolicy} (fail-fast on a malformed URL) and
 *       handed to the adapter, so an allowlisted connection whose host is loopback, private or
 *       link-local (which includes the metadata endpoint) is refused before the client connects.
 * </ul>
 *
 * <p>The configuration stays free of {@code DataSource}/Kafka dependencies — the allowlist is a
 * pure value and the clients come from the Spring AI MCP client starter's auto-configuration — so
 * it is safe in every profile, including the DB-less skeleton.
 */
@Configuration
@EnableConfigurationProperties(McpClientConfiguration.SpringAiMcpStreamableHttp.class)
public class McpClientConfiguration {

  /**
   * Builds the normalized allowlist from the validated {@code agentflow.mcp.*} group.
   *
   * @param properties the validated application properties
   * @return the immutable allowlist (empty when no server is configured)
   */
  @Bean
  McpAllowlist mcpAllowlist(AgentFlowProperties properties) {
    Map<String, McpServerPolicy> policies =
        properties.getMcp().getServers().entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry ->
                        new McpServerPolicy(
                            entry.getKey(),
                            java.util.Set.copyOf(entry.getValue().getTools()),
                            Duration.ofSeconds(entry.getValue().getTimeoutSeconds()),
                            entry.getValue().getMaxResponseBytes()),
                    (a, b) -> a,
                    java.util.LinkedHashMap::new));
    return McpAllowlist.of(policies);
  }

  /**
   * Applies the allowlist policy to every MCP connection the starter builds: the client info name
   * becomes the connection name (the adapter's deterministic client → policy mapping), and the
   * per-server timeout becomes the transport-level request timeout. Connections not named in the
   * allowlist are still built (the starter owns that decision) but can never be invoked — the
   * adapter refuses anything outside the allowlist.
   *
   * @param allowlist the operator-configured allowlist
   * @return the name- and timeout-applying customizer
   */
  @Bean
  McpClientCustomizer<McpClient.SyncSpec> mcpPolicyCustomizer(McpAllowlist allowlist) {
    return (connectionName, spec) -> {
      spec.clientInfo(Implementation.builder(connectionName, "1.0").build());
      allowlist
          .policyFor(connectionName)
          .ifPresent(policy -> spec.requestTimeout(policy.timeout()));
    };
  }

  /**
   * The MCP client adapter: the connected clients keyed by connection name (empty when the starter
   * built none) plus the shared tool-call pool and the outbound destination policy (S11-10) for the
   * bounded invocation.
   *
   * @param allowlist the operator-configured allowlist
   * @param syncClients the starter-managed MCP sync clients (empty list when no connection is
   *     configured)
   * @param transportUrls the per-connection transport URLs, read from the Spring AI connection map
   *     ({@code spring.ai.mcp.client.streamable-http.connections.<name>.url}); only connections
   *     named in the allowlist are bound to a destination policy
   * @param pool the shared tool-call pool (see {@link ToolExecutionConfiguration})
   * @return the fail-closed MCP invocation seam
   */
  @Bean
  McpClientAdapter mcpClientAdapter(
      McpAllowlist allowlist,
      ObjectProvider<List<McpSyncClient>> syncClients,
      SpringAiMcpStreamableHttp transportUrls,
      ExecutorService pool) {
    Map<String, McpSyncClient> byConnectionName =
        syncClients.getIfAvailable(List::of).stream()
            .filter(client -> client.getClientInfo() != null)
            .collect(
                Collectors.toMap(
                    client -> client.getClientInfo().name(), client -> client, (a, b) -> a));
    // Bind a destination policy for each allowlisted connection (deny-by-default: a connection the
    // operator did not allowlist needs no policy — it can never be invoked). A malformed URL on an
    // allowlisted connection fails the context here (S11-10 — the binding fails fast).
    Map<String, OutboundDestinationPolicy> destinationPolicies =
        transportUrls.getConnections().entrySet().stream()
            .filter(entry -> allowlist.containsServer(entry.getKey()))
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> OutboundDestinationPolicy.of(entry.getValue().getUrl()),
                    (a, b) -> a,
                    LinkedHashMap::new));
    return new McpClientAdapter(allowlist, byConnectionName, destinationPolicies, pool);
  }

  /**
   * The Spring AI Streamable-HTTP connection map, bound from {@code
   * spring.ai.mcp.client.streamable-http.connections.<name>.url}. It is the transport side of the
   * MCP seam (S11-09): {@code agentflow.mcp.*} is the authorization policy, this map is the
   * transport the connection rides. It is used only by {@link #mcpClientAdapter} to bind an {@link
   * OutboundDestinationPolicy} per connection (S11-10). Empty by default.
   */
  @ConfigurationProperties(prefix = "spring.ai.mcp.client.streamable-http")
  static class SpringAiMcpStreamableHttp {
    private final Map<String, Url> connections = new LinkedHashMap<>();

    /**
     * @return the connections keyed by connection name, with their transport URL (empty by default)
     */
    public Map<String, Url> getConnections() {
      return connections;
    }

    /** The transport URL of one connection ({@code <name>.url}). */
    static class Url {
      private String url;

      public String getUrl() {
        return url;
      }

      public void setUrl(String url) {
        this.url = url;
      }
    }
  }
}
