package com.example.agentflow.workflowexecution.domain;

/**
 * A step input mapping failure (S08-12): the node's {@code input} wiring could not be resolved
 * against the completed predecessors' outputs. It is thrown <em>before</em> the step's handler (and
 * therefore any tool) is invoked, so an unknown path, a type violation or a null value never
 * reaches a call.
 *
 * <p>It extends {@link IllegalArgumentException} deliberately: the {@code StepFailureClassifier}
 * (S07-11) maps an {@code IllegalArgumentException} to {@link
 * com.example.agentflow.common.ErrorClass#VALIDATION} — a permanent, never-retried contract failure
 * — so a broken wiring fails the step (and surfaces on the run) instead of burning the retry budget
 * on a fault a retry cannot overcome.
 *
 * <p>The {@code reason()} is a stable, machine-readable code (the {@code CalculationException} /
 * {@code SchemaValidationException} convention), so the executor and the audit trail classify the
 * failure without parsing the message:
 *
 * <ul>
 *   <li>{@code invalid-literal} — an input value that is neither a string literal nor a reference
 *       object (defense in depth: the activation gate rejects it first);
 *   <li>{@code invalid-reference} — a malformed reference object (missing / non-string {@code node}
 *       or {@code output}, or an unknown extra field);
 *   <li>{@code unknown-node} — the referenced node does not exist in the graph;
 *   <li>{@code unknown-output} — the referenced node exists but does not declare that output name:
 *       the "unknown path" rejection — only declared named outputs are reachable, never arbitrary
 *       fields of the upstream result;
 *   <li>{@code missing-output} — the referenced step has no persisted output (it never completed)
 *       or the output document does not carry the declared name (nullability: a wiring must resolve
 *       to a value);
 *   <li>{@code null-output} — the declared output exists in the document but is JSON {@code null}.
 * </ul>
 */
public class InputMappingException extends IllegalArgumentException {

  private final String reason;

  /**
   * @param reason the stable machine-readable reason code (one of the codes documented above)
   * @param message the human-readable, safe description (never a secret, prompt or document)
   */
  public InputMappingException(String reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * @return the stable machine-readable reason code (e.g. {@code "unknown-output"})
   */
  public String reason() {
    return reason;
  }
}
