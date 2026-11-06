package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link SchemaValidator} engine (S08-03).
 *
 * <p>They verify the S08-03 acceptance criterion — an invalid payload is blocked <em>before any
 * tool side effect</em> — by checking the subset of JSON Schema a tool call needs: {@code type}
 * matching for every JSON value type, {@code required} fields, {@code properties} typing, {@code
 * items} typing for arrays, and {@code additionalProperties: false} (the strict contract that
 * rejects unknown properties). They also cover the two protective payload limits (node count and
 * nesting depth), the injectable limits, the stable {@link SchemaValidationException#reason()}
 * code, and — with a recording executor — that a valid input reaches the tool while an invalid one
 * does not. DB-free (no database, Kafka, model or system clock).
 */
class SchemaValidatorTest {

  /**
   * A minimal stand-in for a tool executor, recording whether the call actually ran, so a test can
   * assert that an invalid input is blocked before the side effect.
   */
  private static final class RecordingTool {
    boolean executed = false;
    Object lastArgument;

    void run(SchemaValidator validator, Object argument) {
      validator.validate(argument); // throws on invalid input, blocking the call
      executed = true;
      lastArgument = argument;
    }
  }

  /**
   * Runs {@code validator.validate(argument)} and returns the thrown {@link
   * SchemaValidationException}, or fails the test if the value was accepted. Keeps the assertions
   * on the exception's {@code reason()}/{@code message} readable (an {@code extracting} method
   * reference does not type check against the thrown-assertion's {@code Throwable} actual).
   *
   * @param validator the validator to run
   * @param argument the value to validate
   * @return the thrown validation error
   */
  private static SchemaValidationException invalid(SchemaValidator validator, Object argument) {
    try {
      validator.validate(argument);
    } catch (SchemaValidationException error) {
      return error;
    }
    throw new AssertionError("expected a SchemaValidationException but validation passed");
  }

  // ---- schema builders -------------------------------------------------

  /**
   * Builds an {@code object} schema whose root carries the given {@code key}/{@code value} pairs
   * (for example {@code ("properties", ...)}, {@code ("required", ...)}, {@code
   * ("additionalProperties", false)}).
   */
  private static JsonSchema objectSchema(Object... pairs) {
    java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.put("type", "object");
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      root.put((String) pairs[i], pairs[i + 1]);
    }
    return JsonSchema.of(root);
  }

  /** Builds a {@code properties} map of {@code name → {"type": type}} from an alternating array. */
  private static Map<String, Object> props(String... kv) {
    java.util.LinkedHashMap<String, Object> properties = new java.util.LinkedHashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) {
      properties.put(kv[i], Map.of("type", kv[i + 1]));
    }
    return properties;
  }

  /**
   * Builds a single-property {@code object} schema: {@code
   * {"type":"object","properties":{name:{type}}}}.
   */
  private static JsonSchema singleProperty(String name, String type) {
    return objectSchema("properties", props(name, type));
  }

  // ---- type checking ---------------------------------------------------

  @Test
  void a_valid_object_with_required_fields_and_correct_types_passes() {
    SchemaValidator validator =
        SchemaValidator.of(
            objectSchema(
                "properties",
                props("name", "string", "age", "integer"),
                "required",
                List.of("name", "age")));

    assertThatCode(() -> validator.validate(Map.of("name", "Ada", "age", 36L)))
        .doesNotThrowAnyException();
  }

  @Test
  void a_missing_required_property_is_rejected() {
    SchemaValidator validator =
        SchemaValidator.of(
            objectSchema(
                "properties",
                props("name", "string", "age", "integer"),
                "required",
                List.of("name", "age")));

    assertThat(invalid(validator, Map.of("name", "Ada")).reason()).isEqualTo("missing-required");
  }

  @Test
  void a_wrongly_typed_property_is_rejected() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));

    assertThat(invalid(validator, Map.of("name", 42L)).reason()).isEqualTo("type-mismatch");
  }

  @Test
  void a_value_against_a_non_null_type_is_rejected_when_null() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));

    // A LinkedHashMap, since Map.of rejects null values; the key is present with a null value.
    java.util.LinkedHashMap<String, Object> withNull = new java.util.LinkedHashMap<>();
    withNull.put("name", null);

    assertThat(invalid(validator, withNull).reason()).isEqualTo("type-mismatch");
  }

  @Test
  void a_top_level_type_mismatch_is_rejected() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));

    // The schema root is an object, but the value is an array.
    assertThat(invalid(validator, List.of("a", "b")).reason()).isEqualTo("type-mismatch");
  }

  @Test
  void an_integer_schema_accepts_integral_values_but_not_a_fraction_or_a_string() {
    SchemaValidator validator = SchemaValidator.of(JsonSchema.of(Map.of("type", "integer")));

    assertThatCode(() -> validator.validate(42L)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(2.0)).doesNotThrowAnyException(); // integral double
    assertThat(invalid(validator, 2.5).reason()).isEqualTo("type-mismatch");
    assertThat(invalid(validator, "5").reason()).isEqualTo("type-mismatch");
  }

  @Test
  void a_number_schema_accepts_integer_and_double_but_not_a_string() {
    SchemaValidator validator = SchemaValidator.of(JsonSchema.of(Map.of("type", "number")));

    assertThatCode(() -> validator.validate(42L)).doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(2.5)).doesNotThrowAnyException();
    assertThat(invalid(validator, "x").reason()).isEqualTo("type-mismatch");
  }

  @Test
  void a_boolean_string_and_array_types_are_checked() {
    SchemaValidator booleanValidator = SchemaValidator.of(JsonSchema.of(Map.of("type", "boolean")));
    SchemaValidator stringValidator = SchemaValidator.of(JsonSchema.of(Map.of("type", "string")));
    SchemaValidator arrayValidator = SchemaValidator.of(JsonSchema.of(Map.of("type", "array")));

    assertThatCode(() -> booleanValidator.validate(true)).doesNotThrowAnyException();
    assertThat(invalid(stringValidator, 1).reason()).isEqualTo("type-mismatch");
    assertThat(invalid(arrayValidator, Map.of("a", 1)).reason()).isEqualTo("type-mismatch");
  }

  // ---- strict objects --------------------------------------------------

  @Test
  void an_unknown_property_is_rejected_in_a_strict_contract() {
    SchemaValidator validator =
        SchemaValidator.of(
            objectSchema(
                "properties", props("name", "string"), "additionalProperties", Boolean.FALSE));

    assertThat(invalid(validator, Map.of("name", "Ada", "extra", 1)).reason())
        .isEqualTo("unknown-property");
  }

  @Test
  void an_unknown_property_is_allowed_when_the_contract_is_not_strict() {
    SchemaValidator nonStrict =
        SchemaValidator.of(objectSchema("properties", props("name", "string")));
    SchemaValidator explicitAllow =
        SchemaValidator.of(
            objectSchema(
                "properties", props("name", "string"), "additionalProperties", Boolean.TRUE));

    assertThatCode(() -> nonStrict.validate(Map.of("name", "Ada", "extra", 1)))
        .doesNotThrowAnyException();
    assertThatCode(() -> explicitAllow.validate(Map.of("name", "Ada", "extra", 1)))
        .doesNotThrowAnyException();
  }

  @Test
  void a_strict_contract_still_accepts_all_declared_properties() {
    SchemaValidator validator =
        SchemaValidator.of(
            objectSchema(
                "properties",
                props("name", "string", "age", "integer"),
                "additionalProperties",
                Boolean.FALSE));

    assertThatCode(() -> validator.validate(Map.of("name", "Ada", "age", 36L)))
        .doesNotThrowAnyException();
  }

  // ---- arrays and nesting ----------------------------------------------

  @Test
  void array_elements_are_validated_against_the_items_schema() {
    SchemaValidator validator =
        SchemaValidator.of(
            JsonSchema.of(Map.of("type", "array", "items", Map.of("type", "string"))));

    assertThatCode(() -> validator.validate(List.of("a", "b"))).doesNotThrowAnyException();
    assertThat(invalid(validator, List.of("a", 2)).reason()).isEqualTo("type-mismatch");
  }

  @Test
  void a_nested_object_with_valid_children_passes() {
    JsonSchema address =
        JsonSchema.of(Map.of("type", "object", "properties", props("city", "string")));
    // The address subschema is attached to the declared property.
    SchemaValidator validator =
        SchemaValidator.of(objectSchema("properties", Map.of("address", address.root())));

    assertThatCode(() -> validator.validate(Map.of("address", Map.of("city", "London"))))
        .doesNotThrowAnyException();
  }

  @Test
  void a_nested_object_with_an_invalid_child_is_rejected() {
    JsonSchema address =
        JsonSchema.of(Map.of("type", "object", "properties", props("city", "string")));
    SchemaValidator validator =
        SchemaValidator.of(objectSchema("properties", Map.of("address", address.root())));

    // address.city must be a string, but it is an integer.
    assertThat(invalid(validator, Map.of("address", Map.of("city", 5))).reason())
        .isEqualTo("type-mismatch");
  }

  // ---- protective payload limits ---------------------------------------

  @Test
  void a_payload_exceeding_the_node_limit_is_rejected() {
    java.util.LinkedHashMap<String, Object> big = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 100; i++) {
      big.put("p" + i, "v");
    }
    SchemaValidator validator =
        SchemaValidator.of(objectSchema(), 50, SchemaValidator.DEFAULT_MAX_DEPTH);

    assertThat(invalid(validator, big).reason()).isEqualTo("payload-too-large");
  }

  @Test
  void a_deeply_nested_payload_is_rejected() {
    Object deep = "leaf";
    for (int i = 0; i < 10; i++) {
      deep = List.of(deep);
    }
    SchemaValidator validator =
        SchemaValidator.of(JsonSchema.of(Map.of("type", "array")), 1_000, 3);

    assertThat(invalid(validator, deep).reason()).isEqualTo("payload-too-deep");
  }

  @Test
  void a_payload_within_default_limits_and_valid_type_passes() {
    SchemaValidator validator =
        SchemaValidator.of(objectSchema("properties", props("name", "string")));
    assertThatCode(() -> validator.validate(Map.of("name", "Ada"))).doesNotThrowAnyException();
  }

  @Test
  void negative_limits_are_rejected_at_construction() {
    assertThatThrownBy(() -> SchemaValidator.of(objectSchema(), -1, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxNodes");
    assertThatThrownBy(() -> SchemaValidator.of(objectSchema(), 10, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxDepth");
  }

  // ---- before-side-effect ordering (the acceptance criterion) ----------

  @Test
  void an_invalid_input_is_blocked_before_the_tool_runs() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));
    RecordingTool tool = new RecordingTool();

    assertThatThrownBy(() -> tool.run(validator, Map.of("name", 123)))
        .isInstanceOf(SchemaValidationException.class);
    // The side effect (the tool call) never happened: validation is a before-call gate.
    assertThat(tool.executed).isFalse();
  }

  @Test
  void a_valid_input_reaches_the_tool() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));
    RecordingTool tool = new RecordingTool();

    tool.run(validator, Map.of("name", "Ada"));

    assertThat(tool.executed).isTrue();
    assertThat(tool.lastArgument).isEqualTo(Map.of("name", "Ada"));
  }

  // ---- the exception ---------------------------------------------------

  @Test
  void the_exception_exposes_a_stable_reason_code() {
    SchemaValidator validator = SchemaValidator.of(singleProperty("name", "string"));

    SchemaValidationException error = invalid(validator, Map.of("name", 1));
    assertThat(error.reason()).isEqualTo("type-mismatch");
    assertThat(error.getMessage()).contains("type-mismatch");
  }
}
