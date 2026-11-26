package com.example.agentflow.ingestion.application.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.application.IngestionEventKey;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.IngestionStatusDto;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.RedeliveryPolicy;
import com.example.agentflow.ingestion.domain.Strategy;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.DocumentPayloadStore;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.knowledgebase.application.DocumentUploadValidationException;
import com.example.agentflow.knowledgebase.application.DocumentUploadValidator;
import com.example.agentflow.knowledgebase.application.DocumentView;
import com.example.agentflow.knowledgebase.application.ValidatedUpload;
import com.example.agentflow.knowledgebase.domain.DocumentUploadLimits;
import com.example.agentflow.messaging.application.OutboxService;
import com.example.agentflow.messaging.domain.OutboxEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The S14-10 upload / parser abuse regression pack: every unsupported or abusive input is rejected
 * or classified before it can become {@code INDEXED} and never bypasses a resource limit.
 *
 * <p>The pack spans the two resource gates an upload actually passes through — the upload boundary
 * ({@link DocumentUploadValidator}, the only reject-before-persist gate) and the parser boundary
 * ({@link TikaDocumentParser} / {@link BoundedDocumentParser}, the only extract gate) — plus the
 * pipeline end. The ingestion pipeline is driven with its real extract path (no fake parser) and
 * mocked {@code knowledgebase} boundary, so a FAILED outcome is pinned as <em>actually never
 * INDEXED</em>: no {@code markIndexed}, no {@code document.indexed} outbox record, no chunks.
 *
 * <p><b>What the pack is not.</b> A fake parser is used only for the pipeline-tier TIMEOUT pin,
 * which would otherwise need a real slow PDF — a wall-clock timing pin is already proven in {@code
 * BoundedDocumentParserTest}. The corrupt / encrypted / oversized rows all use the real parser, so
 * the classification under test is the production classification.
 *
 * <p>The abuse rows are keyed off the plan's acceptance criterion: {@code
 * AGENTFLOW_ARCHITECTURE_AND_PLAN.md#S14-10} — "не поддерживаемый файл не становится INDEXED и не
 * обходит resource limits".
 */
class UploadParserAbuseSecurityPackTest {

  private static final String PIPELINE_VERSION = "chunking:character_boundary:cs=500:ov=50";

  // ── Upload boundary (pure validator, no I/O) ─────────────────────────────

  @Nested
  @DisplayName("upload boundary (S10-03): reject before anything persists")
  class UploadBoundary {

    private static final DocumentUploadLimits LIMITS = new DocumentUploadLimits(1024, 255, 300);

    private final DocumentUploadValidator validator = new DocumentUploadValidator(LIMITS);

    @Test
    @DisplayName("an oversized payload is rejected, nothing is returned")
    void oversized_payload_is_rejected() {
      byte[] pdf = ("%PDF-1.7\n% bytes").getBytes(StandardCharsets.US_ASCII);
      byte[] oversized = new byte[LIMITS.getMaxBytes() + 1];
      System.arraycopy(pdf, 0, oversized, 0, pdf.length);

      assertThatThrownBy(() -> validator.validateUpload("report.pdf", "application/pdf", oversized))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("exceeds the maximum");
    }

    @Test
    @DisplayName("a MIME type outside the allowlist is rejected before any bytes are seen")
    void unsupported_mime_is_rejected() {
      byte[] bytes = "not pdf at all".getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(
              () -> validator.validateUpload("payload.run", "application/x-msdownload", bytes))
          .isInstanceOf(DocumentUploadValidationException.class);
    }

    @Test
    @DisplayName("a payload whose magic bytes contradict the declared type is rejected")
    void magic_byte_mismatch_is_rejected() {
      // Declared PDF but the leading bytes are a plain-text payload: the type lies, the bytes win.
      byte[] text = "plain text, clearly not a pdf".getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(() -> validator.validateUpload("fake.pdf", "application/pdf", text))
          .isInstanceOf(DocumentUploadValidationException.class);
    }

    @Test
    @DisplayName("a traversal filename never survives: only the safe base name is stored")
    void traversal_filename_is_reduced_to_a_safe_base_name() {
      byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

      ValidatedUpload result = validator.validateUpload("../../etc/passwd", "application/pdf", pdf);

      assertThat(result.fileName()).isEqualTo("passwd");
      assertThat(result.fileName()).doesNotContain("/", "\\", "..");
    }

    @Test
    @DisplayName("a traversal-only filename is rejected outright")
    void traversal_only_filename_is_rejected() {
      byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

      assertThatThrownBy(() -> validator.validateUpload("..", "application/pdf", pdf))
          .isInstanceOf(DocumentUploadValidationException.class);
    }
  }

