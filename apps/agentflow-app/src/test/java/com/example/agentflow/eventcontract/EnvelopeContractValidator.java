package com.example.agentflow.eventcontract;

import com.networknt.schema.Error;
import com.networknt.schema.ExecutionConfig;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Result;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates a JSON document against the committed event envelope contract (draft 2020-12) with the
 * networknt JSON Schema validator.
 *
 * <p>Test-only helper for the event contract suite (task S06-13). It loads {@code
 * contracts/events/json-schema/envelope-v1.json} once and validates producer fixtures against it.
 * Format assertions are enabled so {@code format: uuid} / {@code format: date-time} behave like the
 * {@code ajv} check the contract README documents, and {@code const} / {@code required} / {@code
 * additionalProperties: false} / {@code type} are enforced by default &mdash; so a breaking schema
 * change (a removed required field, a changed {@code schemaVersion}, an extra property, a wrong
 * type) fails validation and therefore CI.
 *
 * <p>Pure test utility: no Spring, no transport. It uses the application's Jackson 3 (the {@code
 * tools.jackson} namespace) so it exercises the same code path as the rest of the app; the
 * networknt validator is a Jackson-3 build on the {@code tools.jackson} line, matching this stack.
 */
public final class EnvelopeContractValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Schema schema;

  /**
   * @param schemaPath the path to the committed envelope JSON Schema (draft 2020-12)
   * @throws IllegalArgumentException if the schema file is missing
   * @throws IllegalStateException if it cannot be read or does not parse
   */
  public EnvelopeContractValidator(Path schemaPath) {
    if (schemaPath == null || !Files.isRegularFile(schemaPath)) {
      throw new IllegalArgumentException("envelope schema not found: " + schemaPath);
    }
    try {
      JsonNode schemaNode = MAPPER.readTree(Files.readAllBytes(schemaPath));
      this.schema = SchemaRegistry.builder().build().getSchema(schemaNode);
    } catch (IOException notReadable) {
      throw new IllegalStateException("could not read envelope schema " + schemaPath, notReadable);
    }
  }

  /**
   * @param json a JSON document (for example a producer fixture)
   * @return the validation messages; an empty list means the document is valid
   * @throws IllegalStateException if the document cannot be parsed
   */
  public List<String> validate(String json) {
    return validate(readNode(json));
  }

  /**
   * @param data a parsed JSON document
   * @return the validation messages; an empty list means the document is valid
   */
  public List<String> validate(JsonNode data) {
    // format is an annotation by default from draft 2019-09 on; enable it so uuid / date-time are
    // asserted like ajv. const / required / additionalProperties / type are asserted regardless.
    ExecutionConfig config = ExecutionConfig.builder().formatAssertionsEnabled(true).build();
    ExecutionContext context = new ExecutionContext(config);
    Result result = schema.walk(context, data, true);
    return result.getErrors().stream().map(Error::getMessage).toList();
  }

  private static JsonNode readNode(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception notJson) {
      throw new IllegalStateException("could not parse JSON document", notJson);
    }
  }
}
