package com.example.agentflow.knowledgebase.domain;

import java.util.Objects;

/**
 * The immutable document-upload limits, {@code S10-03}. A pure value object that wraps the three
 * configured bounds ({@code agentflow.upload.*}) and is handed to the {@code
 * knowledgebase.application.DocumentUploadValidator}. It is the domain's view of the limits, so the
 * validator depends on a domain value rather than on the Spring configuration class, and the limits
 * are validated here (in addition to at config-bind time) so a nonsensical limit fails at the
 * value, never silently accepting everything.
 *
 * <p>Constructed in {@code knowledgebase.application.DocumentUploadConfiguration} from {@link
 * com.example.agentflow.config.AgentFlowProperties.Upload}.
 */
public final class DocumentUploadLimits {

  private final int maxBytes;
  private final int maxFileNameLength;
  private final int maxMediaDurationSeconds;

  /**
   * @param maxBytes the maximum upload payload size in bytes (at least 1)
   * @param maxFileNameLength the maximum sanitised filename length (at least 1)
   * @param maxMediaDurationSeconds the maximum media upload duration in seconds (at least 1)
   */
  public DocumentUploadLimits(int maxBytes, int maxFileNameLength, int maxMediaDurationSeconds) {
    this.maxBytes = requirePositive(maxBytes, "maxBytes");
    this.maxFileNameLength = requirePositive(maxFileNameLength, "maxFileNameLength");
    this.maxMediaDurationSeconds =
        requirePositive(maxMediaDurationSeconds, "maxMediaDurationSeconds");
  }

  private static int requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be at least 1");
    }
    return value;
  }

  /**
   * @return the maximum upload payload size in bytes
   */
  public int getMaxBytes() {
    return maxBytes;
  }

  /**
   * @return the maximum sanitised filename length
   */
  public int getMaxFileNameLength() {
    return maxFileNameLength;
  }

  /**
   * @return the maximum media upload duration in seconds
   */
  public int getMaxMediaDurationSeconds() {
    return maxMediaDurationSeconds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocumentUploadLimits other)) {
      return false;
    }
    return maxBytes == other.maxBytes
        && maxFileNameLength == other.maxFileNameLength
        && maxMediaDurationSeconds == other.maxMediaDurationSeconds;
  }

  @Override
  public int hashCode() {
    return Objects.hash(maxBytes, maxFileNameLength, maxMediaDurationSeconds);
  }

  @Override
  public String toString() {
    return "DocumentUploadLimits[maxBytes="
        + maxBytes
        + ", maxFileNameLength="
        + maxFileNameLength
        + ", maxMediaDurationSeconds="
        + maxMediaDurationSeconds
        + "]";
  }
}
