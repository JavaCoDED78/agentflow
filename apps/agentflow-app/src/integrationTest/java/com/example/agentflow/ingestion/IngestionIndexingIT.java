package com.example.agentflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ingestion.application.IngestionEventKey;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.application.parser.TikaDocumentParser;
import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.RedeliveryPolicy;
import com.example.agentflow.ingestion.domain.Strategy;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.DocumentPayloadStore;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentRepository;
import com.example.agentflow.messaging.application.DlqService;
import com.example.agentflow.messaging.application.InboxConsumeResult;
import com.example.agentflow.messaging.application.InboxConsumerService;
import com.example.agentflow.messaging.application.InboxDedupService;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The S10-13 acceptance proof against a <em>real</em> PostgreSQL — {@code
 * pgvector/pgvector:0.8.6-pg18}, the exact image Compose pins (never {@code H2}): one {@code
 * ingestion-requested} event driven through the real inbox boundary, the real repositories and the
 * real Flyway-migrated schema, with a deterministic in-memory embedding port and payload store (no
 * model, no Garage, no Kafka — the transport loop is covered by the unit tests, this proves the
 * transactional indexing path).
 *
 * <p>The headline criterion: <b>repeating one ingestion event does not create duplicate logical
 * chunks</b> — a redelivery is deduplicated by the inbox claim, and a second event for an
 * already-indexed document is absorbed by the conditional claim (SKIP) or overwrites in place
 * (UPDATE), never duplicating a row.
 */
@Testcontainers
class IngestionIndexingIT {

  private static final String CONSUMER = "agentflow.ingestion-worker";
  private static final String TOPIC = "agentflow.document.ingestion-requested.v1";
  private static final String INDEXED_TOPIC = "agentflow.document.indexed.v1";

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private static JdbcTemplate jdbc;
  private static DataSource dataSource;
  private static DocumentRepository documentRepository;
  private static DocumentChunkRepository chunkRepository;
  private static InboxMessageRepository inboxRepository;
  private static DlqMessageRepository dlqRepository;
  private static OutboxEventRepository outboxRepository;

  @BeforeAll
  static void migrateAndWire() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:migrations")
            .load();
    flyway.migrate();

    dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    documentRepository = new DocumentRepository(jdbc);
    chunkRepository = new DocumentChunkRepository(jdbc);
    inboxRepository = new InboxMessageRepository(jdbc);
    dlqRepository = new DlqMessageRepository(jdbc);
    outboxRepository = new OutboxEventRepository(jdbc);
  }

  /** Per-test isolation: the shared outbox/inbox/DLQ tables accumulate across tests. */
  @BeforeEach
  void cleanSharedTables() {
    jdbc.update("DELETE FROM outbox_events");
    jdbc.update("DELETE FROM inbox_messages");
    jdbc.update("DELETE FROM dlq_messages");
  }

  /** A deterministic 768-dimension embedding port (no model, no network). */
  private static EmbeddingPort fakeEmbeddingPort() {
    return request ->
        new EmbeddingResponse(
            request.model(),
            request.texts().stream().map(text -> unitVector(text.hashCode())).toList(),
            null);
  }

  /** A unit-ish 768-d vector derived deterministically from the text hash. */
  private static float[] unitVector(int seed) {
    float[] v = new float[768];
    Arrays.fill(v, (seed % 7) * 0.001f);
    v[0] = 1.0f;
    return v;
  }

  /** Build the real pipeline with the policy/port/store under test. */
  private IngestionService service(
      RedeliveryPolicy policy, EmbeddingPort port, DocumentPayloadStore store) {
    return new IngestionService(
        new DocumentQueryService(documentRepository),
        new DocumentTransitionService(documentRepository),
        new ChunkIndexingService(chunkRepository),
        store,
        new TikaDocumentParser(100, 500_000),
        new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50)),
        new EmbeddingGenerationService(port, 32),
        new OutboxService(outboxRepository),
        policy);
  }

  /** The consumer boundary the worker's loop calls per record (the real inbox dedup path). */
  private InboxConsumeResult apply(
      UUID eventId, UUID tenantId, UUID documentId, IngestionService service) {
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    InboxConsumerService boundary =
        new InboxConsumerService(
            new InboxDedupService(inboxRepository, transactionTemplate, 3),
            new DlqService(dlqRepository),
            inboxRepository);
    InboxMessage claim = InboxMessage.claiming(CONSUMER, eventId);
    return boundary.apply(
        TOPIC,
        claim,
        () -> service.index(new IngestionEventKey(tenantId, documentId), eventId).status());
  }

  /** Insert a tenant + knowledge base + an UPLOADED document (the S10-05/S10-06 shape). */
  private static UUID[] insertDocument(String slug, String mimeType) {
    UUID tenant = UUID.randomUUID();
    UUID kb = UUID.randomUUID();
    UUID document = UUID.randomUUID();
    jdbc.update("INSERT INTO tenants (id, slug, status) VALUES (?, ?, 'ACTIVE')", tenant, slug);
    jdbc.update(
        "INSERT INTO knowledge_bases (id, tenant_id, name, embedding_model) VALUES (?, ?, ?, ?)",
        kb,
        tenant,
        slug + "-kb",
        "nomic-embed-text");
    jdbc.update(
        "INSERT INTO documents (id, tenant_id, knowledge_base_id, object_key, filename, "
            + "mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        document,
        tenant,
        kb,
        "documents/" + tenant + "/" + document,
        "sample" + (mimeType.endsWith("pdf") ? ".pdf" : ".txt"),
        mimeType,
        1024L,
        "sha256-" + slug);
    return new UUID[] {tenant, kb, document};
  }

  /**
   * A {@code %PDF} header with a non-existent cross-reference (the S10-07 corrupt fixture): {@code
   * PDFBox} cannot recover, so the parser classifies it {@code CORRUPT}.
   */
  private static byte[] corruptPdf() {
    byte[] head = "%PDF-1.4\nxref\n".getBytes(StandardCharsets.US_ASCII);
    byte[] tail = new byte[200];
    for (int i = 0; i < tail.length; i++) {
      tail[i] = (byte) (0x80 + (i % 120));
    }
    byte[] out = new byte[head.length + tail.length];
    System.arraycopy(head, 0, out, 0, head.length);
    System.arraycopy(tail, 0, out, head.length, tail.length);
    return out;
  }

  private static DocumentPayloadStore storeOf(byte[] payload) {
    return (tenantId, documentId) -> payload;
  }

  private static int chunkCount(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?", Integer.class, documentId);
  }

  private static String documentStatus(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT status FROM documents WHERE id = ?", String.class, documentId);
  }

  private static String documentErrorCode(UUID documentId) {
    return jdbc.queryForObject(
        "SELECT error_code FROM documents WHERE id = ?", String.class, documentId);
  }

  private static int indexedEventCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox_events WHERE topic = ?", Integer.class, INDEXED_TOPIC);
  }

  /** The S10-13 acceptance: repeating one ingestion event creates no duplicate logical chunks. */
  @Test
  void a_redelivered_ingestion_event_creates_no_duplicate_chunks() {
    UUID[] ids = insertDocument("redelivery", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    UUID event = UUID.randomUUID();
    IngestionService service =
        service(
            RedeliveryPolicy.SKIP,
            fakeEmbeddingPort(),
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    InboxConsumeResult first = apply(event, tenant, document, service);
    assertThat(first).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(documentStatus(document)).isEqualTo("INDEXED");
    assertThat(chunkCount(document)).isEqualTo(1);
    assertThat(indexedEventCount()).isEqualTo(1);

    // The SAME event redelivered (at-least-once, ADR-003): deduplicated by the inbox claim, the
    // pipeline never re-runs, and no duplicate chunk or indexed event appears.
    InboxConsumeResult second = apply(event, tenant, document, service);
    assertThat(second).isEqualTo(InboxConsumeResult.OTHER_CLAIMED);
    assertThat(chunkCount(document)).isEqualTo(1);
    assertThat(indexedEventCount()).isEqualTo(1);
    assertThat(documentStatus(document)).isEqualTo("INDEXED");
  }

  /** A second (new) event for an already-indexed document is absorbed under the SKIP policy. */
  @Test
  void a_second_event_for_an_indexed_document_skips_under_the_skip_policy() {
    UUID[] ids = insertDocument("second-event", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    IngestionService service =
        service(
            RedeliveryPolicy.SKIP,
            fakeEmbeddingPort(),
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    assertThat(apply(UUID.randomUUID(), tenant, document, service))
        .isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(chunkCount(document)).isEqualTo(1);

    assertThat(apply(UUID.randomUUID(), tenant, document, service))
        .isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(chunkCount(document)).isEqualTo(1);
    assertThat(indexedEventCount()).isEqualTo(1);
  }

  /** Under the UPDATE policy a redelivery re-embeds and overwrites the chunk rows in place. */
  @Test
  void an_update_policy_redelivery_refreshes_chunks_in_place() {
    UUID[] ids = insertDocument("update-policy", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    IngestionService service =
        service(
            RedeliveryPolicy.UPDATE,
            fakeEmbeddingPort(),
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    assertThat(apply(UUID.randomUUID(), tenant, document, service))
        .isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(chunkCount(document)).isEqualTo(1);

    // A new event under UPDATE re-claims the indexed document: the single chunk row is
    // overwritten, never duplicated, and the reindex is a new durable fact (a second event).
    assertThat(apply(UUID.randomUUID(), tenant, document, service))
        .isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(chunkCount(document)).isEqualTo(1);
    assertThat(indexedEventCount()).isEqualTo(2);
  }

  /** A mismatched embedding width fails the document loudly, never reaching the index. */
  @Test
  void a_mismatched_embedding_dimension_fails_the_document() {
    UUID[] ids = insertDocument("dimension", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    EmbeddingPort wrongWidth =
        request ->
            new EmbeddingResponse(
                request.model(),
                request.texts().stream().map(text -> new float[100]).toList(),
                null);
    IngestionService service =
        service(
            RedeliveryPolicy.SKIP,
            wrongWidth,
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    InboxConsumeResult result = apply(UUID.randomUUID(), tenant, document, service);
    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(documentStatus(document)).isEqualTo("FAILED");
    assertThat(documentErrorCode(document)).isEqualTo("EMBEDDING_DIMENSION_MISMATCH");
    assertThat(chunkCount(document)).isZero();
    assertThat(indexedEventCount()).isZero();
  }

  /** A corrupt payload is a permanent failure recorded with the safe S10-08 code. */
  @Test
  void a_corrupt_payload_fails_the_document_with_the_extraction_code() {
    UUID[] ids = insertDocument("corrupt", "application/pdf");
    UUID tenant = ids[0];
    UUID document = ids[2];
    IngestionService service =
        service(RedeliveryPolicy.SKIP, fakeEmbeddingPort(), storeOf(corruptPdf()));

    InboxConsumeResult result = apply(UUID.randomUUID(), tenant, document, service);
    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(documentStatus(document)).isEqualTo("FAILED");
    assertThat(documentErrorCode(document)).startsWith("EXTRACTION_");
    assertThat(chunkCount(document)).isZero();
  }

  /**
   * A transient provider fault holds the event; the claim rolls back, so the document is requeued.
   */
  @Test
  void a_transient_provider_fault_holds_the_event_and_requeues_the_document() {
    UUID[] ids = insertDocument("transient", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    UUID event = UUID.randomUUID();
    EmbeddingPort down =
        request -> {
          throw new RuntimeException("provider unavailable");
        };
    IngestionService service =
        service(
            RedeliveryPolicy.SKIP,
            down,
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    InboxConsumeResult result = apply(event, tenant, document, service);
    assertThat(result).isEqualTo(InboxConsumeResult.RETRY);
    // The claim was part of the rolled-back pass: the document is back in its prior state, never
    // stuck in PROCESSING (the lifecycle's "release" is the transaction rollback).
    assertThat(documentStatus(document)).isEqualTo("UPLOADED");
    InboxMessage row = inboxRepository.findByConsumerAndEvent(CONSUMER, event).orElseThrow();
    assertThat(row.status()).isEqualTo(InboxMessage.Status.FAILED);
    assertThat(row.attempts()).isEqualTo(3);
    assertThat(dlqRepository.findByConsumerAndEvent(CONSUMER, event))
        .isPresent()
        .get()
        .extracting(DlqMessage::status)
        .isEqualTo(DlqMessage.Status.RETRY);
  }

  /** An empty payload is not an error: the document completes with zero chunks. */
  @Test
  void an_empty_payload_completes_with_zero_chunks() {
    UUID[] ids = insertDocument("empty", "text/plain");
    UUID tenant = ids[0];
    UUID document = ids[2];
    IngestionService service =
        service(RedeliveryPolicy.SKIP, fakeEmbeddingPort(), storeOf(new byte[0]));

    InboxConsumeResult result = apply(UUID.randomUUID(), tenant, document, service);
    assertThat(result).isEqualTo(InboxConsumeResult.PROCESSED);
    assertThat(documentStatus(document)).isEqualTo("INDEXED");
    assertThat(chunkCount(document)).isZero();
    assertThat(indexedEventCount()).isEqualTo(1);
  }

  /** A cross-tenant key is indistinguishable from a missing one: permanent, dead-lettered. */
  @Test
  void a_cross_tenant_event_is_a_permanent_fault_and_never_touches_the_document() {
    UUID[] ids = insertDocument("cross-tenant", "text/plain");
    UUID document = ids[2];
    IngestionService service =
        service(
            RedeliveryPolicy.SKIP,
            fakeEmbeddingPort(),
            storeOf(
                "hello world from the integration test document".getBytes(StandardCharsets.UTF_8)));

    InboxConsumeResult result = apply(UUID.randomUUID(), UUID.randomUUID(), document, service);
    assertThat(result).isEqualTo(InboxConsumeResult.DEAD_LETTER);
    assertThat(documentStatus(document)).isEqualTo("UPLOADED");
    assertThat(chunkCount(document)).isZero();
  }
}
