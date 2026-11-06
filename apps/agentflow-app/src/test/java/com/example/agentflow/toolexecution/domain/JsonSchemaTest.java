package com.example.agentflow.toolexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link JsonSchema} value object (S08-01).
 *
 * <p>They verify that a machine-readable schema is carried as an <em>immutable, structured</em>
 * value: it cannot be built from {@code null}, its tree is copied defensively (so a later mutation
 * of the caller's map does not change a built schema), two schemas built from equal trees are value
 * equal, and the tree round-trips its structure. DB-free.
 */
class JsonSchemaTest {

  private static Map<String, Object> objectSchema(String type, String... properties) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    for (int i = 0; i + 1 < properties.length; i += 2) {
      props.put(properties[i], Map.of("type", properties[i + 1]));
    }
    return Map.of(
        "$schema",
        "https://json-schema.org/draft/2020-12/schema",
        "type",
        "object",
        "properties",
        props);
  }

  @Test
  void a_schema_round_trips_its_parsed_structure() {
    JsonSchema schema = JsonSchema.of(objectSchema("object", "name", "string", "age", "integer"));
    assertThat(schema.root())
        .containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.root()).containsEntry("type", "object");
    @SuppressWarnings("unchecked")
    Map<String, Object> props = (Map<String, Object>) schema.root().get("properties");
    assertThat(props).containsEntry("name", Map.of("type", "string"));
    assertThat(props).containsEntry("age", Map.of("type", "integer"));
  }

  @Test
  void a_null_root_is_rejected() {
    assertThatThrownBy(() -> JsonSchema.of(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("root");
  }

  @Test
  void a_built_schema_is_unmodifiable_at_the_root() {
    JsonSchema schema = JsonSchema.of(objectSchema("object", "name", "string"));
    assertThatThrownBy(() -> schema.root().put("type", "array"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void a_mutation_of_the_callers_map_does_not_change_a_built_schema() {
    java.util.LinkedHashMap<String, Object> caller = new java.util.LinkedHashMap<>();
    caller.put("type", "object");
    JsonSchema schema = JsonSchema.of(caller);
    caller.put("type", "array"); // mutate the source after the value was built
    assertThat(schema.root()).containsEntry("type", "object"); // the value kept its copy
  }

  @Test
  void schemas_built_from_equal_trees_are_value_equal() {
    JsonSchema a = JsonSchema.of(objectSchema("object", "name", "string"));
    JsonSchema b = JsonSchema.of(objectSchema("object", "name", "string"));
    JsonSchema c = JsonSchema.of(objectSchema("object", "name", "integer"));
    assertThat(a).isEqualTo(b);
    assertThat(a).isNotEqualTo(c);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  void a_schema_carries_a_nested_array_value_intact() {
    Map<String, Object> root =
        Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "default", List.of("a", "b"));
    JsonSchema schema = JsonSchema.of(root);
    assertThat(schema.root()).containsEntry("type", "array");
    assertThat(schema.root().get("default")).isEqualTo(List.of("a", "b"));
  }
}
