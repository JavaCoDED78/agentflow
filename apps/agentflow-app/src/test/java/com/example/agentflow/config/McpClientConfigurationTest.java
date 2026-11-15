package com.example.agentflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.agentflow.toolexecution.mcp.McpAllowlist;
import com.example.agentflow.toolexecution.mcp.McpClientAdapter;
import com.example.agentflow.toolexecution.mcp.OutboundPolicyViolationException;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binding and wiring tests for the MCP client configuration (S11-09): the {@code agentflow.mcp.*}
 * group binds and validates, and the allowlist/adapter/customizer beans close the seam without a
 * database or Kafka (S01-02 — the skeleton boots unchanged when nothing is configured).
 */
class McpClientConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              PropertiesConfig.class, McpClientConfiguration.class, PoolConfig.class)
          // AgentFlowProperties carries required fields beyond the MCP group; give them valid
          // defaults so the runner exercises only what this test binds.
          .withPropertyValues(
              "agentflow.app-name=test-app",
              "agentflow.security.issuer-uri=https://issuer.example/realm/agentflow",
              "agentflow.security.audience=agentflow-api");

  private ExecutorService pool;

  @AfterEach
  void tearDown() {
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  @Test
  void emptyConfigurationYieldsAnEmptyAllowlistAndAnInertAdapter() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          McpAllowlist allowlist = context.getBean(McpAllowlist.class);
          assertThat(allowlist.isEmpty()).isTrue();
          McpClientAdapter adapter = context.getBean(McpClientAdapter.class);
          assertThat(adapter.isReachable("lab")).isFalse();
        });
  }

  @Test
  void serverEntryBindsWithDefaults() {
    runner
        .withPropertyValues("agentflow.mcp.servers.lab.tools[0]=ticket_lookup")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              McpAllowlist allowlist = context.getBean(McpAllowlist.class);
              assertThat(allowlist.policyFor("lab"))
                  .hasValueSatisfying(
                      policy -> {
                        assertThat(policy.tools()).containsExactly("ticket_lookup");
                        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(10));
                        assertThat(policy.maxResponseBytes()).isEqualTo(65_536);
                      });
            });
  }

  @Test
  void explicitBoundsBind() {
    runner
        .withPropertyValues(
            "agentflow.mcp.servers.lab.tools[0]=ticket_lookup",
            "agentflow.mcp.servers.lab.tools[1]=ticket_search",
            "agentflow.mcp.servers.lab.timeout-seconds=3",
            "agentflow.mcp.servers.lab.max-response-bytes=1024")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              McpAllowlist allowlist = context.getBean(McpAllowlist.class);
              assertThat(allowlist.policyFor("lab"))
                  .hasValueSatisfying(
                      policy -> {
                        assertThat(policy.tools())
                            .containsExactlyInAnyOrder("ticket_lookup", "ticket_search");
                        assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(3));
                        assertThat(policy.maxResponseBytes()).isEqualTo(1024);
                      });
            });
  }

  @Test
  void serverEntryWithoutAnyToolFailsValidation() {
    runner
        .withPropertyValues("agentflow.mcp.servers.lab.tools=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void adapterMapsConnectedClientsByConnectionName() {
    runner
        .withUserConfiguration(ScriptedClientsConfig.class)
        .withPropertyValues("agentflow.mcp.servers.lab.tools[0]=ticket_lookup")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              McpClientAdapter adapter = context.getBean(McpClientAdapter.class);
              assertThat(adapter.isReachable("lab")).isTrue();
              assertThat(adapter.isReachable("unknown")).isFalse();
            });
  }

  @Test
  void customizerToleratesConnectionsOutsideTheAllowlist() {
    // The starter still builds connections the operator did not allowlist (transport is its
    // concern); the customizer must not fail on them — the adapter simply never invokes them.
    runner
        .withPropertyValues("agentflow.mcp.servers.lab.tools[0]=ticket_lookup")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              @SuppressWarnings("unchecked")
              McpClientCustomizer<McpClient.SyncSpec> customizer =
                  context.getBean(McpClientCustomizer.class);
              McpClient.SyncSpec spec = McpClient.sync(mock(McpClientTransport.class));
              customizer.customize("lab", spec);
              customizer.customize("not-allowlisted", spec);
            });
  }

  @Test
  void malformedOutboundUrlFailsContextStartup() {
    // A non-http transport URL on an allowlisted connection fails the binding (S11-10 — fail-fast
    // on a malformed destination policy), never surfacing as a later runtime surprise.
    runner
        .withPropertyValues(
            "agentflow.mcp.servers.lab.tools[0]=ticket_lookup",
            "spring.ai.mcp.client.streamable-http.connections.lab.url=ws://localhost/mcp")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void loopbackTransportIsRefusedAtConnectionTime() {
    // A loopback transport URL binds cleanly (it parses), but the connection is refused at the
    // reachability gate (S11-10 acceptance): the context boots, and isReachable throws — never
    // returns true — before any client connects.
    runner
        .withUserConfiguration(ScriptedClientsConfig.class)
        .withPropertyValues(
            "agentflow.mcp.servers.lab.tools[0]=ticket_lookup",
            "spring.ai.mcp.client.streamable-http.connections.lab.url=http://127.0.0.1:9999/mcp")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              McpClientAdapter adapter = context.getBean(McpClientAdapter.class);
              assertThatThrownBy(() -> adapter.isReachable("lab"))
                  .isInstanceOf(OutboundPolicyViolationException.class);
            });
  }

  private static McpSyncClient clientNamed(String name) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.getClientInfo()).thenReturn(new Implementation(name, "1.0"));
    return client;
  }

  @Configuration
  @EnableConfigurationProperties(AgentFlowProperties.class)
  static class PropertiesConfig {}

  @Configuration
  static class PoolConfig {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Bean
    ExecutorService toolCallPool() {
      return pool;
    }
  }

  /**
   * A scripted connected client; the @Bean method's generic return type preserves {@code
   * List<McpSyncClient>} so the adapter's {@code ObjectProvider<List<McpSyncClient>>} wiring is
   * exercised exactly as in the real application.
   */
  @Configuration
  static class ScriptedClientsConfig {

    @Bean
    List<McpSyncClient> mcpSyncClients() {
      return List.of(clientNamed("lab"));
    }
  }
}