  // ── Parser boundary (real Tika/PDFBox) ────────────────────────────────────

  @Nested
  @DisplayName(
      "parser boundary (S10-07/S14-10): unsupported bytes never reach a successful extraction")
  class ParserBoundary {

    @Test
    @DisplayName("a corrupt PDF is classified CORRUPT, never EXTRACTED")
    void corrupt_pdf_is_rejected() {
      TikaDocumentParser parser = new TikaDocumentParser(100, 100_000);

      ExtractionResult result = parser.parse("corrupt.pdf", "application/pdf", corruptPdf());

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
      assertThat(result.error().code()).isEqualTo(ExtractionError.Code.CORRUPT);
      assertThat(result.isSuccess()).isFalse();
      assertThat(result.truncated()).isFalse();
    }

    @Test
    @DisplayName("an encrypted PDF is classified ENCRYPTED, never EXTRACTED")
    void encrypted_pdf_is_rejected() throws Exception {
      TikaDocumentParser parser = new TikaDocumentParser(100, 100_000);

      ExtractionResult result =
          parser.parse("locked.pdf", "application/pdf", encryptedPdf("S14-10"));

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
      assertThat(result.error().code()).isEqualTo(ExtractionError.Code.ENCRYPTED);
      assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("excessive extracted text is capped at the configured limit, never beyond")
    void excessive_extracted_text_is_capped() {
      StringBuilder text = new StringBuilder();
      while (text.length() < 5_000) {
        text.append("0123456789");
      }
      byte[] payload = text.toString().getBytes(StandardCharsets.UTF_8);
      TikaDocumentParser parser = new TikaDocumentParser(100, 1_000);

      ExtractionResult result = parser.parse("huge.txt", "text/plain", payload);

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
      assertThat(result.truncated()).isTrue();
      assertThat(result.charCount()).isLessThanOrEqualTo(1_000);
      assertThat(result.text().length()).isLessThanOrEqualTo(1_000);
    }
  }

  // ── Bounded parser (S14-10) ─────────────────────────────────────────────────

  @Nested
  @DisplayName("bounded parser: a runaway extraction returns a typed TIMEOUT, never a result")
  class BoundedParser {

    @Test
    @DisplayName("a hanging extraction is abandoned at the bound and reported as TIMEOUT")
    void runaway_extraction_is_abandoned() throws Exception {
      CountDownLatch release = new CountDownLatch(1);
      DocumentParser hanging =
          (name, contentType, payload) -> {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              // abandoned by the bounded wrapper; the thread exits on its own
            }
            return ExtractionResult.of(1, "too late", Map.of(), false, "tika:test");
          };

      BoundedDocumentParser parser = new BoundedDocumentParser(hanging, Duration.ofMillis(150));
      try {
        ExtractionResult result = parser.parse("slow.pdf", "application/pdf", new byte[] {1});

        assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
        assertThat(result.error().code()).isEqualTo(ExtractionError.Code.TIMEOUT);
        assertThat(result.error().summary()).contains("150ms");
        assertThat(result.error().summary()).doesNotContain("too late");
        assertThat(result.isSuccess()).isFalse();
      } finally {
        release.countDown();
        parser.close();
      }
    }
  }

  // ── Pipeline end: the abused document is never INDEXED ──────────────────────

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();
  private static final String OBJECT_KEY = "documents/" + TENANT + "/" + DOCUMENT;

  private DocumentQueryService queryService;
  private DocumentTransitionService transitionService;
  private ChunkIndexingService chunkIndexingService;
  private DocumentPayloadStore payloadStore;
  private OutboxService outboxService;

  private void givenIndexableDocument(byte[] payload, String fileName, String mimeType) {
    queryService = mock(DocumentQueryService.class);
    transitionService = mock(DocumentTransitionService.class);
    chunkIndexingService = mock(ChunkIndexingService.class);
    payloadStore = mock(DocumentPayloadStore.class);
    outboxService = mock(OutboxService.class);
    when(queryService.findForIndexing(TENANT, DOCUMENT))
        .thenReturn(
            Optional.of(
                new DocumentView(
                    DOCUMENT,
                    TENANT,
                    UUID.randomUUID(),
                    OBJECT_KEY,
                    fileName,
                    mimeType,
                    payload.length,
                    "sha256-" + DOCUMENT,
                    "QUEUED",
                    PIPELINE_VERSION,
                    "nomic-embed-text")));
    when(transitionService.claimForIndexing(TENANT, DOCUMENT, false)).thenReturn(true);
    when(payloadStore.fetch(TENANT, DOCUMENT)).thenReturn(payload);
  }

