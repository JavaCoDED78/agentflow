package com.example.agentflow.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.RedeliveryPolicy;
import com.example.agentflow.ingestion.domain.Strategy;
import com.example.agentflow.knowledgebase.application.ChunkIndexingResult;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.ChunkToIndex;
import com.example.agentflow.knowledgebase.application.DocumentPayloadStore;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.knowledgebase.application.DocumentView;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the S10-13 indexing pipeline ({@link IngestionService}): DB-free, model-free and
 * clock-free — the {@code knowledgebase} boundary services are mocks, the parser/chunker/embedder
 * are real deterministic doubles. Covers the happy path, the idempotent-redelivery skip/reindex
 * policies, the permanent-failure recordings (extraction failure, dimensionality mismatch) and the
 * transient-fault propagation.
 */
class IngestionServiceTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();
  private static final UUID INGESTION_EVENT = UUID.randomUUID();
  private static final String PIPELINE_VERSION = "chunking:character_boundary:cs=500:ov=50";

  private DocumentQueryService queryService;
  private DocumentTransitionService transitionService;
  private ChunkIndexingService chunkIndexingService;
  private DocumentPayloadStore payloadStore;
  private DocumentParser parser;
  private OutboxService outboxService;
  private EmbeddingPort embeddingPort;

  private IngestionService skipService;
  private IngestionService updateService;

  @BeforeEach
  void setUp() {
    queryService = mock(DocumentQueryService.class);
    transitionService = mock(DocumentTransitionService.class);
    chunkIndexingService = mock(ChunkIndexingService.class);
    payloadStore = mock(DocumentPayloadStore.class);
    parser = mock(DocumentParser.class);
    outboxService = mock(OutboxService.class);
    embeddingPort =
        request ->
            new EmbeddingResponse(
                request.model(),
                request.texts().stream().map(text -> new float[768]).toList(),
                null);
    Chunker chunker = new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50));
    EmbeddingGenerationService embeddingService = new EmbeddingGenerationService(embeddingPort, 32);
    skipService =
        new IngestionService(
            queryService,
            transitionService,
            chunkIndexingService,
            payloadStore,
            parser,
            chunker,
            embeddingService,
            outboxService,
            RedeliveryPolicy.SKIP);
    updateService =
        new IngestionService(
            queryService,
            transitionService,
            chunkIndexingService,
            payloadStore,
            parser,
            chunker,
            embeddingService,
            outboxService,
            RedeliveryPolicy.UPDATE);
  }

  /** A document view in the given lifecycle state. */
  private static DocumentView view(String status) {
    return new DocumentView(
        DOCUMENT,
        TENANT,
        UUID.randomUUID(),
        "documents/" + TENANT + "/" + DOCUMENT,
        "sample.txt",
        "text/plain",
        1024L,
        "sha256-" + DOCUMENT,
        status,
        "v1",
        "nomic-embed-text");
  }

  /** Wire the happy-path boundary interactions for a claimable document. */
  private void givenClaimableDocument() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT))
        .thenReturn("hello world from the test document".getBytes(StandardCharsets.UTF_8));
    when(parser.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.of(
                1, "hello world from the test document", Map.of(), false, "tika:test"));
    when(chunkIndexingService.index(
            eq(TENANT), eq(DOCUMENT), eq(PIPELINE_VERSION), anyList(), anyBoolean(), isNull()))
        .thenReturn(new ChunkIndexingResult(1, 0));
  }

  @Test
  void a_queued_document_is_indexed_and_the_indexed_event_is_enqueued() {
    givenClaimableDocument();

    IngestionStatusDto result =
        skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("INDEXED");
    assertThat(result.documentId()).isEqualTo(DOCUMENT);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ChunkToIndex>> chunks = ArgumentCaptor.forClass(List.class);
    verify(chunkIndexingService)
        .index(
            eq(TENANT), eq(DOCUMENT), eq(PIPELINE_VERSION), chunks.capture(), eq(false), isNull());
    assertThat(chunks.getValue()).hasSize(1);
    assertThat(chunks.getValue().get(0).embedding()).hasSize(768);
    verify(transitionService).markIndexed(TENANT, DOCUMENT, 1);

    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    assertThat(event.getValue().topic()).isEqualTo("agentflow.document.indexed.v1");
    assertThat(event.getValue().eventKey()).isEqualTo(TENANT + ":" + DOCUMENT);
    assertThat(event.getValue().payloadJson())
        .contains("\"status\":\"INDEXED\"")
        .contains("\"chunkCount\":1")
        .contains(documentKnowledgeBaseId(event.getValue()));
    assertThat(event.getValue().headersJson())
        .contains("\"correlationId\":\"" + DOCUMENT + "\"")
        .contains("\"causationId\":\"" + INGESTION_EVENT + "\"");
  }

  private static String documentKnowledgeBaseId(OutboxEvent event) {
    return event.payloadJson().split("\"knowledgeBaseId\":\"")[1].split("\"")[0];
  }

  @Test
  void an_already_indexed_document_is_skipped_under_the_skip_policy() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("INDEXED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(false);

    IngestionStatusDto result =
        skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("INDEXED");
    verifyNoInteractions(payloadStore, parser, outboxService);
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
  }

  @Test
  void an_already_indexed_document_is_reindexed_under_the_update_policy() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("INDEXED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, true)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT))
        .thenReturn("hello world from the test document".getBytes(StandardCharsets.UTF_8));
    when(parser.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.of(
                1, "hello world from the test document", Map.of(), false, "tika:test"));
    when(chunkIndexingService.index(
            eq(TENANT), eq(DOCUMENT), eq(PIPELINE_VERSION), anyList(), anyBoolean(), isNull()))
        .thenReturn(new ChunkIndexingResult(1, 0));

    IngestionStatusDto result =
        updateService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("INDEXED");
    verify(chunkIndexingService)
        .index(eq(TENANT), eq(DOCUMENT), eq(PIPELINE_VERSION), anyList(), eq(true), isNull());
  }

  @Test
  void a_document_claimed_by_another_worker_is_skipped() {
    when(queryService.findForIndexing(TENANT, DOCUMENT))
        .thenReturn(Optional.of(view("PROCESSING")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(false);

    IngestionStatusDto result =
        skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("PROCESSING");
    verifyNoInteractions(payloadStore, parser, outboxService);
  }

  @Test
  void a_missing_or_cross_tenant_document_is_a_permanent_fault() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no tenant-owned document");
    verifyNoInteractions(transitionService, payloadStore, parser, outboxService);
  }

  @Test
  void a_failed_extraction_marks_the_document_failed_with_the_safe_code() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT)).thenReturn(new byte[] {1, 2, 3});
    when(parser.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.failed(
                new ExtractionError(ExtractionError.Code.CORRUPT, "bad file"), "tika:test"));

    IngestionStatusDto result =
        skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(transitionService).markFailed(TENANT, DOCUMENT, "EXTRACTION_CORRUPT", "bad file");
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
    verify(outboxService, never()).enqueue(any());
  }

  @Test
  void an_empty_extraction_completes_with_zero_chunks() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT)).thenReturn(new byte[0]);
    when(parser.parse(any(), any(), any())).thenReturn(ExtractionResult.empty("tika:test"));

    IngestionStatusDto result =
        skipService.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("INDEXED");
    verify(transitionService).markIndexed(TENANT, DOCUMENT, 0);
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
    ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outboxService).enqueue(event.capture());
    assertThat(event.getValue().payloadJson()).contains("\"chunkCount\":0");
  }

  @Test
  void a_mismatched_embedding_dimension_fails_the_document_and_never_reaches_the_index() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT))
        .thenReturn("hello world from the test document".getBytes(StandardCharsets.UTF_8));
    when(parser.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.of(
                1, "hello world from the test document", Map.of(), false, "tika:test"));
    EmbeddingPort wrongWidth =
        request ->
            new EmbeddingResponse(
                request.model(),
                request.texts().stream().map(text -> new float[100]).toList(),
                null);
    IngestionService service =
        new IngestionService(
            queryService,
            transitionService,
            chunkIndexingService,
            payloadStore,
            parser,
            new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50)),
            new EmbeddingGenerationService(wrongWidth, 32),
            outboxService,
            RedeliveryPolicy.SKIP);

    IngestionStatusDto result =
        service.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(transitionService)
        .markFailed(eq(TENANT), eq(DOCUMENT), eq("EMBEDDING_DIMENSION_MISMATCH"), any());
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
  }

  @Test
  void a_missing_payload_store_fails_loudly_at_call_time() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    IngestionService service =
        new IngestionService(
            queryService,
            transitionService,
            chunkIndexingService,
            null,
            parser,
            new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50)),
            new EmbeddingGenerationService(embeddingPort, 32),
            outboxService,
            RedeliveryPolicy.SKIP);

    assertThatThrownBy(
            () -> service.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no DocumentPayloadStore is configured");
  }

  @Test
  void a_transient_provider_fault_propagates_without_marking_anything() {
    when(queryService.findForIndexing(TENANT, DOCUMENT)).thenReturn(Optional.of(view("QUEUED")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT))
        .thenReturn("hello world from the test document".getBytes(StandardCharsets.UTF_8));
    when(parser.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.of(
                1, "hello world from the test document", Map.of(), false, "tika:test"));
    EmbeddingPort down =
        request -> {
          throw new RuntimeException("provider unavailable");
        };
    IngestionService service =
        new IngestionService(
            queryService,
            transitionService,
            chunkIndexingService,
            payloadStore,
            parser,
            new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50)),
            new EmbeddingGenerationService(down, 32),
            outboxService,
            RedeliveryPolicy.SKIP);

    assertThatThrownBy(
            () -> service.index(new IngestionEventKey(TENANT, DOCUMENT), INGESTION_EVENT))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("provider unavailable");
    verify(transitionService, never()).markIndexed(any(), any(), anyInt());
    verify(transitionService, never()).markFailed(any(), any(), any(), any());
    verify(outboxService, never()).enqueue(any());
  }
}
