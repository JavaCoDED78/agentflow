package com.example.agentflow.ingestion.application.parser;

import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.domain.ExtractionError;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;

/**
 * {@link DocumentParser} backed by Apache Tika (magic-byte detection) and {@code
 * tika-parser-pdf-module} (PDFBox for PDF text extraction).
 *
 * <p>This is the sole class in the codebase that imports a Tika / PDFBox API: it sits behind the
 * neutral {@link DocumentParser} port, so the domain and the ingestion worker never see a PDFBox or
 * Tika type.
 *
 * <p>Extraction is bounded on purpose. Two limits, both configurable and enforced here (never as
 * magic numbers): {@code maxPages} caps the number of pages PDFBox reads from a PDF (pages beyond
 * it are not read), and {@code maxChars} caps the number of characters retained from any document.
 * Every failure is classified into an {@link ExtractionResult} state rather than thrown, so the
 * ingest side keeps processing:
 *
 * <ul>
 *   <li>a corrupt / malformed PDF &rarr; {@link ExtractionResult.Status#FAILED} + {@link
 *       ExtractionError.Code#CORRUPT} (PDFBox's {@code load} does not repair, so corruption is
 *       observable rather than masked);
 *   <li>a password-protected PDF &rarr; {@link ExtractionResult.Status#FAILED} + {@link
 *       ExtractionError.Code#ENCRYPTED} (the document loads structurally but stays encrypted
 *       without the key, so it is detected via {@code PDDocument#isEncrypted()});
 *   <li>an empty payload &rarr; {@link ExtractionResult.Status#EMPTY}.
 * </ul>
 *
 * <p>Database-free and free of any {@code DataSource} / Kafka dependency, so it is safe in every
 * profile (including the {@code api} role that never starts the ingestion worker).
 */
public final class TikaDocumentParser implements DocumentParser {

  private final Tika tika;
  private final int maxPages;
  private final int maxChars;

  /**
   * @param maxPages the maximum number of pages read from a PDF (must be &gt; 0)
   * @param maxChars the maximum number of characters retained from the extracted text (must be &gt;
   *     0)
   */
  public TikaDocumentParser(int maxPages, int maxChars) {
    if (maxPages <= 0) {
      throw new IllegalArgumentException("maxPages must be > 0, was " + maxPages);
    }
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be > 0, was " + maxChars);
    }
    this.maxPages = maxPages;
    this.maxChars = maxChars;
    this.tika = new Tika();
  }

  @Override
  public ExtractionResult parse(String originalName, String contentType, byte[] payload) {
    if (payload == null || payload.length == 0) {
      return ExtractionResult.empty();
    }
    // Detect from the payload's leading bytes (magic bytes), falling back to the filename hint. The
    // client-supplied content type is NOT trusted for this decision (invariant 1): a payload that
    // misrepresents itself is handled by whatever its real leading bytes say.
    String detected =
        tika.detect(
            payload, (originalName == null || originalName.isBlank()) ? contentType : originalName);
    if ("application/pdf".equals(detected)) {
      return parsePdf(payload);
    }
    return parseText(payload);
  }

  private ExtractionResult parsePdf(byte[] payload) {
    PDDocument doc = null;
    try {
      // PDDocument.load(byte[]) does NOT repair: a corrupt document throws instead of being
      // silently
      // fixed, so corruption is observable (CORRUPT) rather than masked.
      doc = PDDocument.load(payload);
      // A password-protected PDF loads structurally but stays encrypted without the key; report
      // ENCRYPTED rather than extracting meaningless, undecrypted content.
      if (doc.isEncrypted()) {
        return ExtractionResult.failed(
            new ExtractionError(
                ExtractionError.Code.ENCRYPTED,
                "PDF is password protected and could not be decrypted"));
      }
      int totalPages = doc.getNumberOfPages();
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(Math.min(totalPages, maxPages));
      String fullText = stripper.getText(doc);
      boolean charLimited = fullText != null && fullText.length() > maxChars;
      String text = boundChars(fullText);
      int pageCount = Math.min(totalPages, maxPages);
      return ExtractionResult.of(
          pageCount, text, pdfMetadata(doc), totalPages > maxPages || charLimited);
    } catch (Exception e) {
      if (isEncryptionFailure(e)) {
        return ExtractionResult.failed(
            new ExtractionError(
                ExtractionError.Code.ENCRYPTED,
                "PDF is password protected and could not be decrypted"));
      }
      return ExtractionResult.failed(
          new ExtractionError(
              ExtractionError.Code.CORRUPT, "PDF could not be parsed: " + shortMessage(e)));
    } finally {
      if (doc != null) {
        try {
          doc.close();
        } catch (IOException ignored) {
          // best-effort close on the close path
        }
      }
    }
  }

  /**
   * Text-like content types are already text, so decode the bytes directly instead of routing
   * through a Tika parser: no parser module is required and the retained characters map 1:1 to the
   * source. Bounded by {@code maxChars} so a long input reports {@link
   * ExtractionResult.Status#EXTRACTED} with {@link ExtractionResult#truncated()} true instead of
   * silently dropping content.
   */
  private ExtractionResult parseText(byte[] payload) {
    String raw = new String(payload, StandardCharsets.UTF_8).replace('\u0000', ' ').trim();
    boolean truncated = raw.length() > maxChars;
    return ExtractionResult.of(0, boundChars(raw), Map.of(), truncated);
  }

  /**
   * A load failure that stems from encryption (an unknown / wrong password, or an unsupported
   * cipher) rather than from genuine corruption. PDFBox throws several different exception types
   * across versions ({@code InvalidPasswordException}, {@code PDFBoxCipherException}, ...), so the
   * classification inspects the class name and the message, not just one type.
   */
  private static boolean isEncryptionFailure(Throwable e) {
    String name = e.getClass().getName().toLowerCase();
    if (name.contains("invalidpassword")
        || name.contains("cipher")
        || name.contains("decrypt")
        || name.contains("encryption")) {
      return true;
    }
    String message = String.valueOf(e.getMessage());
    if (message == null || message.isBlank()) {
      return false;
    }
    String lower = message.toLowerCase();
    return lower.contains("password")
        || lower.contains("encrypt")
        || lower.contains("cipher")
        || lower.contains("decrypt");
  }

  private String boundChars(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  private Map<String, String> pdfMetadata(PDDocument doc) {
    PDDocumentInformation info = doc.getDocumentInformation();
    if (info == null) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    String title = info.getTitle();
    if (title != null && !title.isBlank()) {
      out.put("title", title);
    }
    String author = info.getAuthor();
    if (author != null && !author.isBlank()) {
      out.put("author", author);
    }
    Calendar creation = info.getCreationDate();
    if (creation != null) {
      out.put("creation_date", creation.toString());
    }
    return out;
  }

  /**
   * Reduce an exception to a safe one-liner: class name + first line of the message, capped, no
   * stack trace and no binary fragment.
   */
  private static String shortMessage(Throwable e) {
    String cls = e.getClass().getSimpleName();
    String msg = String.valueOf(e.getMessage());
    if (msg == null || msg.isBlank()) {
      return cls;
    }
    msg = msg.replace('\r', ' ').replace('\n', ' ').replace('\u0000', ' ').trim();
    int cap = 200;
    return (msg.length() <= cap ? msg : msg.substring(0, cap) + "\u2026");
  }
}
