package com.example.agentflow.toolexecution.mcp;

import java.time.Duration;
import java.util.Set;

/**
 * The allowlist policy for one MCP server (S11-09): the exact tool names the platform may call on
 * it, the per-call timeout, and the maximum accepted result size.
 *
 * <p>It is a pure, immutable value object: no Spring, no I/O, no clock (invariant 10). The
 * canonical constructor rejects a blank server name, an empty or blank-containing tool set, a
 * non-positive timeout, or a non-positive response bound — an invalid policy is a configuration
 * error that must fail startup, never silently widen or narrow the allowlist.
 *
 * @param server the Spring AI connection name this policy applies to (non-blank)
 * @param tools the exact tool names allowed on this server (non-empty, no blanks)
 * @param timeout the per-call timeout (strictly positive)
 * @param maxResponseBytes the maximum accepted result size in bytes (strictly positive)
 */
public record McpServerPolicy(
    String server, Set<String> tools, Duration timeout, int maxResponseBytes) {

  /**
   * Canonical-constructor validation (see the class javadoc). The tool set is copied into an
   * unmodifiable set so a caller cannot widen the allowlist after construction.
   */
  public McpServerPolicy {
    if (server == null || server.isBlank()) {
      throw new IllegalArgumentException("server must not be null or blank");
    }
    if (tools == null || tools.isEmpty()) {
      throw new IllegalArgumentException("tools must not be null or empty");
    }
    if (tools.stream().anyMatch(tool -> tool == null || tool.isBlank())) {
      throw new IllegalArgumentException("tools must not contain null or blank names");
    }
    if (timeout == null || !timeout.isPositive()) {
      throw new IllegalArgumentException("timeout must be strictly positive but was " + timeout);
    }
    if (maxResponseBytes < 1) {
      throw new IllegalArgumentException(
          "maxResponseBytes must be at least 1 but was " + maxResponseBytes);
    }
    tools = Set.copyOf(tools);
  }
}
