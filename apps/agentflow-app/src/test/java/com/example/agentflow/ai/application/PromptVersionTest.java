package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * S09-04 unit tests for {@link PromptVersion} (DB-free): the version identity a model attempt
 * carries is exactly {@code name / version / hash}, validated up front, and holds no prompt text or
 * user / document content.
 */
class PromptVersionTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void exposes_name_version_and_hash() {
    PromptVersion version = new PromptVersion("planner", "1", HASH);

    assertThat(version.name()).isEqualTo("planner");
    assertThat(version.version()).isEqualTo("1");
    assertThat(version.hash()).isEqualTo(HASH);
  }

  @Test
  void record_components_are_exactly_name_version_and_hash() {
    assertThat(
            Arrays.stream(PromptVersion.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toList()))
        .containsExactly("name", "version", "hash");
  }

  @Test
  void rejects_a_null_name() {
    assertThatThrownBy(() -> new PromptVersion(null, "1", HASH))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejects_a_blank_name() {
    assertThatThrownBy(() -> new PromptVersion("   ", "1", HASH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_a_blank_version() {
    assertThatThrownBy(() -> new PromptVersion("planner", "   ", HASH))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_a_non_hex_hash() {
    assertThatThrownBy(() -> new PromptVersion("planner", "1", "xyz"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejects_a_wrong_length_hash() {
    assertThatThrownBy(() -> new PromptVersion("planner", "1", "a".repeat(63)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
