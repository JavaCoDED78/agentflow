package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.domain.DocumentContentType;
import com.example.agentflow.knowledgebase.domain.DocumentUploadLimits;
import com.example.agentflow.knowledgebase.domain.DocumentUploadStatus;
import java.util.Optional;

/**
 * An immutable, validated upload, {@code S10-03} — the payload the {@code DocumentUploadValidator}
 * returns once every accept/bound check passed. It carries everything the rest of the pipeline
 * needs to persist a {@code documents} row and write an object: the server-sanitised filename, the
 * canonical content type, the payload size, the SHA-256 checksum and, for media, the decoded
 * duration. It deliberately does <b>not</b> carry the payload bytes — by the time validation is
 * done the caller decides where they go (object storage), and nothing here implies anything was
 * written. The {@link DocumentUploadStatus} is {@link DocumentUploadStatus#UPLOADED}, the upload
 * lifecycle state the row will start in.
 */
public final class ValidatedUpload {

  private final String fileName;
  private final DocumentContentType contentType;
  private final long sizeBytes;
  private final String checksumSha256;
  private final boolean media;
  private final Optional<Long> mediaDurationSeconds;

  /**
   * @param fileName the server-sanitised, path-free filename (never null/blank)
   * @param contentType the canonical allowlisted content type
   * @param sizeBytes the payload size in bytes (must be within {@link DocumentUploadLimits})
   * @param checksumSha256 the SHA-256 of the payload, hex-encoded, non-null
   * @param media whether the type is media whose duration is bounded
   * @param mediaDurationSeconds decoded duration for media types; empty for non-media
   */
  public ValidatedUpload(
      String fileName,
      DocumentContentType contentType,
      long sizeBytes,
      String checksumSha256,
      boolean media,
      Optional<Long> mediaDurationSeconds) {
    this.fileName = fileName;
    this.contentType = contentType;
    this.sizeBytes = sizeBytes;
    this.checksumSha256 = checksumSha256;
    this.media = media;
    this.mediaDurationSeconds =
        mediaDurationSeconds == null ? Optional.empty() : mediaDurationSeconds;
  }

  /**
   * @return the server-sanitised filename
   */
  public String fileName() {
    return fileName;
  }

  /**
   * @return the canonical allowlisted content type
   */
  public DocumentContentType contentType() {
    return contentType;
  }

  /**
   * @return the payload size in bytes
   */
  public long sizeBytes() {
    return sizeBytes;
  }

  /**
   * @return the SHA-256 checksum of the payload, hex-encoded
   */
  public String checksumSha256() {
    return checksumSha256;
  }

  /**
   * @return whether this is a media upload
   */
  public boolean media() {
    return media;
  }

  /**
   * @return the decoded duration in seconds for media; empty for non-media types
   */
  public Optional<Long> mediaDurationSeconds() {
    return mediaDurationSeconds;
  }
}