  private IngestionService pipeline(DocumentParser parser) {
    Chunker chunker = new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50));
    EmbeddingPort fakeEmbed =
        request ->
            new EmbeddingResponse(
                request.model(),
                request.texts().stream().map(text -> new float[768]).toList(),
                null);
    EmbeddingGenerationService embeddingService = new EmbeddingGenerationService(fakeEmbed, 32);
    return new IngestionService(
        queryService,
        transitionService,
        chunkIndexingService,
        payloadStore,
        parser,
        chunker,
        embeddingService,
        outboxService,
        RedeliveryPolicy.SKIP);
  }

  @Test
  @DisplayName("a corrupt PDF never becomes INDEXED (the real parser, the real pipeline)")
  void corrupt_pdf_never_becomes_indexed() {
    givenIndexableDocument(corruptPdf(), "corrupt.pdf", "application/pdf");
    DocumentParser realParser = new TikaDocumentParser(100, 100_000);

    IngestionStatusDto result =
        pipeline(realParser).index(new IngestionEventKey(TENANT, DOCUMENT), UUID.randomUUID());

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.status()).isNotEqualTo("INDEXED");
    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(transitionService)
        .markFailed(eq(TENANT), eq(DOCUMENT), eq("EXTRACTION_CORRUPT"), summary.capture());
    assertThat(summary.getValue()).isNotBlank();
    verify(transitionService, never()).markIndexed(any(), any(), anyInt(), anyString());
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
    verify(outboxService, never()).enqueue(any(OutboxEvent.class));
  }

  @Test
  @DisplayName("an encrypted PDF never becomes INDEXED (the real parser, the real pipeline)")
  void encrypted_pdf_never_becomes_indexed() throws Exception {
    givenIndexableDocument(encryptedPdf("S14-10"), "locked.pdf", "application/pdf");
    DocumentParser realParser = new TikaDocumentParser(100, 100_000);

    IngestionStatusDto result =
        pipeline(realParser).index(new IngestionEventKey(TENANT, DOCUMENT), UUID.randomUUID());

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.status()).isNotEqualTo("INDEXED");
    ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(transitionService)
        .markFailed(eq(TENANT), eq(DOCUMENT), eq("EXTRACTION_ENCRYPTED"), summary.capture());
    assertThat(summary.getValue()).isNotBlank();
    verify(transitionService, never()).markIndexed(any(), any(), anyInt(), anyString());
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
    verify(outboxService, never()).enqueue(any(OutboxEvent.class));
  }

  @Test
  @DisplayName(
      "a timeout extraction never becomes INDEXED (the S14-10 classification, the real pipeline)")
  void timeout_extraction_never_becomes_indexed() {
    givenIndexableDocument(new byte[] {1}, "pathological.pdf", "application/pdf");
    DocumentParser stub = mock(DocumentParser.class);
    when(stub.parse(any(), any(), any()))
        .thenReturn(
            ExtractionResult.failed(
                new ExtractionError(
                    ExtractionError.Code.TIMEOUT, "extraction exceeded the time limit"),
                "bounded-document-parser"));

    IngestionStatusDto result =
        pipeline(stub).index(new IngestionEventKey(TENANT, DOCUMENT), UUID.randomUUID());

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.status()).isNotEqualTo("INDEXED");
    verify(transitionService)
        .markFailed(eq(TENANT), eq(DOCUMENT), eq("EXTRACTION_TIMEOUT"), anyString());
    verify(transitionService, never()).markIndexed(any(), any(), anyInt(), anyString());
    verify(chunkIndexingService, never())
        .index(any(), any(), any(), anyList(), anyBoolean(), any());
    verify(outboxService, never()).enqueue(any(OutboxEvent.class));
  }

  // ── Fixtures (S10-07 shape) ────────────────────────────────────────────────────

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

  private static byte[] encryptedPdf(String text) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      writeText(doc, text);
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner", "", new AccessPermission());
      doc.protect(policy);
      return toBytes(doc);
    }
  }

  private static void writeText(PDDocument doc, String text) throws Exception {
    try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(0))) {
      cs.beginText();
      cs.setFont(PDType1Font.HELVETICA, 12);
      cs.newLineAtOffset(72, 700);
      cs.showText(text);
      cs.endText();
    }
  }

  private static byte[] toBytes(PDDocument doc) throws Exception {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.save(out);
      return out.toByteArray();
    }
  }
}
