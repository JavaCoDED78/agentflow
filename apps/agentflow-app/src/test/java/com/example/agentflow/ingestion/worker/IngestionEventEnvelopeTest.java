package com.example.agentflow.ingestion.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the consumed-envelope parser (S10-13): a well-formed {@code
 * document.ingestion-requested.v1} envelope yields the identity triple, anything without a full
 * identity (missing, blank, non-UUID or non-JSON) yields empty — such a record has no dedup anchor
 * and is skipped past by the worker.
 */
class IngestionEventEnvelopeTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();

  private static String envelope(String eventId, String tenantId, String documentId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"document.ingestion-requested.v1\",\"tenantId\":\""
        + tenantId
        + "\",\"payload\":{\"documentId\":\""
        + documentId
        + "\",\"knowledgeBaseId\":\""
        + UUID.randomUUID()
        + "\"}}";
  }

  @Test
  void a_well_formed_envelope_yields_the_identity_triple() {
    Optional<IngestionEventEnvelope> parsed =
        IngestionEventEnvelope.parse(
            envelope(EVENT.toString(), TENANT.toString(), DOCUMENT.toString()));

    assertThat(parsed)
        .isPresent()
        .get()
        .satisfies(
            e -> {
              assertThat(e.eventId()).isEqualTo(EVENT);
              assertThat(e.tenantId()).isEqualTo(TENANT);
              assertThat(e.documentId()).isEqualTo(DOCUMENT);
            });
  }

  @Test
  void a_missing_event_id_yields_empty() {
    assertThat(IngestionEventEnvelope.parse(envelope(null, TENANT.toString(), DOCUMENT.toString())))
        .isEmpty();
  }

  @Test
  void a_missing_tenant_id_yields_empty() {
    assertThat(IngestionEventEnvelope.parse(envelope(EVENT.toString(), null, DOCUMENT.toString())))
        .isEmpty();
  }

  @Test
  void a_missing_payload_document_id_yields_empty() {
    assertThat(IngestionEventEnvelope.parse(envelope(EVENT.toString(), TENANT.toString(), null)))
        .isEmpty();
    assertThat(
            IngestionEventEnvelope.parse(
                "{\"eventId\":\"" + EVENT + "\",\"tenantId\":\"" + TENANT + "\",\"payload\":{}}"))
        .isEmpty();
  }

  @Test
  void a_non_uuid_identity_field_yields_empty() {
    assertThat(
            IngestionEventEnvelope.parse(
                envelope("not-a-uuid", TENANT.toString(), DOCUMENT.toString())))
        .isEmpty();
  }

  @Test
  void a_non_json_record_yields_empty() {
    assertThat(IngestionEventEnvelope.parse("not json at all")).isEmpty();
  }

  @Test
  void a_null_or_blank_record_yields_empty() {
    assertThat(IngestionEventEnvelope.parse(null)).isEmpty();
    assertThat(IngestionEventEnvelope.parse("  ")).isEmpty();
  }
}
