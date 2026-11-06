package com.example.agentflow.toolexecution.domain;

/**
 * Thrown by {@link Calculator} when an expression cannot be safely evaluated.
 *
 * <p>It is the tool-layer's calculator guard (S08-05). A calculator runs a <em>safe,
 * deterministic</em> arithmetic expression — the {@code S08-05} acceptance is that {@code
 * calculator.evaluate} handles ordinary operations, division by zero, overflow and invalid input —
 * so this is never a transient or external fault: it is a <em>deterministic</em> contract failure
 * on the expression itself, the calculator-layer counterpart to {@link SchemaValidationException}
 * on a payload. It is thrown from the pure evaluator before any side effect, so a hostile or
 * malformed expression never reaches the rest of the executor.
 *
 * <p>The {@link #reason()} is a stable, short code the executor can classify without parsing the
 * human-readable message (the same convention as {@link SchemaValidationException}):
 *
 * <ul>
 *   <li>{@code invalid-token} — an unknown character, a stray operator, an unbalanced parenthesis,
 *       or a malformed numeric literal;
 *   <li>{@code empty-expression} — a {@code null} or blank/whitespace-only expression;
 *   <li>{@code division-by-zero} — dividing by zero;
 *   <li>{@code overflow} — the result exceeds the calculator's magnitude bound.
 * </ul>
 *
 * It is a plain {@link RuntimeException}: it carries no message-parsing contract, only the stable
 * {@link #reason()} code, and reads neither time nor randomness (invariant 10).
 */
public class CalculationException extends RuntimeException {

  private final String reason;

  /**
   * @param reason the stable, short failure code ({@code invalid-token}, {@code empty-expression},
   *     {@code division-by-zero}, {@code overflow}); never {@code null}
   */
  public CalculationException(String reason) {
    super("calculation failed (" + reason + ")");
    this.reason = reason;
  }

  /**
   * @return the stable, short failure code ({@code invalid-token}, {@code empty-expression}, {@code
   *     division-by-zero}, {@code overflow})
   */
  public String reason() {
    return reason;
  }
}
