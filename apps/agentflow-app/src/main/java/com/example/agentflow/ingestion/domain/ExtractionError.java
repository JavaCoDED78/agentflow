package com.example.agentflow.ingestion.domain;

import java.util.Objects;

/**
 * A classified, safe description of why a document could not be fully extracted.
 *
 * <p>The {@link #code()} is a stable, machine-readable discriminator (persisted verbatim as the
 * document {@code error_code}, so a query can distinguish a corrupt PDF from an encrypted one); the
 * {@link #summary()} is a human-readable, redacted one-liner (persisted as {@code error_summary})
 * that never carries the document body, binary bytes, a stack trace or any secret.
 */
public final class ExtractionError {

  /**
   * Stable classification of an extraction failure. The values are an internal contract: they are
   * persisted on the document row and must change only by adding a new value, never by renaming or
   * reusing one (the {@code S10-08} durability of the error code).
   */
  public enum Code {
    /** The document is password protected and could not be decrypted. */
    ENCRYPTED,
    /** The document is corrupt / malformed and could not be read. */
    CORRUPT
  }

  private final Code code;
  private final String summary;

  /**
   * @param code the classification (never {@code null})
   * @param summary a human-readable one-liner; blank or {@code null} collapses to {@link
   *     Code#name()}
   */
  public ExtractionError(Code code, String summary) {
    this.code = Objects.requireNonNull(code, "code");
    this.summary = (summary == null || summary.isBlank()) ? code.name() : sanitize(summary);
  }

  /**
   * @return the stable classification (never {@code null}).
   */
  public Code code() {
    return code;
  }

  /**
   * @return the redacted one-liner summary (never {@code null}, never a secret).
   */
  public String summary() {
    return summary;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ExtractionError other)) {
      return false;
    }
    return code == other.code && Objects.equals(summary, other.summary);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, summary);
  }

  @Override
  public String toString() {
    return "ExtractionError[code=" + code + ", summary=" + summary + "]";
  }

  /**
   * Reduce a raw exception message to a safe one-liner: strip line breaks and NUL bytes (so binary
   * fragments cannot forge log lines or carry a secret), trim, and cap length. Never a stack trace.
   */
  private static String sanitize(String in) {
    String msg = in.replace('\r', ' ').replace('\n', ' ').replace('\u0000', ' ').trim();
    int cap = 500;
    return msg.length() <= cap ? msg : msg.substring(0, cap) + "\u2026";
  }
}
