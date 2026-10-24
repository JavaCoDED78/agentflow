package com.example.agentflow.openapi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * OpenAPI compatibility checker for the contract gate (S03-16).
 *
 * <p>Compares a baseline (checked-in) OpenAPI description with a freshly generated one and
 * classifies every change as <em>breaking</em> or <em>compatible</em>. The gate fails the build
 * only on breaking changes; compatible additions (a new endpoint, an optional field) are reported
 * so the developer can refresh the baseline deliberately (see {@code contracts/openapi/README.md}).
 *
 * <p>Breaking changes this checker recognises:
 *
 * <ul>
 *   <li>a whole path (or an HTTP method on a path) removed;
 *   <li>a <em>required</em> object property removed;
 *   <li>a property's <code>type</code> changed;
 *   <li>an <code>enum</code> value removed.
 * </ul>
 *
 * <p>Everyting else (added path/method, added property, added response) is compatible. The S03-16
 * acceptance criterion — "unintentional removal of a required field breaks CI" — is the
 * required-property rule. The checker walks the raw JSON tree (Jackson, {@code tools.jackson}) so
 * it has no dependency on swagger's runtime and is deterministic for a given pair of specs.
 */
public final class OpenApiCompatibility {

  /** How deep inline nested object schemas are compared before giving up. */
  private static final int SCHEMA_DEPTH_LIMIT = 8;

  /** The result of a single comparison. */
  public static final class CompatibilityReport {
    private final List<String> breaking = new ArrayList<>();
    private final List<String> compatible = new ArrayList<>();

    void addBreaking(String message) {
      breaking.add(message);
    }

    void addCompatible(String message) {
      compatible.add(message);
    }

    /** The breaking changes, in the order they were discovered (never null). */
    public List<String> breakingChanges() {
      return List.copyOf(breaking);
    }

    /** The compatible changes, in the order they were discovered (never null). */
    public List<String> compatibleChanges() {
      return List.copyOf(compatible);
    }

    /** True when at least one breaking change was found. */
    public boolean isBreaking() {
      return !breaking.isEmpty();
    }

    /**
     * A human-readable report: breaking changes first, then compatible ones. Empty when nothing
     * changed. Used by the CLI/generator and by the test output.
     */
    public String summary() {
      if (breaking.isEmpty() && compatible.isEmpty()) {
        return "No contract changes.\n";
      }
      StringBuilder sb = new StringBuilder();
      sb.append("Breaking changes: ").append(breaking.size()).append('\n');
      for (String c : breaking) {
        sb.append("  - ").append(c).append('\n');
      }
      sb.append("Compatible changes: ").append(compatible.size()).append('\n');
      for (String c : compatible) {
        sb.append("  - ").append(c).append('\n');
      }
      return sb.toString();
    }
  }

  /**
   * Compare the baseline (older, committed) description against the current (generated) one. Only
   * changes that make the current contract strictly less capable of satisfying the baseline are
   * breaking.
   *
   * @param baseline the checked-in contract
   * @param current the freshly generated contract
   * @return a report of breaking and compatible changes (never null)
   */
  public CompatibilityReport compare(JsonNode baseline, JsonNode current) {
    CompatibilityReport report = new CompatibilityReport();
    diffPaths(baseline, current, report);
    diffSchemas(baseline, current, report, 0);
    return report;
  }

  private static void diffPaths(JsonNode baseline, JsonNode current, CompatibilityReport report) {
    JsonNode baselinePaths = baseline.get("paths");
    JsonNode currentPaths = current.get("paths");
    if (!(baselinePaths instanceof ObjectNode) || currentPaths == null) {
      return;
    }
    for (String path : keys(baselinePaths)) {
      JsonNode baselinePath = baselinePaths.get(path);
      if (!currentPaths.has(path)) {
        report.addBreaking("removed path " + path);
        continue;
      }
      diffMethods(path, baselinePath, currentPaths.get(path), report);
    }
    // Paths that only exist in the current spec are additive; report them so the developer can
    // refresh the baseline deliberately.
    for (String path : keys(currentPaths)) {
      if (!baselinePaths.has(path)) {
        report.addCompatible("added path " + path);
      }
    }
  }

  private static final String[] HTTP_METHODS = {
    "get", "put", "post", "delete", "options", "head", "patch", "trace"
  };

  private static void diffMethods(
      String path, JsonNode baselinePath, JsonNode currentPath, CompatibilityReport report) {
    for (String method : HTTP_METHODS) {
      if (baselinePath.hasNonNull(method) && !currentPath.hasNonNull(method)) {
        report.addBreaking("removed " + method.toUpperCase() + " " + path);
      }
    }
  }

