package com.example.agentflow.toolexecution.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A machine-readable JSON Schema document (draft 2020-12) held as an immutable, structured value.
 *
 * <p>Unlike {@link ToolDescriptor#description()} — human-readable prose the Planner reads to decide
 * whether to call a tool — the schema is <em>machine-readable</em>: it is a structured JSON Schema
 * document that a tool validates its arguments or result against at call time (S08-03, S08-04). It
 * is held here as the parsed schema tree (an unmodifiable {@code Map<String, Object>} root) so this
 * domain value carries no JSON library dependency: the {@code application}/{@code persistence}
 * layers convert between the JSON Schema text (the {@code tool_definitions.input_schema}/{@code
 * output_schema} columns, and the JSON Schema strings the Planner sends) and this value.
 *
 * <p>It is an immutable value object with value equality: two schemas are equal when their parsed
 * trees are equal, and a schema built from the same document is equal to another. The tree is
 * copied defensively at construction, so a later mutation of the caller's map does not change an
 * already built {@link JsonSchema}.
 *
 * @param root the parsed JSON Schema document; an unmodifiable object tree, never {@code null}
 */
public record JsonSchema(Map<String, Object> root) {

  /**
   * Canonical-constructor validation. A {@code null} root is a configuration error (a tool without
   * a machine-readable schema cannot be validated), so the constructor rejects it. The root is
   * copied defensively and made unmodifiable so the value stays immutable.
   */
  public JsonSchema {
    if (root == null) {
      throw new IllegalArgumentException("root schema tree must not be null");
    }
    Map<String, Object> copy = new LinkedHashMap<>(root); // defensive copy (allows null values)
    root = Collections.unmodifiableMap(copy);
  }

  /**
   * @param root the parsed JSON Schema document (an object tree; may contain nested maps/lists)
   * @return a validated, immutable {@link JsonSchema}
   * @throws IllegalArgumentException if {@code root} is {@code null}
   */
  public static JsonSchema of(Map<String, Object> root) {
    return new JsonSchema(root);
  }
}
