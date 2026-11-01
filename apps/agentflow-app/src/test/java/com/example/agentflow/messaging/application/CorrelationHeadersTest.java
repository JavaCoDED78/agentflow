package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for {@link CorrelationHeaders} (S06-12): the factory builds a deterministic JSON
 * object of only the present fields (stable field order, no {@code null} values), so the persisted
 * {@code outbox_events.headers} is stable across equivalent events and a missing field never
 * becomes an empty Kafka header. The {@code tenantId} is intentionally absent — it is a real column
 * on the outbox row, never a correlation header (invariant 1).
 */
class CorrelationHeadersTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serializes_the_three_fields_in_stable_order_when_all_present() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    String causationId = UUID.randomUUID().toString();
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    String json = CorrelationHeaders.of(correlationId, causationId, traceparent).toJson();

    JsonNode node = mapper.readTree(json);
    assertThat(fieldNames(node)).containsExactly("correlationId", "causationId", "traceparent");
    assertThat(node.get("correlationId").asText()).isEqualTo(correlationId);
    assertThat(node.get("causationId").asText()).isEqualTo(causationId);
    assertThat(node.get("traceparent").asText()).isEqualTo(traceparent);
  }

  @Test
  void omits_a_null_causation_for_the_first_event_of_a_chain() throws Exception {
    // A run-request is the first event of its chain: correlationId = runId, no causation.
    String correlationId = UUID.randomUUID().toString();

    String json = CorrelationHeaders.of(correlationId, null, null).toJson();

    JsonNode node = mapper.readTree(json);
    assertThat(fieldNames(node)).containsExactly("correlationId");
    assertThat(node.has("causationId")).isFalse();
    assertThat(node.has("traceparent")).isFalse();
  }

  @Test
  void threads_a_traceparent_but_keeps_the_chain_id_and_null_causation() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    String json = CorrelationHeaders.of(correlationId, null, traceparent).toJson();

    JsonNode node = mapper.readTree(json);
    assertThat(fieldNames(node)).containsExactly("correlationId", "traceparent");
    assertThat(node.get("correlationId").asText()).isEqualTo(correlationId);
    assertThat(node.get("traceparent").asText()).isEqualTo(traceparent);
  }

  @Test
  void treats_a_blank_traceparent_as_absent() throws Exception {
    String correlationId = UUID.randomUUID().toString();

    String json = CorrelationHeaders.of(correlationId, null, "   ").toJson();

    JsonNode node = mapper.readTree(json);
    assertThat(fieldNames(node)).containsExactly("correlationId");
    assertThat(node.has("traceparent")).isFalse();
  }

  @Test
  void produces_identical_json_for_equivalent_present_fields() {
    // Determinism keeps the persisted headers stable for an equivalent event.
    String correlationId = "11111111-2222-3333-4444-555566667777";
    String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    String a = CorrelationHeaders.of(correlationId, null, traceparent).toJson();
    String b = CorrelationHeaders.of(correlationId, null, traceparent).toJson();

    assertThat(a).isEqualTo(b);
  }

  private List<String> fieldNames(JsonNode node) {
    List<String> fields = new ArrayList<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
