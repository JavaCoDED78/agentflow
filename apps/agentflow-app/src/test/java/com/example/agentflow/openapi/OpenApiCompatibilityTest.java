package com.example.agentflow.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unit tests for {@link OpenApiCompatibility}. They build small synthetic OpenAPI specs with the
 * Jackson {@link ObjectMapper} (so the fixtures are always well-formed JSON) and assert that the
 * breaker classifier recognises the S03-16 breaking changes (most importantly the removal of a
 * <em>required</em> property) and treats additive changes as compatible.
 */
class OpenApiCompatibilityTest {

  private static final ObjectMapper mapper = new ObjectMapper();
  private final OpenApiCompatibility checker = new OpenApiCompatibility();

  /** A {@code {"type":"object","properties":{...},"required":[...]}} schema node. */
  private static ObjectNode schema(Map<String, String> properties, String... required) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    properties.forEach((name, type) -> props.putObject(name).put("type", type));
    if (required.length > 0) {
      ArrayNode req = schema.putArray("required");
      for (String name : required) {
        req.add(name);
      }
    }
    return schema;
  }

  /** A {@code {"components":{"schemas":{name: schema}}}} wrapper around a schema. */
  private static JsonNode schemaSpec(String name, ObjectNode schema) {
    ObjectNode schemas = mapper.createObjectNode();
    schemas.set(name, schema);
    ObjectNode components = mapper.createObjectNode();
    components.set("schemas", schemas);
    ObjectNode wrapper = mapper.createObjectNode();
    wrapper.set("components", components);
    return wrapper;
  }

  /** A bare {@code {"paths":{...}}} spec with the given path keys. */
  private static JsonNode pathSpec(String... paths) {
    ObjectNode root = mapper.createObjectNode();
    ObjectNode pathsNode = root.putObject("paths");
    for (String path : paths) {
      pathsNode.putObject(path);
    }
    return root;
  }

  private static JsonNode pathSpecWithItem(String path, ObjectNode item) {
    ObjectNode root = mapper.createObjectNode();
    root.putObject("paths").set(path, item);
    return root;
  }

  @Test
  void removingARequiredPropertyIsBreaking() {
    // Baseline exposes a required `name` on the create request; current drops it.
    JsonNode baseline =
        schemaSpec("Req", schema(Map.of("name", "string", "description", "string"), "name"));
    JsonNode current = schemaSpec("Req", schema(Map.of("description", "string")));

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isTrue();
    assertTrue(
        report.breakingChanges().contains("removed required property Req.name"),
        () -> report.breakingChanges().toString());
    assertThat(report.summary()).contains("removed required property Req.name");
  }

  @Test
  void removingAnOptionalPropertyIsCompatible() {
    JsonNode baseline =
        schemaSpec("Req", schema(Map.of("name", "string", "description", "string"), "name"));
    JsonNode current = schemaSpec("Req", schema(Map.of("name", "string"), "name"));

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isFalse();
    assertTrue(
        report.compatibleChanges().contains("removed optional property Req.description"),
        () -> report.compatibleChanges().toString());
  }

  @Test
  void removingAPathIsBreaking() {
    JsonNode baseline = pathSpec("/api/v1/workflows", "/api/v1/workflows/{id}");
    JsonNode current = pathSpec("/api/v1/workflows");

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isTrue();
    assertTrue(
        report.breakingChanges().contains("removed path /api/v1/workflows/{id}"),
        () -> report.breakingChanges().toString());
  }

  @Test
  void removingAnHttpMethodIsBreaking() {
    // Build {"/api/v1/workflows/{id}": {"get": {...}, "delete": {...}}}. putObject() returns the
    // created child, so capture the parent to add more than one method.
    ObjectNode root = mapper.createObjectNode();
    root.putObject("get");
    root.putObject("delete");
    ObjectNode itemGetOnly = mapper.createObjectNode();
    itemGetOnly.putObject("get");
    JsonNode baseline = pathSpecWithItem("/api/v1/workflows/{id}", root);
    JsonNode current = pathSpecWithItem("/api/v1/workflows/{id}", itemGetOnly);

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isTrue();
    assertTrue(
        report.breakingChanges().stream()
            .anyMatch(s -> s.contains("DELETE /api/v1/workflows/{id}")),
        () -> report.breakingChanges().toString());
  }

  @Test
  void changingAPropertyTypeIsBreaking() {
    JsonNode baseline = schemaSpec("Req", schema(Map.of("count", "integer")));
    JsonNode current = schemaSpec("Req", schema(Map.of("count", "string")));

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isTrue();
    assertTrue(
        report.breakingChanges().stream().anyMatch(s -> s.contains("changed type of Req.count")),
        () -> report.breakingChanges().toString());
  }

  @Test
  void addingOptionalFieldAndPathKeepsTheGateGreen() {
    // The realistic "I added an endpoint + a field" case: no breaking change, the baseline just
    // needs a deliberate refresh. putObject() returns the created child, so capture the parent.
    ObjectNode baselineRoot = mapper.createObjectNode();
    baselineRoot.putObject("/api/v1/workflows");
    JsonNode baseline = schemaSpec("Req", schema(Map.of("name", "string"), "name"));
    ((ObjectNode) baseline).set("paths", baselineRoot);

    ObjectNode currentRoot = mapper.createObjectNode();
    currentRoot.putObject("/api/v1/workflows");
    currentRoot.putObject("/api/v1/agents");
    JsonNode current =
        schemaSpec("Req", schema(Map.of("name", "string", "scope", "string"), "name"));
    ((ObjectNode) current).set("paths", currentRoot);

    var report = checker.compare(baseline, current);

    assertThat(report.isBreaking()).isFalse();
    assertThat(report.compatibleChanges())
        .contains("added path /api/v1/agents", "added property Req.scope");
  }

  @Test
  void identicalSpecsReportNoChanges() {
    JsonNode spec = schemaSpec("Req", schema(Map.of("name", "string"), "name"));

    var report = checker.compare(spec, spec);

    assertThat(report.isBreaking()).isFalse();
    assertThat(report.breakingChanges()).isEmpty();
    assertThat(report.compatibleChanges()).isEmpty();
  }
}
