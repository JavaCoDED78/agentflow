package com.example.agentflow.config;

import com.example.agentflow.toolexecution.mcp.McpAllowlist;
import com.example.agentflow.toolexecution.mcp.McpClientAdapter;
import com.example.agentflow.toolexecution.mcp.McpServerPolicy;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
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
 * </ul>
 *
 * <p>The configuration stays free of {@code DataSource}/Kafka dependencies — the allowlist is a
 * pure value and the clients come from the Spring AI MCP client starter's auto-configuration — so
 * it is safe in every profile, including the DB-less skeleton.
 */
@Configuration
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
      spec.clientInfo(new Implementation(connectionName, "1.0"));
      allowlist
          .policyFor(connectionName)
          .ifPresent(policy -> spec.requestTimeout(policy.timeout()));
    };
  }

  /**
   * The MCP client adapter: the connected clients keyed by connection name (empty when the starter
   * built none) plus the shared tool-call pool for the bounded invocation.
   *
   * @param allowlist the operator-configured allowlist
   * @param syncClients the starter-managed MCP sync clients (empty list when no connection is
   *     configured)
   * @param pool the shared tool-call pool (see {@link ToolExecutionConfiguration})
   * @return the fail-closed MCP invocation seam
   */
  @Bean
  McpClientAdapter mcpClientAdapter(
      McpAllowlist allowlist,
      ObjectProvider<List<McpSyncClient>> syncClients,
      ExecutorService pool) {
    Map<String, McpSyncClient> byConnectionName =
        syncClients.getIfAvailable(List::of).stream()
            .filter(client -> client.getClientInfo() != null)
            .collect(
                Collectors.toMap(
                    client -> client.getClientInfo().name(), client -> client, (a, b) -> a));
    return new McpClientAdapter(allowlist, byConnectionName, pool);
  }
}
