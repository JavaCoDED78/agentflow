package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DocumentContentType} closed allowlist and its magic-byte verification
 * ({@code S10-03}). DB-free: the value is pure. The tests pin that the accepted set is small and
 * fixed, that a declared type is verified against the real leading bytes (not just the header), and
 * that an untrusted type is rejected so an untrusted header cannot expand the surface.
 */
class DocumentContentTypeTest {

  @Test
  void a_whitelisted_type_resolves_case_insensitively_and_strips_parameters() {
    DocumentContentType pdf = DocumentContentType.from("application/PDF; charset=binary");

    assertThat(pdf.contentType()).isEqualTo("application/pdf");
  }

  @Test
  void every_allowed_type_resolves_from_a_hostile_declaration() {
    Set<String> hostile =
        Set.of(
            "application/pdf",
            "  IMAGE/PNG  ",
            "application/json;charset=utf-8",
            "text/plain",
            "audio/wav");

    for (String declaration : hostile) {
      assertThat(DocumentContentType.from(declaration)).isNotNull();
    }
  }

  @Test
  void an_unallowed_content_type_is_rejected() {
    Set<String> rejected =
        Set.of(
            "application/javascript",
            "text/html",
            "application/vnd.microsoft.word",
            "application/octet-stream",
            "text/x-shellscript",
            "application/x-executable");

    for (String declaration : rejected) {
      assertThatThrownBy(() -> DocumentContentType.from(declaration))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not permitted");
    }
  }

  @Test
  void a_blank_or_null_declaration_is_rejected() {
    assertThatThrownBy(() -> DocumentContentType.from(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DocumentContentType.from("   "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DocumentContentType.from(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Nested
  class MagicBytes {

    @Test
    void a_pdf_must_start_with_the_pdf_signature() {
      DocumentContentType pdf = DocumentContentType.from("application/pdf");
      byte[] realPdf = ("%PDF-1.7\n% header bytes").getBytes(StandardCharsets.US_ASCII);
      byte[] fakePdf = "%HTML-1.7".getBytes(StandardCharsets.US_ASCII);

      assertThat(pdf.matches(realPdf)).isTrue();
      assertThat(pdf.matches(fakePdf)).isFalse();
    }

    @Test
    void a_png_must_start_with_the_png_signature() {
      DocumentContentType png = DocumentContentType.from("image/png");
      byte[] realPng = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
      byte[] notPng = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};

      assertThat(png.matches(realPng)).isTrue();
      assertThat(png.matches(notPng)).isFalse();
    }

    @Test
    void a_gif_matches_either_of_its_two_versions() {
      DocumentContentType gif = DocumentContentType.from("image/gif");
      byte[] g87a = "GIF87a".getBytes(StandardCharsets.US_ASCII);
      byte[] g89a = "GIF89a".getBytes(StandardCharsets.US_ASCII);

      assertThat(gif.matches(g87a)).isTrue();
      assertThat(gif.matches(g89a)).isTrue();
    }

    @Test
    void a_wav_container_requires_riff_and_wave() {
      DocumentContentType wav = DocumentContentType.from("audio/wav");
      byte[] riff = new byte[16];
      System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, riff, 0, 4);
      System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, riff, 8, 4);
      byte[] fake = "RIFF-not-wave".getBytes(StandardCharsets.US_ASCII);

      assertThat(wav.matches(riff)).isTrue();
      assertThat(wav.matches(fake)).isFalse();
    }

    @Test
    void an_empty_payload_never_matches_any_type() {
      DocumentContentType pdf = DocumentContentType.from("application/pdf");
      assertThat(pdf.matches(new byte[0])).isFalse();
      assertThat(pdf.matches(null)).isFalse();
    }

    @Test
    void a_json_payload_must_start_with_a_structural_character() {
      DocumentContentType json = DocumentContentType.from("application/json");
      byte[] valid = "{ \"a\": 1 }".getBytes(StandardCharsets.UTF_8);
      byte[] invalid = "not-json".getBytes(StandardCharsets.UTF_8);

      assertThat(json.matches(valid)).isTrue();
      assertThat(json.matches(invalid)).isFalse();
    }
  }

  @Test
  void a_media_type_is_flagged_as_media_and_others_are_not() {
    assertThat(DocumentContentType.from("audio/wav").isMedia()).isTrue();
    assertThat(DocumentContentType.from("application/pdf").isMedia()).isFalse();
    assertThat(DocumentContentType.from("text/plain").isMedia()).isFalse();
  }

  @Test
  void is_permitted_is_a_non_throwing_membership_probe() {
    assertThat(DocumentContentType.isPermitted("application/pdf")).isTrue();
    assertThat(DocumentContentType.isPermitted("application/javascript")).isFalse();
    assertThat(DocumentContentType.isPermitted(null)).isFalse();
  }
}
