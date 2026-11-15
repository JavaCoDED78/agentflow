package com.example.agentflow.toolexecution.mcp;

/**
 * A refusal raised at connection time when a destination fails the outbound policy (S11-10). It is
 * one exception carrying the {@link OutboundDestinationPolicy} that was evaluated and a stable
 * {@link #reason()} — a closed label ({@link OutboundDestinationPolicy#reasonIfDenied()}), never
 * the connection URL — so the caller classifies without parsing a message and without ever exposing
 * the destination through diagnostics (invariant 8).
 *
 * <p>The guard refuses the destination <em>before</em> the client connects (the S11-10 acceptance:
 * the connection never opens, no socket, no metadata probe).
 */
public class OutboundPolicyViolationException extends RuntimeException {

  private final OutboundDestinationPolicy policy;
  private final String reason;

  /**
   * @param policy the evaluated destination policy (known not permitted)
   */
  public OutboundPolicyViolationException(OutboundDestinationPolicy policy) {
    String reason = policy.reasonIfDenied() == null ? "UNSPECIFIED" : policy.reasonIfDenied();
    super(policy.protocol() + " to " + reason + " destination is refused by the outbound policy");
    this.policy = policy;
    this.reason = reason;
  }

  /** The evaluated destination policy. */
  public OutboundDestinationPolicy policy() {
    return policy;
  }

  /** The closed, stable reason the destination was refused — never the connection URL. */
  public String reason() {
    return reason;
  }
}
