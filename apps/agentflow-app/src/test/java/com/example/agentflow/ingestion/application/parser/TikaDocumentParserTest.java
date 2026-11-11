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
  void normalPdfExtractsTextAndPageCount() throws Exception {
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
  void blankPayloadIsEmpty() {
    ExtractionResult result = parser.parse("empty.pdf", "application/pdf", new byte[0]);

    assertThat(result.status()).isEqualTo(ExtractionResult.Status.EMPTY);
    assertThat(result.isEmpty()).isTrue();
    assertThat(result.error()).isNull();
  }

  @Test
  @DisplayName("plain text is extracted with page count 0")
  void plainTextExtracted() {
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
    void truncatesToMaxPages() throws Exception {
      TikaDocumentParser small = new TikaDocumentParser(5, MAX_CHARS);
      ExtractionResult result = small.parse("many.pdf", "application/pdf", multiPagePdf(10));

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.EXTRACTED);
      assertThat(result.pageCount()).isEqualTo(5);
      assertThat(result.truncated()).isTrue();
    }

    @Test
    @DisplayName("a document within the limits is not truncated")
    void withinLimitsIsNotTruncated() throws Exception {
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
    void corruptPdfIsCorrupt() {
      ExtractionResult result = parser.parse("bad.pdf", "application/pdf", corruptPdf());

      assertThat(result.status()).isEqualTo(ExtractionResult.Status.FAILED);
      assertThat(result.isFailure()).isTrue();
      assertThat(result.error().code()).isEqualTo(ExtractionError.Code.CORRUPT);
      assertThat(result.error().summary()).isNotBlank();
    }

    @Test
    @DisplayName("a password-protected PDF is classified ENCRYPTED")
    void encryptedPdfIsEncrypted() throws Exception {
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
    void nonPositiveLimitsRejected() {
      assertThatThrownBy(() -> new TikaDocumentParser(0, MAX_CHARS))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new TikaDocumentParser(MAX_PAGES, 0))
          .isInstanceOf(IllegalArgumentException.class);
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
