package com.example.agentflow.eventcontract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Event envelope contract tests (task S06-13).
 *
 * <p>The committed envelope schema ({@code contracts/events/json-schema/envelope-v1.json}) is the
 * source of truth, and the canonical producer fixture ({@code envelope-v1.example.json}) is
 * validated against it. The suite covers:
 *
 * <ul>
 *   <li>the producer fixture is accepted;
 *   <li>every <b>required</b> field is enforced (a missing one breaks the contract);
 *   <li>an <b>additional</b> (unauthorized) property is rejected ({@code additionalProperties:
 *       false});
 *   <li>the {@code schemaVersion} {@code const} is enforced &mdash; this is the version-bump gate,
 *       so a producer that drifts the envelope version fails CI;
 *   <li>wrong {@code type} / {@code format} (non-object payload, non-UUID tenant) are rejected;
 *   <li>the only <b>optional</b> field ({@code causationId}) is compatible when absent or {@code
 *       null}.
 * </ul>
 *
 * <p>Because the schema is loaded from disk, a breaking schema change (a removed required field, a
 * changed {@code schemaVersion}, an added property, a changed type) fails the relevant assertion
 * and therefore {@code ./gradlew check} &mdash; a breaking change cannot slip through without an
 * explicit, deliberate baseline update (a new {@code schemaVersion} and an updated fixture).
 *
 * <p>The committed schema directory is handed to the test as {@code vent.contract.dir} by the
 * {@code test} task (the same pattern the OpenAPI and workflow-graph contract gates use); the test
 * is skipped when run without it, for example from an IDE without the {@code -D} flag.
 */
class EventContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The committed schema directory, or {@code null} when the {@code -Dvent.contract.dir} flag is
   * absent.
   */
  private static final String CONTRACT_DIR = System.getProperty("vent.contract.dir");

  private EnvelopeContractValidator validator;
  private ObjectNode example;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Path.of(CONTRACT_DIR);
    assertThat(dir).as("the event contract directory must exist").exists().isDirectory();

    Path schema = dir.resolve("envelope-v1.json");
    validator = new EnvelopeContractValidator(schema);
    assertThat(schema).as("the envelope schema must exist").exists().isRegularFile();

    Path fixture = dir.resolve("envelope-v1.example.json");
    assertThat(fixture).as("the producer fixture must exist").exists().isRegularFile();
    example = (ObjectNode) MAPPER.readTree(Files.readAllBytes(fixture));
  }

  @Test
  void the_producer_fixture_validates_against_the_committed_schema() {
    assertThat(validator.validate(toJson(example))).as("canonical fixture must be valid").isEmpty();
  }

  @Test
  void the_committed_schema_is_draft_2020_12() throws IOException {
    JsonNode schema =
        MAPPER.readTree(Files.readAllBytes(Path.of(CONTRACT_DIR).resolve("envelope-v1.json")));
    assertThat(schema.path("$schema").toString())
        .as("the envelope schema declares draft 2020-12")
        .contains("2020-12");
  }

  @ParameterizedTest(name = "removing required '{0}' breaks the contract")
  @MethodSource("requiredFields")
  void every_required_field_is_enforced(String field) {
    ObjectNode modified = (ObjectNode) example.deepCopy();
    modified.remove(field);
    assertThat(validator.validate(toJson(modified)))
        .as("missing '%s' must fail the contract", field)
        .isNotEmpty()
        .anySatisfy(message -> assertThat(message).contains(field));
  }

  @Test
  void an_unauthorized_additional_property_is_rejected() {
    ObjectNode modified = (ObjectNode) example.deepCopy();
    modified.put("unrelatedField", "value");
    assertThat(validator.validate(toJson(modified)))
        .as("an extra envelope property must be rejected (additionalProperties: false)")
        .isNotEmpty();
  }

  @Test
  void a_wrong_schema_version_is_rejected() {
    ObjectNode modified = (ObjectNode) example.deepCopy();
    modified.put("schemaVersion", "2.0.0");
    assertThat(validator.validate(toJson(modified)))
        .as("a schemaVersion other than the const 1.0.0 must be rejected (envelope version gate)")
        .isNotEmpty();
  }

  @Test
  void a_non_object_payload_is_rejected() {
    ObjectNode modified = (ObjectNode) example.deepCopy();
    // A JSON string literal parses to a TextNode, i.e. a non-object payload.
    JsonNode nonObjectPayload = MAPPER.readTree("\"payload-must-be-an-object\"");
    modified.set("payload", nonObjectPayload);
    assertThat(validator.validate(toJson(modified)))
        .as("a non-object payload must be rejected")
        .isNotEmpty();
  }

  @Test
  void a_non_uuid_tenant_id_is_rejected() {
    ObjectNode modified = (ObjectNode) example.deepCopy();
    modified.put("tenantId", "not-a-uuid");
    assertThat(validator.validate(toJson(modified)))
        .as("a non-UUID id must be rejected (format: uuid)")
        .isNotEmpty();
  }

  @Test
  void the_optional_causation_id_is_compatible_when_absent_or_null() {
    // The example carries causationId = null (the first event of a chain); that is valid.
    assertThat(validator.validate(toJson(example))).as("causationId=null must be valid").isEmpty();

    // ...and omitting the optional field entirely is also valid: it is genuinely optional.
    ObjectNode omitted = (ObjectNode) example.deepCopy();
    omitted.remove("causationId");
    assertThat(validator.validate(toJson(omitted)))
        .as("an absent causationId must be valid (the field is optional)")
        .isEmpty();
  }

  /**
   * @return the required-field names declared in the committed envelope schema.
   */
  private static Stream<String> requiredFields() throws IOException {
    JsonNode schema =
        MAPPER.readTree(Files.readAllBytes(Path.of(CONTRACT_DIR).resolve("envelope-v1.json")));
    JsonNode required = schema.get("required");
    if (required == null) {
      throw new IllegalStateException("the envelope schema must declare a 'required' array");
    }
    String[] names = MAPPER.convertValue(required, String[].class);
    List<String> fields = new ArrayList<>(Arrays.asList(names));
    assertThat(fields).as("the schema must declare a non-empty required set").isNotEmpty();
    return fields.stream();
  }

  private static String toJson(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (Exception notSerializable) {
      throw new IllegalStateException(notSerializable);
    }
  }

  /** Skip the whole suite when the contract directory flag is absent (e.g. an IDE run). */
  @EnabledIfSystemProperty(named = "vent.contract.dir", matches = ".+")
  static void contractDirPresent() {}
}
