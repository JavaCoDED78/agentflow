package com.example.agentflow.messaging.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The correlation metadata an event carries across Kafka, in addition to the envelope body
 * (S06-12).
 *
 * <p>Three fields, each optional and only ever <b>correlation</b> data — never a secret, full
 * prompt or full document (invariants 7 / 8), and never an authorization input (invariant 1):
 *
 * <ul>
 *   <li>{@code correlationId} &mdash; the id of the whole operation chain, shared by every event of
 *       one run from request to completion (for a run-request event this is the {@code runId}).
 *   <li>{@code causationId} &mdash; the id of the event that caused this one, so a consumer can
 *       link an event to its predecessor; {@code null} for the first event of a chain (no
 *       predecessor).
 *   <li>{@code traceparent} &mdash; the W3C Trace-Context parent (e.g. {@code
 *       00-<traceId>-<spanId>-<flags>}) of the request that started the event, threaded untouched
 *       through the producer so distributed tracing is preserved.
 * </ul>
 *
 * <p>The values are written to the {@code outbox_events.headers} column as JSON by the producer and
 * forwarded verbatim as Kafka record headers by the deliverer (S06-09). Only non-null, non-blank
 * fields are serialized and in a stable field order, so the JSON is deterministic (a stable diff)
 * and a missing field is never emitted as a {@code null}-byte header. The {@code tenantId} is
 * deliberately <b>not</b> a correlation header: it is a real column on {@code outbox_events} (a
 * typed FK) and on the run row, so authorization is always resolved from the stored context and
 * never from a header (invariant 1).
 *
 * <p>Pure value type, no Spring / transport dependency; the JSON serialization uses Jackson (a
 * plain library), matching {@code KafkaMessageDeliverer}.
 */
public record CorrelationHeaders(String correlationId, String causationId, String traceparent) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * @param correlationId the operation-chain id, or {@code null} to omit (correlation only)
   * @param causationId the triggering event id, or {@code null} for the first event of a chain
   * @param traceparent the W3C trace-parent, or {@code null} when no trace context is threaded
   * @return the correlation metadata; a field is present in {@link #toJson()} only when non-null
   *     and non-blank, in the stable order {@code correlationId, causationId, traceparent}
   */
  public static CorrelationHeaders of(
      String correlationId, String causationId, String traceparent) {
    return new CorrelationHeaders(correlationId, causationId, traceparent);
  }

  /**
   * @return a deterministic JSON object of only the present fields (stable field order, no {@code
   *     null} values), so the persisted {@code outbox_events.headers} is stable across equivalent
   *     events and a missing field never becomes an empty Kafka header
   */
  public String toJson() {
    Map<String, String> fields = new LinkedHashMap<>();
    addPresent(fields, "correlationId", correlationId);
    addPresent(fields, "causationId", causationId);
    addPresent(fields, "traceparent", traceparent);
    try {
      return MAPPER.writeValueAsString(fields);
    } catch (JsonProcessingException notSerializable) {
      // A LinkedHashMap of String values never fails to serialize; treat it as a programming error
      // rather than a runtime fault, so it surfaces loudly instead of shipping empty headers.
      throw new IllegalStateException("could not serialize correlation headers", notSerializable);
    }
  }

  private static void addPresent(Map<String, String> fields, String key, String value) {
    if (value != null && !value.isBlank()) {
      fields.put(key, value);
    }
  }
}
