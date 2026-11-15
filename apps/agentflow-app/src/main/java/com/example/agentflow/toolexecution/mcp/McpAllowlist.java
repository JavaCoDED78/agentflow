package com.example.agentflow.toolexecution.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP client allowlist (S11-09): the complete, normalized set of {@link McpServerPolicy}
 * entries the platform may call through.
 *
 * <p>It is the single authorization seam between the untrusted planner ({@code ADR-006}, invariant
 * 7) and an MCP server: the {@link McpClientAdapter} consults it <em>before</em> any network I/O,
 * and everything not in it is refused — an unknown server is indistinguishable from a server that
 * simply is not allowlisted (deny by default, fail closed). The model never learns about a server
 * or a tool from the server itself; the operator's configuration is the only source.
 *
 * <p>It is a pure, immutable value: no Spring, no I/O, no time. An empty allowlist is a valid state
 * — the default — meaning the app has no MCP surface at all (the skeleton boots unchanged, S01-02).
 */
public final class McpAllowlist {

  private final Map<String, McpServerPolicy> servers;

  private McpAllowlist(Map<String, McpServerPolicy> servers) {
    this.servers = Map.copyOf(servers);
  }

  /**
   * Builds the allowlist from validated per-server policies.
   *
   * @param policies the allowlisted server policies (a {@code null} map means none)
   * @return the immutable allowlist; empty when no policies are given
   */
  public static McpAllowlist of(Map<String, McpServerPolicy> policies) {
    if (policies == null || policies.isEmpty()) {
      return new McpAllowlist(Map.of());
    }
    Map<String, McpServerPolicy> copy = new LinkedHashMap<>();
    policies.forEach(
        (name, policy) -> {
          if (policy == null) {
            throw new IllegalArgumentException("policy for server '" + name + "' must not be null");
          }
          if (!policy.server().equals(name)) {
            throw new IllegalArgumentException(
                "policy server '" + policy.server() + "' does not match its key '" + name + "'");
          }
          copy.put(name, policy);
        });
    return new McpAllowlist(copy);
  }

  /**
   * Resolves the policy for a server.
   *
   * @param server the connection name to look up
   * @return the policy, or {@link Optional#empty()} when the server is not allowlisted — the caller
   *     must refuse the call; there is no default-allow path
   */
  public Optional<McpServerPolicy> policyFor(String server) {
    return Optional.ofNullable(servers.get(server));
  }

  /**
   * @param server the connection name
   * @return {@code true} when a policy names the server
   */
  public boolean containsServer(String server) {
    return servers.containsKey(server);
  }

  /**
   * @return {@code true} when no server is allowlisted (the app has no MCP surface)
   */
  public boolean isEmpty() {
    return servers.isEmpty();
  }
}
