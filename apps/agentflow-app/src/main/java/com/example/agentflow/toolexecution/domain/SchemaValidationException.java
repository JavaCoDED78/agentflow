package com.example.agentflow.toolexecution.domain;

/**
 * Thrown by {@link SchemaValidator} when a parsed value does not conform to a JSON Schema.
 *
 * <p>It is the tool-layer's before-call guard (S08-03): the executor validates a Planner's
 * arguments against the tool's {@link ToolDescriptor#inputSchema()} before invoking the tool, and a
 * {@code SchemaValidationException} thrown there blocks the call <em>before any side effect</em>.
 * The same engine validates a tool's result in {@code S08-04}.
 *
 * <p>The {@link #reason()} is a stable, short code (for example {@code type-mismatch}, {@code
 * missing-required}, {@code unknown-property}, {@code payload-too-large}, {@code payload-too-deep})
 * so the caller can classify the failure — for example to pick a retry decision (S08-14) — without
 * parsing the human-readable message.
 */
public class SchemaValidationException extends RuntimeException {

  private final String reason;

  /**
   * @param reason the stable, short failure code; never {@code null}
   */
  public SchemaValidationException(String reason) {
    super("schema validation failed (" + reason + ")");
    this.reason = reason;
  }

  /**
   * @return the stable, short failure code ({@code type-mismatch}, {@code missing-required}, {@code
   *     unknown-property}, {@code payload-too-large}, {@code payload-too-deep}, …)
   */
  public String reason() {
    return reason;
  }
}
