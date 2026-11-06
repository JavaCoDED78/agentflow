package com.example.agentflow.toolexecution.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * A safe, dependency-free arithmetic expression evaluator (S08-05).
 *
 * <p>It closes the S08-05 acceptance criterion: {@link #evaluate(String)} evaluates a
 * <em>typed</em> arithmetic expression (operands are {@link BigDecimal}s, so numbers are exact and
 * typed) built from {@code + - * /}, parentheses and decimal/scientific literals, using a
 * <b>self-contained recursive-descent parser</b>. It implements <em>nothing</em> that could escape
 * the expression: there is no {@code eval}, no shell, no reflection, no class loading and no access
 * to any language feature — an expression is only numbers and the four binary operators, so an
 * untrusted Planner cannot turn a calculator argument into code to run. This is the concrete
 * manifestation of the invariant that an external planner is an untrusted planner ({@code
 * ADR-006}): the calculator is a closed numeric dialect, never a general interpreter.
 *
 * <p>The grammar is the standard operator-precedence form, with {@code -} and unary sign:
 *
 * <pre>{@code
 * expression := term   (('+' | '-') term)*
 * term       := factor (('*' | '/') factor)*
 * factor     := ('+' | '-') factor | primary
 * primary    := number | '(' expression ')'
 * }</pre>
 *
 * <p>Operator precedence follows the grammar: {@code *}/{@code /} bind tighter than {@code
 * +}/{@code -}, and {@code ( )} groups, so {@code "2 + 3 * 4"} is {@code 14} and {@code "(1 + 2) *
 * 4"} is {@code 12}. Addition, subtraction and multiplication are exact {@link BigDecimal}
 * operations; division is bounded to {@value #DIVIDE_SCALE} fractional digits (half-up) so a
 * non-terminating result such as {@code 1 / 3} is deterministic, and dividing by zero throws {@link
 * CalculationException#reason() "division-by-zero"}. Every intermediate result is checked against a
 * magnitude bound ({@value #OVERFLOW_BOUND}); a result larger than that bound throws "overflow".
 *
 * <p>On the first problem it throws a {@link CalculationException} carrying a stable {@link
 * CalculationException#reason()} code:
 *
 * <ul>
 *   <li>{@code invalid-token} — an unknown character (any letter or symbol), a stray or repeated
 *       operator, an unbalanced parenthesis, an {@code E} exponent with no digits, a malformed
 *       numeric literal, or two operands with no operator between them;
 *   <li>{@code empty-expression} — a {@code null} or blank/whitespace-only expression;
 *   <li>{@code division-by-zero} — dividing by zero (directly or through {@code "(2 - 2)"});
 *   <li>{@code overflow} — a result exceeding the magnitude bound.
 * </ul>
 *
 * <p>Like {@link JsonSchema}, {@link RetryPolicy} and {@link SchemaValidator} it is a pure,
 * dependency-free value that reads neither time nor randomness and carries no Spring or I/O
 * dependency, so it is fully testable in isolation (invariant 10). It owns no state: {@link
 * #evaluate(String)} is a static entry point, the calculator-layer mirror of {@code
 * calculator.evaluate} in the built-in tool descriptor.
 */
public final class Calculator {

  /**
   * The magnitude bound an evaluated result must not exceed: {@code 1E1000}. Generous enough to
   * absorb any realistic calculator result (far beyond {@code double} range), yet a hard ceiling
   * that turns unbounded numeric growth (for example {@code "1E600 * 1E600"}) into a deterministic
   * {@code overflow} rather than unbounded work.
   */
  private static final BigDecimal OVERFLOW_BOUND = new BigDecimal("1E1000");

  /**
   * The number of fractional digits a non-terminating division is rounded to (half-up), so a result
   * such as {@code 1 / 3} is deterministic. Chosen to match the {@code DECIMAL64} significant-digit
   * precision, so the result is well within {@link #OVERFLOW_BOUND}.
   */
  private static final int DIVIDE_SCALE = 34;

  private Calculator() {}

  /**
   * Evaluates a safe arithmetic expression such as {@code "2 + 3 * 4"} or {@code "(1 + 2) * 5"}.
   *
   * <p>The operands are typed {@link BigDecimal}s; the expression is only numbers plus the
   * operators {@code + - * /} and parentheses, so the evaluator is a closed numeric dialect and can
   * never evaluate anything that escapes it (no {@code eval}, shell, reflection or arbitrary code —
   * invariant 7). The first problem throws a {@link CalculationException}; a successful evaluation
   * returns the exact (or division-bounded) numeric result.
   *
   * @param expression the arithmetic expression to evaluate; a {@code null} value is treated as an
   *     invalid token (there is nothing to evaluate)
   * @return the evaluated result as an exact {@link BigDecimal} (division is bounded to {@value
   *     #DIVIDE_SCALE} fractional digits)
   * @throws CalculationException with reason {@code invalid-token} for an unknown character, a
   *     stray operator, an unbalanced parenthesis, a malformed literal, or two operands with no
   *     operator; reason {@code empty-expression} for a {@code null} or blank/whitespace-only
   *     expression; reason {@code division-by-zero} for dividing by zero; reason {@code overflow}
   *     when the result exceeds {@value #OVERFLOW_BOUND}
   */
  public static BigDecimal evaluate(String expression) {
    if (expression == null) {
      throw new CalculationException("invalid-token");
    }
    if (expression.trim().isEmpty()) {
      throw new CalculationException("empty-expression");
    }
    List<Token> tokens = tokenize(expression);
    Parser parser = new Parser(tokens);
    BigDecimal result = parser.parseExpression();
    parser.ensureEnd();
    return result;
  }

  // ---- arithmetic (each intermediate result bound-checked for overflow) ----

  private static BigDecimal add(BigDecimal a, BigDecimal b) {
    return bound(a.add(b));
  }

  private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
    return bound(a.subtract(b));
  }

  private static BigDecimal multiply(BigDecimal a, BigDecimal b) {
    return bound(a.multiply(b));
  }

  private static BigDecimal divide(BigDecimal a, BigDecimal b) {
    if (b.signum() == 0) {
      throw new CalculationException("division-by-zero");
    }
    BigDecimal result = a.divide(b, DIVIDE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    return bound(result);
  }

  /**
   * Bounds a result against {@link #OVERFLOW_BOUND}; a magnitude larger than the bound is an {@code
   * overflow}.
   *
   * @param value the intermediate or final result
   * @return {@code value} unchanged, when its magnitude is within the bound
   * @throws CalculationException with reason {@code overflow} when {@code value.abs()} exceeds
   *     {@value #OVERFLOW_BOUND}
   */
  private static BigDecimal bound(BigDecimal value) {
    if (value.abs().compareTo(OVERFLOW_BOUND) > 0) {
      throw new CalculationException("overflow");
    }
    return value;
  }

  // ---- tokenizer ----

  /**
   * A parsed numeric literal: the {@link Token} to emit, and the index immediately after the
   * literal in the source, so the caller can advance the scan position past it.
   */
  private record NumberLiteral(Token token, int endIndex) {}

  /**
   * Splits an expression into {@link Token}s: number literals (which carry a parsed {@link
   * BigDecimal}) and operator characters ({@code + - * / ( )}). Whitespace is skipped; an unknown
   * character, an {@code E} exponent with no digits, or a malformed numeric literal throws {@code
   * invalid-token}.
   *
   * @param expression the expression to tokenize; never {@code null} or blank (checked by {@link
   *     #evaluate(String)})
   * @return the token list, in source order
   * @throws CalculationException with reason {@code invalid-token} on an unknown character, an
   *     exponent without digits, or a malformed numeric literal
   */
  private static List<Token> tokenize(String expression) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int n = expression.length();
    while (i < n) {
      char c = expression.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (c == '(' || c == ')' || c == '+' || c == '-' || c == '*' || c == '/') {
        tokens.add(new Token(c, null));
        i++;
        continue;
      }
      if (Character.isDigit(c) || c == '.') {
        NumberLiteral literal = parseNumber(expression, i);
        tokens.add(literal.token());
        i = literal.endIndex();
        continue;
      }
      // Any other character is outside the closed numeric dialect.
      throw new CalculationException("invalid-token");
    }
    return tokens;
  }

  /**
   * Parses a single numeric literal starting at {@code start} (a digit or {@code '.'}), advancing
   * past it, and returns the {@link Token} holding the parsed {@link BigDecimal}. The literal
   * supports an optional decimal point and an optional {@code E}/{@code e} exponent with an
   * optional sign; a malformed literal throws {@code invalid-token}.
   *
   * @param expression the expression text
   * @param start the index of the first digit or dot of the literal
   * @return the parsed {@link NumberLiteral} (token + end index)
   * @throws CalculationException with reason {@code invalid-token} when the literal is malformed
   */
  private static NumberLiteral parseNumber(String expression, int start) {
    int i = start;
    int n = expression.length();
    boolean seenDot = false;
    while (i < n && (Character.isDigit(expression.charAt(i)) || expression.charAt(i) == '.')) {
      char c = expression.charAt(i);
      if (c == '.') {
        if (seenDot) {
          break; // a second dot ends the literal here (it becomes an invalid token at the top
          // level)
        }
        seenDot = true;
      }
      i++;
    }
    // Optional exponent: E/e, an optional sign, then required digits.
    if (i < n && (expression.charAt(i) == 'E' || expression.charAt(i) == 'e')) {
      i++;
      if (i < n && (expression.charAt(i) == '+' || expression.charAt(i) == '-')) {
        i++;
      }
      int digitsStart = i;
      while (i < n && Character.isDigit(expression.charAt(i))) {
        i++;
      }
      if (i == digitsStart) {
        // E/e with no following digits: a malformed literal.
        throw new CalculationException("invalid-token");
      }
    }
    String numberText = expression.substring(start, i);
    BigDecimal value;
    try {
      value = new BigDecimal(numberText);
    } catch (NumberFormatException malformed) {
      throw new CalculationException("invalid-token");
    }
    return new NumberLiteral(new Token('N', value), i);
  }

  // ---- parser ----

  /**
   * A position-tracking recursive-descent parser over the token list, implementing the operator-
   * precedence grammar above. Each {@code parse*} method consumes the tokens of the sub-expression
   * it handles; {@link #ensureEnd()} fails the evaluation when tokens are left unconsumed (an
   * operator missing its right operand, or two operands with no operator between them).
   */
  private static final class Parser {
    private final List<Token> tokens;
    private int pos = 0;

    Parser(List<Token> tokens) {
      this.tokens = tokens;
    }

    /** Parses a full expression: a term followed by any {@code +}/{@code -} term chain. */
    BigDecimal parseExpression() {
      BigDecimal value = parseTerm();
      while (pos < tokens.size() && (peek().op() == '+' || peek().op() == '-')) {
        char op = next().op();
        BigDecimal right = parseTerm();
        value = op == '+' ? add(value, right) : subtract(value, right);
      }
      return value;
    }

    /** Parses a term: a factor followed by any {@code *}/{@code /} factor chain. */
    BigDecimal parseTerm() {
      BigDecimal value = parseFactor();
      while (pos < tokens.size() && (peek().op() == '*' || peek().op() == '/')) {
        char op = next().op();
        BigDecimal right = parseFactor();
        value = op == '*' ? multiply(value, right) : divide(value, right);
      }
      return value;
    }

    /** Parses a signed primary: an optional unary sign, then a primary. */
    BigDecimal parseFactor() {
      if (pos >= tokens.size()) {
        throw new CalculationException("invalid-token");
      }
      Token token = peek();
      if (token.op() == '+' || token.op() == '-') {
        next();
        BigDecimal operand = parseFactor();
        return token.op() == '+' ? operand : subtract(BigDecimal.ZERO, operand);
      }
      return parsePrimary();
    }

    /** Parses a primary: a number literal or a parenthesised expression. */
    BigDecimal parsePrimary() {
      Token token = next();
      if (token.op() == 'N') {
        return token.value();
      }
      if (token.op() == '(') {
        BigDecimal value = parseExpression();
        Token closing = next();
        if (closing.op() != ')') {
          throw new CalculationException("invalid-token");
        }
        return value;
      }
      throw new CalculationException("invalid-token");
    }

    /**
     * @throws CalculationException with reason {@code invalid-token} if any token remains after the
     *     top-level expression has been parsed
     */
    void ensureEnd() {
      if (pos != tokens.size()) {
        throw new CalculationException("invalid-token");
      }
    }

    private Token peek() {
      return tokens.get(pos);
    }

    private Token next() {
      if (pos >= tokens.size()) {
        throw new CalculationException("invalid-token");
      }
      return tokens.get(pos++);
    }
  }

  // ---- token ----

  /**
   * A single lexeme: either a number literal ({@code op() == 'N'}, carrying its {@link BigDecimal}
   * in {@link #value()}) or one of the operator characters ({@code + - * / ( )}). The operator
   * character doubles as the token kind; a number's kind is the sentinel {@code 'N'}.
   */
  private static final class Token {
    private final char op;
    private final BigDecimal value;

    Token(char op, BigDecimal value) {
      this.op = op;
      this.value = value;
    }

    /**
     * @return the operator character, or {@code 'N'} for a number literal
     */
    char op() {
      return op;
    }

    /**
     * @return the numeric value, for a number literal ({@code op() == 'N'})
     */
    BigDecimal value() {
      return value;
    }
  }
}
