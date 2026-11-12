package com.example.agentflow.ingestion.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;

/**
 * The consumed {@code document.ingestion-requested.v1} envelope, parsed from the Kafka record value
 * (S10-13).
 *
 * <p>Extracts exactly what the indexing pass needs and nothing else: the {@code eventId} (the inbox
 * dedup anchor — the SAME id as the outbox event id), the {@code tenantId} (correlation metadata,
 * re-checked against the stored document row — never an authorization source, invariant 1) and the
 * {@code payload.documentId}.
 *
 * <p>Parsing is lenient on shape and strict on identity: an envelope whose {@code eventId}, {@code
 * tenantId} or {@code payload.documentId} is missing, blank or unparseable yields {@link
 * Optional#empty()} — such a record has no dedup anchor, so it is logged and skipped (committed
 * past) rather than blocking its partition forever. A well-formed envelope referencing a
 * missing/cross-tenant document is <em>not</em> filtered here: it is the pipeline's permanent
 * fault, dead-lettered through the inbox.
 */
record IngestionEventEnvelope(UUID eventId, UUID tenantId, UUID documentId) {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Parse one record value.
   *
   * @param recordValue the Kafka record value (the envelope JSON); may be {@code null} or blank
   * @return the envelope, or empty when a required identity field is missing or unparseable
   */
  static Optional<IngestionEventEnvelope> parse(String recordValue) {
    if (recordValue == null || recordValue.isBlank()) {
      return Optional.empty();
    }
    try {
      var root = MAPPER.readTree(recordValue);
      UUID eventId = uuidOrNull(root.path("eventId").asText(null));
      UUID tenantId = uuidOrNull(root.path("tenantId").asText(null));
      UUID documentId = uuidOrNull(root.path("payload").path("documentId").asText(null));
      if (eventId == null || tenantId == null || documentId == null) {
        return Optional.empty();
      }
      return Optional.of(new IngestionEventEnvelope(eventId, tenantId, documentId));
    } catch (Exception notJson) {
      return Optional.empty();
    }
  }

  private static UUID uuidOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException notUuid) {
      return null;
    }
  }
}
