package com.example.agentflow.toolexecution.mcp;

/**
 * The classified refusal the MCP client adapter raises when a call is not allowed to proceed
 * (S11-09). It is deliberately one exception with a closed {@link Reason} vocabulary — the caller
 * (the future {@code TOOL_CALL} step handler) classifies by {@link #reason()} without parsing a
 * message, exactly like the tool-executor's other bounded outcomes.
 */
public class McpAccessDeniedException extends RuntimeException {

  /** The closed vocabulary of refusal reasons. */
  public enum Reason {
    /** The server is not in the allowlist (or not connected) — the default-deny answer. */
    UNKNOWN_SERVER,
    /** The server is allowlisted but this tool is not on its allowlist. */
    TOOL_NOT_ALLOWLISTED,
    /** The tool is allowlisted but the server does not advertise it. */
    TOOL_NOT_ADVERTISED,
    /** The tool answered, but the result exceeds the configured response bound. */
    RESPONSE_LIMIT_EXCEEDED
  }

  private final Reason reason;

  /**
   * @param reason the closed refusal reason
   * @param detail a human-readable detail (never carries secrets or payloads)
   */
  public McpAccessDeniedException(Reason reason, String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  /**
   * @return the closed refusal reason, for classification without message parsing
   */
  public Reason reason() {
    return reason;
  }
}
