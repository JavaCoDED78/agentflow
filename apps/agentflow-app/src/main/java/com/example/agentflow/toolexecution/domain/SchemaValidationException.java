package com.example.agentflow.toolexecution.domain;

/**
 * Thrown by {@link SchemaValidator} when a parsed value does not conform to a JSON Schema.
 *
 * <p>It is the tool-layer's schema-validation guard. It classifies <em>both</em> roles of the
 * engine: a mismatch on the Planner's arguments ({@code S08-03}) blocks the call <em>before any
 * side effect</em>, and a mismatch on the tool's result ({@code S08-04}) blocks the step from being
 * marked <em>SUCCEEDED</em> — so an invalid output is never accepted, forwarded to the next step,
 * or checkpointed. In both cases it is a <em>deterministic</em> contract failure (distinct from a
 * transient execution error the tool may raise before producing a result), so the executor
 * classifies it without string parsing.
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
