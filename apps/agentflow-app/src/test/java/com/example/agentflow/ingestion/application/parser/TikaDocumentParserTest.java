package com.example.agentflow.ingestion.application.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for the PDF / text extraction adapter (S10-07).
 *
 * <p>The PDF fixtures are generated with PDFBox (on the test classpath via {@code
 * tika-parser-pdf}): a <b>normal</b> page, a <b>multi-page</b> document and a
 * <b>password-protected</b> page. The <b>empty</b> payload is a zero-byte array and the
 * <b>corrupt</b> PDF is a {@code %PDF} header with a non-existent cross-reference, so {@code
 * PDDocument.load(byte[])} (which does not repair) rejects it deterministically. These are the four
 * states the S10-07 acceptance calls for: normal / empty / corrupt / encrypted.
 */
class TikaDocumentParserTest {

  private static final int MAX_PAGES = 100;
  private static final int MAX_CHARS = 100_000;

  private TikaDocumentParser parser;

  @BeforeEach
  void setUp() {
    parser = new TikaDocumentParser(MAX_PAGES, MAX_CHARS);
  }

  @Test
  @DisplayName("normal single-page PDF yields the text, the page count and no error")
  void normal_pdf_extracts_text_and_page_count() throws Exception {
    ExtractionResult result =
        parser.parse("sample.pdf", "application/pdf", singlePagePdf("S10-07"));

    assertThat(result.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
    assertThat(result.pageCount()).isEqualTo(1);
    assertThat(result.text()).contains("S10-07");
    assertThat(result.truncated()).isFalse();
    assertThat(result.error()).isNull();
  }

  @Test
  @DisplayName("a blank payload yields EMPTY, not an error")
  void blank_payload_is_empty() {
    ExtractionResult result = parser.parse("empty.pdf", "application/pdf", new byte[0]);

    assertThat(result.status()).isEqualTo(ExtractionResult.Status.EMPTY);
    assertThat(result.isEmpty()).isTrue();
    assertThat(result.error()).isNull();
  }

  @Test
  @DisplayName("plain text is extracted with page count 0")
  void plain_text_extracted() {
    byte[] text = "hello world\nsecond line".getBytes(StandardCharsets.UTF_8);
    ExtractionResult result = parser.parse("notes.txt", "text/plain", text);

    assertThat(result.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
    assertThat(result.pageCount()).isEqualTo(0);
    assertThat(result.text()).contains("hello world");
  }

  @Nested
  @DisplayName("limits (page / character)")
  class Limits {

    @Test
    @DisplayName("pages beyond maxPages are not read and truncated reports a partial extraction")
    void truncates_to_max_pages() throws Exception {
      TikaDocumentParser small = new TikaDocumentParser(5, MAX_CHARS);
      ExtractionResult result = small.parse("many.pdf", "application/pdf", multiPagePdf(10));

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
      assertThat(result.pageCount()).isEqualTo(5);
      assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("a document within the limits is not truncated")
    void within_limits_is_not_truncated() throws Exception {
      ExtractionResult result = parser.parse("one.pdf", "application/pdf", singlePagePdf("short"));

      assertThat(result.pageCount()).isEqualTo(1);
      assertThat(result.truncated()).isFalse();
    }
  }

  @Nested
  @DisplayName("classified failures")
  class Failures {

    @Test
    @DisplayName("a corrupt PDF (bad cross-reference) is classified CORRUPT, never recovered")
    void corrupt_pdf_is_corrupt() {
      ExtractionResult result = parser.parse("bad.pdf", "application/pdf", corruptPdf());

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
      assertThat(result.isFailure()).isTrue();
      assertThat(result.error().code()).isEqualTo(ExtractionError.Code.CORRUPT);
      assertThat(result.error().summary()).isNotBlank();
    }

    @Test
    @DisplayName("a password-protected PDF is classified ENCRYPTED")
    void encrypted_pdf_is_encrypted() throws Exception {
      ExtractionResult result =
          parser.parse("locked.pdf", "application/pdf", encryptedPdf("secret"));

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
      assertThat(result.error().code()).isEqualTo(ExtractionError.Code.ENCRYPTED);
      assertThat(result.error().summary()).isNotBlank();
    }
  }

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("non-positive limits are rejected by the constructor")
    void non_positive_limits_rejected() {
      assertThatThrownBy(() -> new TikaDocumentParser(0, MAX_CHARS))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new TikaDocumentParser(MAX_PAGES, 0))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("extraction diagnostics (S10-08)")
  class Diagnostics {

    @Test
    @DisplayName("a successful extraction reports the parser version, page and char counts")
    void parser_version_reported_on_success() throws Exception {
      ExtractionResult result =
          parser.parse("sample.pdf", "application/pdf", singlePagePdf("S10-08"));

      assertThat(result.isSuccess()).isTrue();
      assertThat(result.parserVersion()).isNotBlank();
      assertThat(result.pageCount()).isEqualTo(1);
      assertThat(result.charCount()).isGreaterThan(0);
      assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("a partial extraction (page limit hit) is a partial success, not a failure")
    void partial_success_carries_parser_version() throws Exception {
      TikaDocumentParser small = new TikaDocumentParser(5, MAX_CHARS);
      ExtractionResult result = small.parse("many.pdf", "application/pdf", multiPagePdf(10));

      assertThat(result.isPartialSuccess()).isTrue();
      assertThat(result.error()).isNull();
      assertThat(result.parserVersion()).isNotBlank();
    }

    @Test
    @DisplayName("a failure reports the parser version and a summary without raw file fragments")
    void failure_reports_parser_version_and_safe_summary() {
      ExtractionResult corrupt = parser.parse("bad.pdf", "application/pdf", corruptPdf());

      assertThat(corrupt.isFailure()).isTrue();
      assertThat(corrupt.error().code()).isEqualTo(ExtractionError.Code.CORRUPT);
      assertThat(corrupt.parserVersion()).isNotBlank();

      String summary = corrupt.error().summary();
      assertThat(summary).isNotBlank();
      assertThat(summary).doesNotContain("\n");
      assertThat(summary).doesNotContain("\r");
      assertThat(summary).hasSizeLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("an empty payload reports the parser version and is not an error")
    void empty_reports_parser_version() {
      ExtractionResult empty = parser.parse("empty.pdf", "application/pdf", new byte[0]);

      assertThat(empty.isEmpty()).isTrue();
      assertThat(empty.parserVersion()).isNotBlank();
      assertThat(empty.error()).isNull();
    }
  }

  // --- PDF fixtures -------------------------------------------------------

  private static byte[] singlePagePdf(String text) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      writeText(doc, text);
      return toBytes(doc);
    }
  }

  private static byte[] multiPagePdf(int pages) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        doc.addPage(new PDPage(PDRectangle.A4));
      }
      writeText(doc, "one");
      return toBytes(doc);
    }
  }

  private static byte[] encryptedPdf(String text) throws Exception {
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage(PDRectangle.A4));
      writeText(doc, text);
      // Owner password set, user password empty: the document loads structurally but stays
      // encrypted without the key, so isEncrypted()==true (S10-07 accepts this as ENCRYPTED).
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner", "", new AccessPermission());
      doc.protect(policy);
      return toBytes(doc);
    }
  }

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