  private static void diffSchemas(
      JsonNode baseline, JsonNode current, CompatibilityReport report, int depth) {
    if (depth >= SCHEMA_DEPTH_LIMIT) {
      return;
    }
    JsonNode baselineSchemas = componentsSchemas(baseline);
    JsonNode currentSchemas = componentsSchemas(current);
    if (!(baselineSchemas instanceof ObjectNode)) {
      return;
    }
    if (currentSchemas == null) {
      // Every declared schema vanished: each baseline schema's required surface is gone.
      for (String name : keys(baselineSchemas)) {
        report.addBreaking("removed schema {" + name + "}");
      }
      return;
    }
    for (String name : keys(baselineSchemas)) {
      JsonNode baselineSchema = baselineSchemas.get(name);
      JsonNode currentSchema = currentSchemas.has(name) ? currentSchemas.get(name) : null;
      if (currentSchema == null) {
        report.addBreaking("removed schema {" + name + "}");
      } else {
        diffObjectSchema(baselineSchema, currentSchema, name, report, depth);
      }
    }
  }

  private static JsonNode componentsSchemas(JsonNode spec) {
    JsonNode components = spec.get("components");
    return components != null ? components.get("schemas") : null;
  }

  private static void diffObjectSchema(
      JsonNode baseline,
      JsonNode current,
      String schemaName,
      CompatibilityReport report,
      int depth) {
    JsonNode baselineProperties = baseline.get("properties");
    if (!(baselineProperties instanceof ObjectNode)) {
      return;
    }
    JsonNode currentProperties = current.get("properties");
    boolean currentHasProperties = currentProperties instanceof ObjectNode;

    for (String property : keys(baselineProperties)) {
      if (!currentHasProperties || !currentProperties.has(property)) {
        if (isRequired(baseline, property)) {
          report.addBreaking("removed required property " + schemaName + "." + property);
        } else {
          report.addCompatible("removed optional property " + schemaName + "." + property);
        }
        continue;
      }
      JsonNode baselineProperty = baselineProperties.get(property);
      JsonNode currentProperty = currentProperties.get(property);
      diffPropertyType(schemaName + "." + property, baselineProperty, currentProperty, report);
      diffEnum(schemaName + "." + property, baselineProperty, currentProperty, report);
      if ("object".equals(typeOf(baselineProperty))) {
        diffObjectSchema(
            baselineProperty, currentProperty, schemaName + "." + property, report, depth + 1);
      }
    }

    // Properties that only exist in the current spec are additive, hence compatible.
    if (currentHasProperties) {
      for (String property : keys(currentProperties)) {
        if (!baselineProperties.has(property)) {
          report.addCompatible("added property " + schemaName + "." + property);
        }
      }
    }
  }

  private static void diffPropertyType(
      String path, JsonNode baseline, JsonNode current, CompatibilityReport report) {
    String baselineType = typeOf(baseline);
    String currentType = typeOf(current);
    if (!Objects.equals(baselineType, currentType)) {
      report.addBreaking(
          "changed type of " + path + " from " + baselineType + " to " + currentType);
    }
  }

  private static void diffEnum(
      String path, JsonNode baseline, JsonNode current, CompatibilityReport report) {
    JsonNode baselineEnum = baseline.get("enum");
    if (baselineEnum == null || !baselineEnum.isArray()) {
      return;
    }
    JsonNode currentEnum = current.get("enum");
    if (currentEnum == null) {
      report.addBreaking("removed enum from " + path);
      return;
    }
    for (JsonNode value : baselineEnum) {
      boolean stillPresent = false;
      for (JsonNode candidate : currentEnum) {
        if (candidate.asString().equals(value.asString())) {
          stillPresent = true;
          break;
        }
      }
      if (!stillPresent) {
        report.addBreaking("removed enum value \"" + value.asString() + "\" from " + path);
      }
    }
  }

  private static boolean isRequired(JsonNode schema, String property) {
    JsonNode required = schema.get("required");
    if (required == null || !required.isArray()) {
      return false;
    }
    for (JsonNode value : required) {
      if (value.asString().equals(property)) {
        return true;
      }
    }
    return false;
  }

  private static String typeOf(JsonNode node) {
    if (node == null) {
      return "undefined";
    }
    if (node.has("type")) {
      return node.get("type").asString();
    }
    return node.has("$ref") ? "$ref" : "undefined";
  }

  /** The keys of a JSON object node, or an empty collection when the node is not an object. */
  private static Collection<String> keys(JsonNode node) {
    return (node instanceof ObjectNode) ? ((ObjectNode) node).propertyNames() : List.of();
  }
}
