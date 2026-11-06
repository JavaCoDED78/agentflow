package com.example.agentflow.toolexecution.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pure, dependency-free validator that checks a <em>parsed</em> JSON value — an {@code Object}
 * tree held as {@link Map}/{@link List}/{@link String}/{@link Boolean}/numeric types (exactly the
 * shapes the {@link JsonSchema} root holds) — against a parsed JSON Schema (draft 2020-12) root.
 *
 * <p>It exists to close the S08-03 acceptance criterion: <b>an invalid payload is blocked before
 * any tool side effect</b>. The executor resolves the tool's {@link ToolDescriptor#inputSchema()}
 * and validates the Planner's arguments against it <em>before</em> invoking the tool, so a value
 * that does not match the contract never reaches the executor (and therefore never triggers a side
 * effect). It closes <b>S08-04</b> the other way: it validates the tool's <em>output</em> against
 * {@link ToolDescriptor#outputSchema()} <em>after</em> the tool runs and <em>before</em> the step
 * is marked SUCCEEDED, so an invalid result is never accepted, forwarded to the next step, or
 * checkpointed — a schema mismatch throws {@link SchemaValidationException}, distinct from a
 * transient execution error the tool itself may raise before producing a result.
 *
 * <p>It supports the subset of JSON Schema a tool call needs — and nothing a full schema engine
 * would add only for novelty:
 *
 * <ul>
 *   <li><b>Type check.</b> {@code type} (a single name or a list of names) for the JSON value
 *       types: {@code object}, {@code string}, {@code integer}, {@code number}, {@code boolean},
 *       {@code array} and {@code null}. An {@code integer} schema accepts an integral numeric value
 *       — including an integral {@code double} such as {@code 2.0} — but not a fractional double
 *       such as {@code 2.5} or the string {@code "5"}.
 *   <li><b>Required properties.</b> An {@code object} schema's {@code required} lists property
 *       names that must be present.
 *   <li><b>Property typing.</b> An {@code object} schema's {@code properties} maps a property name
 *       to a subschema; a present property is validated against it.
 *   <li><b>Strict objects.</b> {@code additionalProperties: false} rejects any property that is not
 *       declared in {@code properties} (the "strict contract" that forbids unknown properties); the
 *       default — {@code additionalProperties} absent or {@code true} — allows them.
 *   <li><b>Array elements.</b> {@code items} subschema, so an array's elements are typed too.
 * </ul>
 *
 * <p>It also enforces two protective limits on the <em>payload</em>, so a hostile or malformed
 * value is rejected before it is processed: a maximum number of nodes (containers and scalars) and
 * a maximum nesting depth. Both are constructor parameters with conservative defaults so a
 * production caller (later, from configuration, S08-10) can tune them; like {@link JsonSchema} and
 * {@link RetryPolicy} it is a pure value that carries no Spring or I/O dependency and is fully
 * testable (invariant 10 — it reads neither time nor randomness).
 *
 * <p>The walk is total over the value: every node is checked, and the first violation throws a
 * {@link SchemaValidationException} carrying a stable {@link SchemaValidationException#reason()}
 * code. It carries no JSON library dependency: it walks the already-parsed {@code Map}/{@code List}
 * tree, exactly as {@link JsonSchema} holds its schema as a parsed tree.
 */
public final class SchemaValidator {

  /**
   * Default protective limits for a tool-call payload, used when a validator is built without
   * specifying them (see {@link #of(JsonSchema)}). Conservative enough to reject obviously hostile
   * payloads; a production caller tunes these from configuration.
   */
  public static final int DEFAULT_MAX_NODES = 1_000;

  public static final int DEFAULT_MAX_DEPTH = 20;

  private final JsonSchema schema;
  private final int maxNodes;
  private final int maxDepth;

  private SchemaValidator(JsonSchema schema, int maxNodes, int maxDepth) {
    this.schema = schema;
    this.maxNodes = maxNodes;
    this.maxDepth = maxDepth;
  }

  /**
   * @param schema the parsed input schema to validate against (never {@code null}); the schema's
   *     root is already validated by the {@link JsonSchema} constructor
   * @return a validator bound to {@code schema} with the {@link #DEFAULT_MAX_NODES}/{@link
   *     #DEFAULT_MAX_DEPTH} payload limits
   */
  public static SchemaValidator of(JsonSchema schema) {
    return new SchemaValidator(schema, DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH);
  }

  /**
   * @param schema the parsed input schema to validate against (never {@code null})
   * @param maxNodes the maximum number of nodes (containers and scalars) the value may contain; a
   *     non-negative bound on the payload size
   * @param maxDepth the maximum nesting depth of the value; a non-negative bound on how deeply
   *     nested the payload may be
   * @return a validator bound to {@code schema} with the given payload limits
   * @throws IllegalArgumentException if {@code schema} is {@code null}, or {@code maxNodes}/{@code
   *     maxDepth} is negative
   */
  public static SchemaValidator of(JsonSchema schema, int maxNodes, int maxDepth) {
    if (schema == null) {
      throw new IllegalArgumentException("schema must not be null");
    }
    if (maxNodes < 0 || maxDepth < 0) {
      throw new IllegalArgumentException(
          "maxNodes and maxDepth must be non-negative but were " + maxNodes + "/" + maxDepth);
    }
    return new SchemaValidator(schema, maxNodes, maxDepth);
  }

  /**
   * Validates a parsed JSON value against the bound schema and the payload limits, throwing a
   * {@link SchemaValidationException} on the first violation. It is used for both roles the engine
   * serves: validating the tool's <em>arguments</em> ({@code S08-03}, blocking an invalid payload
   * <em>before</em> the tool is invoked and any side effect) and validating the tool's
   * <em>result</em> ({@code S08-04}, blocking a non-conforming output <em>before</em> the step is
   * marked succeeded).
   *
   * @param argument the parsed value to check — the tool-call arguments (S08-03) or the tool's
   *     result (S08-04) — a {@code Map}/{@code List}/scalar tree
   * @throws SchemaValidationException if the value is too large, too deeply nested, or does not
   *     conform to the schema
   */
  public void validate(Object argument) {
    int nodes = countNodes(argument);
    if (nodes > maxNodes) {
      throw new SchemaValidationException("payload-too-large");
    }
    int depth = depthOf(argument);
    if (depth > maxDepth) {
      throw new SchemaValidationException("payload-too-deep");
    }
    validateNode(schema.root(), argument);
  }

  /**
   * Recursively walks the parsed value tree, counting every container (map entry / list element)
   * and scalar as one node, so the returned total bounds the size of the parsed payload.
   *
   * @param value the value (a {@code Map}/{@code List}/scalar)
   * @return the number of nodes in the value; {@code 1} for a scalar, {@code 1} plus the children
   *     for a container
   */
  private static int countNodes(Object value) {
    if (value instanceof Map<?, ?> map) {
      int nodes = 1;
      for (Object child : map.values()) {
        nodes += countNodes(child);
      }
      return nodes;
    }
    if (value instanceof List<?> list) {
      int nodes = 1;
      for (Object child : list) {
        nodes += countNodes(child);
      }
      return nodes;
    }
    return 1;
  }

  /**
   * Recursively computes the maximum nesting depth of the parsed value: a scalar is depth {@code
   * 1}, a container is {@code 1} plus the deepest child's depth.
   *
   * @param value the value (a {@code Map}/{@code List}/scalar)
   * @return the nesting depth of the value
   */
  private static int depthOf(Object value) {
    if (value instanceof Map<?, ?> map) {
      int max = 0;
      for (Object child : map.values()) {
        max = Math.max(max, depthOf(child));
      }
      return 1 + max;
    }
    if (value instanceof List<?> list) {
      int max = 0;
      for (Object child : list) {
        max = Math.max(max, depthOf(child));
      }
      return 1 + max;
    }
    return 1;
  }

  /**
   * Validates a single value against a single subschema. The schema node is an already-parsed JSON
   * Schema (a {@code Map}); the value is the corresponding parsed JSON value.
   *
   * @param schemaNode the parsed subschema
   * @param value the value to check
   */
  private static void validateNode(Object schemaNode, Object value) {
    @SuppressWarnings("unchecked")
    Map<String, Object> schema = (Map<String, Object>) schemaNode;

    List<String> types = typeNameList(schema.get("type"));
    if (types != null && !anyMatch(types, value)) {
      throw new SchemaValidationException("type-mismatch");
    }

    boolean isObject =
        types != null && types.contains("object")
            || schema.containsKey("properties")
            || schema.containsKey("required")
            || Boolean.FALSE.equals(schema.get("additionalProperties"));
    if (isObject) {
      if (!(value instanceof Map<?, ?> map)) {
        throw new SchemaValidationException("type-mismatch");
      }
      validateObject(map, schema);
      return;
    }

    boolean isArray = (types != null && types.contains("array")) || schema.containsKey("items");
    if (isArray) {
      if (!(value instanceof List<?> list)) {
        throw new SchemaValidationException("type-mismatch");
      }
      validateArray(list, schema);
      return;
    }
  }

  /**
   * Validates an object value against its subschema: {@code required} names are present, each
   * declared {@code properties} entry is present and typed, and (when {@code additionalProperties:
   * false}) no undeclared property is present.
   *
   * @param value the parsed object value
   * @param schema the parsed object subschema
   */
  private static void validateObject(Map<?, ?> value, Map<String, Object> schema) {
    Object required = schema.get("required");
    if (required instanceof List<?> requiredList) {
      for (Object name : requiredList) {
        if (!(name instanceof String propertyName)) {
          // A malformed schema (a non-string required entry) is a programming error, not an
          // invalid payload; surface it clearly rather than class-casting blindly.
          throw new SchemaValidationException("invalid-schema");
        }
        if (!value.containsKey(propertyName)) {
          throw new SchemaValidationException("missing-required");
        }
      }
    }

    Map<String, Object> properties = asStrMap(schema.get("properties"));
    if (properties != null) {
      for (Map.Entry<?, ?> entry : value.entrySet()) {
        if (entry.getKey() instanceof String name && properties.containsKey(name)) {
          validateNode(properties.get(name), entry.getValue());
        }
      }
    }

    if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
      for (Object key : value.keySet()) {
        if (key instanceof String name && (properties == null || !properties.containsKey(name))) {
          throw new SchemaValidationException("unknown-property");
        }
      }
    }
  }

  /**
   * Validates an array value: every element is checked against the {@code items} subschema when one
   * is present.
   *
   * @param value the parsed array value
   * @param schema the parsed array subschema
   */
  private static void validateArray(List<?> value, Map<String, Object> schema) {
    Object items = schema.get("items");
    if (items instanceof Map<?, ?> itemsSchema) {
      for (Object element : value) {
        validateNode(itemsSchema, element);
      }
    }
  }

  /**
   * @param typeNode the parsed {@code type} schema keyword (a single name, a list of names, or
   *     absent)
   * @return the list of type names, or {@code null} when the keyword is absent (no type check)
   */
  private static List<String> typeNameList(Object typeNode) {
    if (typeNode == null) {
      return null;
    }
    if (typeNode instanceof String single) {
      return List.of(single);
    }
    if (typeNode instanceof List<?> list) {
      List<String> names = new ArrayList<>(list.size());
      for (Object type : list) {
        if (type instanceof String name) {
          names.add(name);
        }
      }
      return names;
    }
    return null;
  }

  /**
   * @param types the expected type names
   * @param value the parsed value
   * @return {@code true} when the value matches any of the expected types
   */
  private static boolean anyMatch(List<String> types, Object value) {
    for (String type : types) {
      if (jsonTypeMatches(type, value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param type the JSON type name from the schema
   * @param value the parsed value
   * @return {@code true} when {@code value} is a JSON value of the expected {@code type}
   */
  private static boolean jsonTypeMatches(String type, Object value) {
    return switch (type) {
      case "object" -> value instanceof Map<?, ?>;
      case "array" -> value instanceof List<?>;
      case "string" -> value instanceof String;
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      case "integer" -> isInteger(value);
      case "number" -> value instanceof Number;
      default -> true; // An unknown schema type keyword never blocks a value (a future extension).
    };
  }

  /**
   * @param value the parsed value
   * @return {@code true} when {@code value} represents an integer: an integral numeric type, or a
   *     floating-point value with no fractional part
   */
  private static boolean isInteger(Object value) {
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d);
    }
    return value instanceof Number;
  }

  private static Map<String, Object> asStrMap(Object node) {
    if (node instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    }
    return null;
  }
}
