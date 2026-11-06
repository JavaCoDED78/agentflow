package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Calculator} safe expression evaluator (S08-05).
 *
 * <p>They close the S08-05 acceptance criterion — {@code operations}, {@code division-by-zero},
 * {@code overflow} and {@code invalid tokens} are covered — by checking that {@link
 * Calculator#evaluate(String)} computes the ordinary arithmetic correctly (operator precedence,
 * parentheses, associativity, unary sign, decimals and division), that dividing by zero and an
 * out-of-bound result are rejected deterministically, and that anything outside the closed numeric
 * dialect (letters, stray operators, unbalanced parentheses, malformed literals, an empty or null
 * expression) is rejected with a stable {@link CalculationException#reason()} code. Because only
 * numbers and the four operators are accepted, the tests also prove the evaluator cannot run code
 * (no {@code eval}, shell, reflection or arbitrary execution): a letter is not a valid token.
 * DB-free (no database, Kafka, model or system clock); the calculator reads neither time nor
 * randomness (invariant 10).
 */
class CalculatorTest {

  /**
   * Runs {@code Calculator.evaluate(expression)} and returns the thrown {@link
   * CalculationException}, or fails the test if the expression was accepted. Keeps the assertions
   * on the exception's {@code reason()} readable (an {@code extracting} method reference does not
   * type check against the thrown-assertion's {@code Throwable} actual).
   *
   * @param expression the expression to evaluate
   * @return the thrown calculation error
   */
  private static CalculationException failing(String expression) {
    try {
      Calculator.evaluate(expression);
    } catch (CalculationException error) {
      return error;
    }
    throw new AssertionError(
        "expected a CalculationException but evaluate passed for: " + expression);
  }

  // ---- operations: precedence, associativity, grouping, sign, decimals ----

  @Test
  void multiplication_binds_tighter_than_addition() {
    // "2 + 3 * 4" is 14, not 20.
    assertThat(Calculator.evaluate("2 + 3 * 4")).isEqualTo(new BigDecimal("14"));
  }

  @Test
  void parentheses_override_precedence() {
    assertThat(Calculator.evaluate("(1 + 2) * 4")).isEqualTo(new BigDecimal("12"));
  }

  @Test
  void addition_is_left_associative() {
    // "10 - 2 - 3" is 5, not 11.
    assertThat(Calculator.evaluate("10 - 2 - 3")).isEqualTo(new BigDecimal("5"));
  }

  @Test
  void nested_parentheses_are_grouped() {
    assertThat(Calculator.evaluate("((1 + 2) * (3 + 4))")).isEqualTo(new BigDecimal("21"));
  }

  @Test
  void division_and_multiplication_are_evaluated_before_addition() {
    assertThat(Calculator.evaluate("2 * 3 + 1")).isEqualTo(new BigDecimal("7"));
  }

  @Test
  void decimal_operands_are_exact() {
    // 1.5 + 2.5 = 4.0 (BigDecimal keeps the fractional scale of the exact sum).
    assertThat(Calculator.evaluate("1.5 + 2.5")).isEqualTo(new BigDecimal("4.0"));
  }

  @Test
  void division_that_divides_evenly_is_exact() {
    assertThat(Calculator.evaluate("10 / 4")).isEqualTo(new BigDecimal("2.5"));
  }

  @Test
  void a_non_terminating_division_is_deterministic() {
    // 1 / 3 rounded to the calculator's bounded scale, half-up.
    BigDecimal expected = new BigDecimal("1").divide(new BigDecimal("3"), 34, RoundingMode.HALF_UP);
    assertThat(Calculator.evaluate("1 / 3")).isEqualTo(expected);
  }

  @Test
  void leading_unary_sign_is_applied() {
    assertThat(Calculator.evaluate("-5 + 3")).isEqualTo(new BigDecimal("-2"));
  }

  @Test
  void a_unary_sign_on_a_factor_is_applied() {
    // "2 * -3" is -6.
    assertThat(Calculator.evaluate("2 * -3")).isEqualTo(new BigDecimal("-6"));
  }

  @Test
  void two_unary_signs_cancel_out() {
    // "-(-5)" is 5.
    assertThat(Calculator.evaluate("-(-5)")).isEqualTo(new BigDecimal("5"));
  }

  @Test
  void surrounding_whitespace_is_ignored() {
    assertThat(Calculator.evaluate("   2 + 3   ")).isEqualTo(new BigDecimal("5"));
  }

  @Test
  void a_scientific_literal_is_parsed() {
    assertThat(Calculator.evaluate("1.5E2 + 1")).isEqualTo(new BigDecimal("151"));
  }

  // ---- division by zero ----

  @Test
  void dividing_by_zero_is_rejected() {
    assertThat(failing("1 / 0").reason()).isEqualTo("division-by-zero");
  }

  @Test
  void dividing_by_an_expression_that_is_zero_is_rejected() {
    assertThat(failing("5 / (2 - 2)").reason()).isEqualTo("division-by-zero");
  }

  // ---- overflow ----

  @Test
  void a_result_that_exceeds_the_bound_overflows() {
    // 1E600 * 1E600 = 1E1200, above the 1E1000 magnitude bound.
    assertThat(failing("1E600 * 1E600").reason()).isEqualTo("overflow");
  }

  @Test
  void a_large_but_bounded_result_is_returned() {
    // 1E400 * 1E400 = 1E800, below the 1E1000 magnitude bound.
    assertThat(Calculator.evaluate("1E400 * 1E400")).isEqualTo(new BigDecimal("1E800"));
  }

  // ---- invalid tokens ----

  @Test
  void a_letter_is_an_invalid_token() {
    assertThat(failing("2 + x").reason()).isEqualTo("invalid-token");
  }

  @Test
  void a_symbol_is_an_invalid_token() {
    assertThat(failing("2 @ 3").reason()).isEqualTo("invalid-token");
  }

  @Test
  void two_operands_without_an_operator_between_them_are_invalid() {
    assertThat(failing("2 3").reason()).isEqualTo("invalid-token");
  }

  @Test
  void a_trailing_operator_is_invalid() {
    assertThat(failing("2 +").reason()).isEqualTo("invalid-token");
  }

  @Test
  void a_repeated_operator_is_invalid() {
    assertThat(failing("2 ** 3").reason()).isEqualTo("invalid-token");
  }

  @Test
  void an_unbalanced_parenthesis_is_invalid() {
    assertThat(failing("(1 + 2").reason()).isEqualTo("invalid-token");
  }

  @Test
  void an_unterminated_exponent_is_invalid() {
    assertThat(failing("1E").reason()).isEqualTo("invalid-token");
  }

  @Test
  void a_malformed_numeric_literal_is_invalid() {
    assertThat(failing("1.2.3").reason()).isEqualTo("invalid-token");
  }

  // ---- empty / null ----

  @Test
  void an_empty_expression_is_rejected() {
    assertThat(failing("").reason()).isEqualTo("empty-expression");
  }

  @Test
  void a_whitespace_only_expression_is_rejected() {
    assertThat(failing("     ").reason()).isEqualTo("empty-expression");
  }

  @Test
  void a_null_expression_is_an_invalid_token() {
    assertThat(failing(null).reason()).isEqualTo("invalid-token");
  }

  // ---- the exception ---------------------------------------------------

  @Test
  void the_exception_exposes_a_stable_reason_code() {
    CalculationException error = failing("1 / 0");
    assertThat(error.reason()).isEqualTo("division-by-zero");
    assertThat(error.getMessage()).contains("division-by-zero");
  }
}
