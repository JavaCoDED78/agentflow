package com.example.agentflow.knowledgebase.application;

import com.example.agentflow.knowledgebase.domain.DocumentContentType;
import com.example.agentflow.knowledgebase.domain.DocumentUploadLimits;
import com.example.agentflow.knowledgebase.domain.FileNameSanitizer;
import com.example.agentflow.knowledgebase.domain.MediaDuration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * The {@code S10-03} document-upload accept/bound checks, {@code S10-03} — the safe core that runs
 * <b>before</b> a single byte is written to object storage and before a single {@code documents}
 * metadata row is committed. This is the whole of S10-03 that is fully verifiable today: it needs
 * no storage backend, no schema and no worker, only the configured limits, so it is unit-testable
 * in isolation and safe to call from an early API.
 *
 * <p>The checks, in order, and each one is a <b>reject-before-anything</b> boundary — the moment
 * any check fails the payload is dropped and nothing is persisted, so an oversized or unsupported
 * upload can never leave a dangling metadata row or object (the S10-03 acceptance criterion):
 *
 * <ol>
 *   <li><b>Filename</b> &mdash; {@link FileNameSanitizer} strips any path/traversal/control
 *       character and rejects a blank or {@code ..}-only name; the result is then length-checked
 *       against {@code maxFileNameLength}.
 *   <li><b>Content type</b> &mdash; {@link DocumentContentType#from(String)} resolves the declared
 *       type against the closed allowlist and rejects anything not on it, so an untrusted or
 *       prompt-injected header cannot expand the accepted surface.
 *   <li><b>Size</b> &mdash; the payload length is rejected when it exceeds {@code maxBytes}.
 *   <li><b>Magic bytes</b> &mdash; {@link DocumentContentType#matches(byte[])} verifies the real
 *       leading bytes match the declared type, so a payload that misrepresents itself is rejected.
 *   <li><b>Media duration</b> &mdash; for media types, {@link MediaDuration} decodes the WAV
 *       duration and it is rejected when it exceeds {@code maxMediaDurationSeconds}.
 *   <li><b>Checksum</b> &mdash; the SHA-256 of the payload is computed last and returned for the
 *       row.
 * </ol>
 *
 * <p>Every rejected input throws {@link DocumentUploadValidationException}, which the HTTP layer
 * translates to a {@code 400} ({@code common.ErrorClass}). The validator holds no mutable state, so
 * it is safe to share across requests.
 */
public final class DocumentUploadValidator {

  private static final String ALGORITHM_SHA_256 = "SHA-256";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private final DocumentUploadLimits limits;

  /**
   * @param limits the configured upload limits ({@code agentflow.upload.*}); must be non-null
   */
  public DocumentUploadValidator(DocumentUploadLimits limits) {
    if (limits == null) {
      throw new IllegalArgumentException("limits must not be null");
    }
    this.limits = limits;
  }

  /**
   * Validate an upload against every S10-03 accept/bound check, in the reject-before-persist order.
   * Nothing is written by this call.
   *
   * @param declaredFileName the raw client filename (may be {@code null}/blank or unsafe)
   * @param declaredContentType the raw client content type, parameters such as {@code ;charset=}
   *     permitted
   * @param payload the raw upload bytes (must be non-null)
   * @return a {@link ValidatedUpload} carrying the safe filename, canonical type, size, checksum
   *     and (for media) duration
   * @throws DocumentUploadValidationException if any check fails (oversized, unsupported, bad
   *     filename, tampered magic bytes, undecodable/over-long media)
   */
  public ValidatedUpload validateUpload(
      String declaredFileName, String declaredContentType, byte[] payload) {
    if (payload == null) {
      throw new DocumentUploadValidationException("payload must not be null");
    }
    String safeFileName = sanitizeFileName(declaredFileName);
    verifyFileNameLength(safeFileName);
    DocumentContentType contentType = resolveContentType(declaredContentType);
    verifySize(payload);
    verifyMagicBytes(contentType, payload);
    verifyMediaDuration(contentType, payload);
    return new ValidatedUpload(
        safeFileName,
        contentType,
        payload.length,
        sha256Hex(payload),
        contentType.isMedia(),
        contentType.isMedia() ? MediaDuration.durationSeconds(payload) : Optional.empty());
  }

  private String sanitizeFileName(String declaredFileName) {
    // Sanitize first; a bad name is rejected before any length check. The sanitizer throws a raw
    // IllegalArgumentException; wrap it so every upload rejection is a single
    // DocumentUploadValidationException.
    try {
      return FileNameSanitizer.sanitize(declaredFileName);
    } catch (IllegalArgumentException e) {
      throw new DocumentUploadValidationException(e.getMessage());
    }
  }

  private void verifyFileNameLength(String safeFileName) {
    if (safeFileName.length() > limits.getMaxFileNameLength()) {
      throw new DocumentUploadValidationException(
          "filename exceeds the maximum length of "
              + limits.getMaxFileNameLength()
              + " characters: "
              + safeFileName.length());
    }
  }

  private DocumentContentType resolveContentType(String declaredContentType) {
    try {
      return DocumentContentType.from(declaredContentType);
    } catch (IllegalArgumentException e) {
      throw new DocumentUploadValidationException(e.getMessage());
    }
  }

  private void verifySize(byte[] payload) {
    if (payload.length > limits.getMaxBytes()) {
      throw new DocumentUploadValidationException(
          "payload size " + payload.length + " exceeds the maximum of " + limits.getMaxBytes());
    }
  }

  private void verifyMagicBytes(DocumentContentType contentType, byte[] payload) {
    if (!contentType.matches(payload)) {
      throw new DocumentUploadValidationException(
          "payload does not match the declared content type " + contentType.contentType());
    }
  }

  private void verifyMediaDuration(DocumentContentType contentType, byte[] payload) {
    if (!contentType.isMedia()) {
      return;
    }
    Long duration =
        MediaDuration.durationSeconds(payload)
            .orElseThrow(
                () ->
                    new DocumentUploadValidationException(
                        "media payload could not be decoded; refusing to accept it"));
    if (duration > limits.getMaxMediaDurationSeconds()) {
      throw new DocumentUploadValidationException(
          "media duration "
              + duration
              + "s exceeds the maximum of "
              + limits.getMaxMediaDurationSeconds()
              + "s");
    }
  }

  private static String sha256Hex(byte[] payload) {
    try {
      MessageDigest digest = MessageDigest.getInstance(ALGORITHM_SHA_256);
      byte[] hash = digest.digest(payload);
      char[] hexChars = new char[hash.length * 2];
      for (int i = 0; i < hash.length; i++) {
        int v = hash[i] & 0xFF;
        hexChars[i * 2] = HEX[v >>> 4];
        hexChars[i * 2 + 1] = HEX[v & 0x0F];
      }
      return new String(hexChars);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the JVM spec; treat absence as a hard internal error.
      throw new IllegalStateException(ALGORITHM_SHA_256 + " not available", e);
    }
  }
}
