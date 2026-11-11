package com.example.agentflow.ingestion.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of extracting text and metadata from a document payload that has already passed
 * upload validation (see {@code knowledgebase.domain.DocumentContentType}).
 *
 * <p>The outcome is one of three <b>predictable</b> states, so the ingestion worker can always
 * decide what to do next without inspecting a thrown exception:
 *
 * <ul>
 *   <li><b>{@link Status#EXTRACTED}</b> &mdash; text and metadata were extracted. {@link
 *       #truncated()} is {@code true} when a page or character limit was hit, i.e. the result is a
 *       partial extraction of a larger document (a partial success, not a failure).
 *   <li><b>{@link Status#EMPTY}</b> &mdash; the payload carried no extractable content (a blank,
 *       zero-byte or zero-page document). This is not an error. {@link Status#FAILED}</b> &mdash;
 *       the payload could not be parsed; {@link #error()} carries the classified {@link
 *       ExtractionError} ({@code CORRUPT} or {@code ENCRYPTED}).
 * </ul>
 *
 * <p>Nothing here is a thrown fault: the parser maps every failure to a {@link Status} + {@link
 * ExtractionError} so a corrupt or encrypted PDF is a <i>result</i>, not an interruption, and the
 * ingest side keeps processing it (at-least-once, never exactly-once &mdash; invariant 3).
 */
public final class ExtractionResult {

  /** The outcome of an extraction. */
  public enum Status {
    /** Text and metadata were extracted (possibly truncated at the configured limit). */
    EXTRACTED,
    /** The payload carried no extractable content (blank / zero-byte / zero-page document). */
    EMPTY,
    /** The payload could not be parsed (see {@link #error()}). */
    FAILED
  }

  private final Status status;
  private final int pageCount;
  private final int charCount;
  private final String text;
  private final Map<String, String> metadata;
  private final boolean truncated;
  private final ExtractionError error;

  private ExtractionResult(
      Status status,
      int pageCount,
      String text,
      Map<String, String> metadata,
      boolean truncated,
      ExtractionError error) {
    this.status = Objects.requireNonNull(status, "status");
    this.pageCount = pageCount;
    this.charCount = (text == null) ? 0 : text.length();
    this.text = (text == null) ? "" : text;
    this.metadata =
        (metadata == null) ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    this.truncated = truncated;
    this.error = error;
  }

  /**
   * An {@link Status#EXTRACTED} result.
   *
   * @param pageCount the number of pages read from the document (0 for non-PDF content)
   * @param text the extracted text, already bounded by the caller's limit
   * @param metadata the safe document metadata captured for diagnostics
   * @param truncated whether a page / character limit was hit, i.e. this is a partial extraction
   * @return an extracted result (partial when {@code truncated})
   */
  public static ExtractionResult of(
      int pageCount, String text, Map<String, String> metadata, boolean truncated) {
    return new ExtractionResult(Status.EXTRACTED, pageCount, text, metadata, truncated, null);
  }

  /** An {@link Status#EMPTY} result: no extractable content, not an error. */
  public static ExtractionResult empty() {
    return new ExtractionResult(Status.EMPTY, 0, "", Map.of(), false, null);
  }

  /**
   * A {@link Status#FAILED} result.
   *
   * @param error the classified extraction error
   * @return a failed result carrying the error code and a safe summary
   */
  public static ExtractionResult failed(ExtractionError error) {
    return new ExtractionResult(
        Status.FAILED, 0, "", Map.of(), false, Objects.requireNonNull(error, "error"));
  }

  /**
   * @return the outcome ({@link Status#EXTRACTED}, {@link Status#EMPTY} or {@link Status#FAILED}).
   */
  public Status status() {
    return status;
  }

  /**
   * @return the number of pages read from the document (0 for non-PDF content).
   */
  public int pageCount() {
    return pageCount;
  }

  /**
   * @return the number of characters actually extracted (the length of {@link #text()}).
   */
  public int charCount() {
    return charCount;
  }

  /**
   * @return the extracted text, empty when nothing was extracted.
   */
  public String text() {
    return text;
  }

  /**
   * @return an unmodifiable, safe subset of the document metadata (never a secret).
   */
  public Map<String, String> metadata() {
    return metadata;
  }

  /**
   * @return {@code true} when a page / character limit was hit and the extraction is partial.
   */
  public boolean truncated() {
    return truncated;
  }

  /**
   * @return the classified error, non-null only when {@link #status()} is {@link Status#FAILED}.
   */
  public ExtractionError error() {
    return error;
  }

  /**
   * @return {@code true} when the text and metadata were extracted.
   */
  public boolean isSuccess() {
    return status == Status.EXTRACTED;
  }

  /**
   * @return {@code true} when the payload carried no extractable content.
   */
  public boolean isEmpty() {
    return status == Status.EMPTY;
  }

  /**
   * @return {@code true} when the payload could not be parsed.
   */
  public boolean isFailure() {
    return status == Status.FAILED;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ExtractionResult other)) {
      return false;
    }
    return pageCount == other.pageCount
        && charCount == other.charCount
        && truncated == other.truncated
        && status == other.status
        && Objects.equals(text, other.text)
        && Objects.equals(metadata, other.metadata)
        && Objects.equals(error, other.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(status, pageCount, charCount, text, metadata, truncated, error);
  }

  @Override
  public String toString() {
    return "ExtractionResult[status="
        + status
        + ", pageCount="
        + pageCount
        + ", charCount="
        + charCount
        + ", truncated="
        + truncated
        + (error == null ? "" : ", error=" + error)
        + "]";
  }
}
