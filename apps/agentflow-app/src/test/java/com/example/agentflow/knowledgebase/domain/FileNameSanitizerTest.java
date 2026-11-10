package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link FileNameSanitizer} ({@code S10-03}). DB-free: the value is pure. The
 * tests pin that the filename can never carry a path, a traversal or a control character, and can
 * never become an object path (invariant 1 / {@code S10-04}).
 */
class FileNameSanitizerTest {

  @Test
  void a_plain_filename_is_returned_as_a_safe_base_name() {
    assertThat(FileNameSanitizer.sanitize("report.pdf")).isEqualTo("report.pdf");
  }

  @Test
  void any_path_is_reduced_to_its_trailing_segment() {
    assertThat(FileNameSanitizer.sanitize("a/b/c/report.pdf")).isEqualTo("report.pdf");
    assertThat(FileNameSanitizer.sanitize("c:\\back\\slash\\report.pdf")).isEqualTo("report.pdf");
    assertThat(FileNameSanitizer.sanitize("  report.pdf  ")).isEqualTo("report.pdf");
  }

  @Nested
  class Rejected {

    @Test
    void a_blank_or_null_name_is_rejected() {
      assertThatThrownBy(() -> FileNameSanitizer.sanitize(""))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("   "))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> FileNameSanitizer.sanitize(null))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_traversal_filename_is_sanitised_to_a_safe_base() {
      // The sanitizer strips the path/traversal rather than trusting it, so no segment survives.
      assertThat(FileNameSanitizer.sanitize("../secret")).isEqualTo("secret");
      assertThat(FileNameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
    }

    @Test
    void a_name_that_reduces_to_dots_or_empty_is_rejected() {
      assertThatThrownBy(() -> FileNameSanitizer.sanitize(".."))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("../"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_byte_or_control_character_is_rejected() {
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("report\u0000.pdf"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("rep\rort"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("re\u0001port"))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_name_that_is_only_a_path_leaves_a_rejected_base() {
      assertThatThrownBy(() -> FileNameSanitizer.sanitize("a/b/"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
