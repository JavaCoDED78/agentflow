package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.knowledgebase.domain.DocumentContentType;
import com.example.agentflow.knowledgebase.domain.DocumentUploadLimits;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DocumentUploadValidator} ({@code S10-03}). DB-free: the validator is
 * pure and writes nothing. The tests pin the reject-before-persist contract — every accept/bound
 * check rejects without leaving anything to persist (the S10-03 acceptance), the safe filename,
 * canonical type, size and SHA-256 are returned, and for media the decoded duration is bounded.
 */
class DocumentUploadValidatorTest {

  private static final DocumentUploadLimits LIMITS =
      new DocumentUploadLimits(1024 * 1024, 255, 300);

  private final DocumentUploadValidator validator = new DocumentUploadValidator(LIMITS);

  @Nested
  class Accepts {

    @Test
    void a_valid_pdf_is_validated_with_a_server_sanitised_filename_and_checksum() {
      byte[] pdf = ("%PDF-1.7\n% real bytes").getBytes(StandardCharsets.US_ASCII);

      ValidatedUpload result = validator.validateUpload("report.pdf", "application/pdf", pdf);

      assertThat(result.fileName()).isEqualTo("report.pdf");
      assertThat(result.contentType()).isEqualTo(DocumentContentType.from("application/pdf"));
      assertThat(result.sizeBytes()).isEqualTo(pdf.length);
      assertThat(result.media()).isFalse();
      assertThat(result.mediaDurationSeconds()).isEmpty();
      assertThat(result.checksumSha256()).isEqualTo(sha256Hex(pdf));
    }

    @Test
    void a_hostile_filename_is_reduced_to_a_safe_base_name() {
      byte[] pdf = ("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

      ValidatedUpload result = validator.validateUpload("../../etc/passwd", "application/pdf", pdf);

      assertThat(result.fileName()).isEqualTo("passwd");
    }

    @Test
    void a_media_payload_carries_its_decoded_duration() {
      byte[] wav = buildWav(8000, 1, 8, 8000 * 2); // 2 s

      ValidatedUpload result = validator.validateUpload("clip.wav", "audio/wav", wav);

      assertThat(result.media()).isTrue();
      assertThat(result.mediaDurationSeconds()).hasValue(2L);
      assertThat(result.fileName()).isEqualTo("clip.wav");
    }
  }

  @Nested
  class RejectsBeforePersisting {

    @Test
    void an_oversized_payload_is_rejected() {
      byte[] payload = new byte[LIMITS.getMaxBytes() + 1]; // 1025 bytes

      assertThatThrownBy(() -> validator.validateUpload("big.pdf", "application/pdf", payload))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("exceeds the maximum");
    }

    @Test
    void an_unsupported_content_type_is_rejected() {
      byte[] payload = "not a pdf".getBytes(StandardCharsets.US_ASCII);

      assertThatThrownBy(() -> validator.validateUpload("x.txt", "application/javascript", payload))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("not permitted");
    }

    @Test
    void a_tampered_magic_bytes_are_rejected() {
      // Declared as PDF, but the bytes are HTML — the declared type does not match the payload.
      byte[] html = "<html></html>".getBytes(StandardCharsets.UTF_8);

      assertThatThrownBy(() -> validator.validateUpload("fake.pdf", "application/pdf", html))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("does not match");
    }

    @Test
    void a_filename_that_is_too_long_is_rejected() {
      byte[] pdf = ("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
      String longName = "a".repeat(256);

      assertThatThrownBy(() -> validator.validateUpload(longName, "application/pdf", pdf))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("maximum length");
    }

    @Test
    void a_traversal_filename_is_rejected() {
      byte[] pdf = ("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

      assertThatThrownBy(() -> validator.validateUpload("../", "application/pdf", pdf))
          .isInstanceOf(DocumentUploadValidationException.class);
    }

    @Test
    void a_media_payload_longer_than_the_limit_is_rejected() {
      // 600 s at 100 Hz, 8-bit mono = 60_000 bytes, well under max-bytes but over the 300 s limit.
      byte[] wav = buildWav(100, 1, 8, 600 * 100);

      assertThatThrownBy(() -> validator.validateUpload("long.wav", "audio/wav", wav))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("duration");
    }

    @Test
    void an_undecodable_media_payload_is_rejected() {
      // A well-formed RIFF/WAVE container (passes magic bytes) whose fmt header has sample rate 0,
      // so the duration cannot be decoded. Magic bytes pass; the duration decode refuses it.
      byte[] wav = buildWav(0, 1, 8, 100);

      assertThatThrownBy(() -> validator.validateUpload("bad.wav", "audio/wav", wav))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("could not be decoded");
    }

    @Test
    void a_null_payload_is_rejected() {
      assertThatThrownBy(() -> validator.validateUpload("x.pdf", "application/pdf", null))
          .isInstanceOf(DocumentUploadValidationException.class)
          .hasMessageContaining("payload");
    }
  }

  @Test
  void a_null_limits_make_the_validator_instantiation_fail() {
    assertThatThrownBy(() -> new DocumentUploadValidator(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_checksum_is_the_sha_256_of_the_payload() {
    byte[] payload = ("%PDF-1.7\npayload".getBytes(StandardCharsets.US_ASCII));

    ValidatedUpload result = validator.validateUpload("report.pdf", "application/pdf", payload);

    assertThat(result.checksumSha256()).isEqualTo(sha256Hex(payload));
  }

  private static byte[] buildWav(int sampleRate, int numChannels, int bitsPerSample, int dataSize) {
    byte[] payload = new byte[44 + dataSize];
    java.nio.ByteBuffer buffer =
        java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(36 + dataSize);
    buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(16);
    buffer.putShort((short) 1);
    buffer.putShort((short) numChannels);
    buffer.putInt(sampleRate);
    buffer.putInt(sampleRate * numChannels * (bitsPerSample / 8));
    buffer.putShort((short) (numChannels * (bitsPerSample / 8)));
    buffer.putShort((short) bitsPerSample);
    buffer.put("data".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(dataSize);
    return payload;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
