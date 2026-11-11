package com.example.agentflow.ingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for the {@link ChunkingConfig} value object (S10-09): the validation the chunk
 * size / overlap bounds must satisfy and the determinism of the derived {@code pipeline_version}.
 */
class ChunkingConfigTest {

  private static final Strategy STRATEGY = Strategy.CHARACTER_BOUNDARY;

  @Test
  @DisplayName("a valid combination builds")
  void a_valid_combination_builds() {
    ChunkingConfig config = new ChunkingConfig(STRATEGY, 500, 50);

    assertThat(config.strategy()).isEqualTo(STRATEGY);
    assertThat(config.maxChunkChars()).isEqualTo(500);
    assertThat(config.overlapChars()).isEqualTo(50);
  }

  @Test
  @DisplayName("max-chunk-chars must be positive")
  void max_chunk_chars_must_be_positive() {
    assertThatThrownBy(() -> new ChunkingConfig(STRATEGY, 0, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxChunkChars");
  }

  @Test
  @DisplayName("overlap must be non-negative")
  void overlap_must_be_non_negative() {
    assertThatThrownBy(() -> new ChunkingConfig(STRATEGY, 100, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlapChars");
  }

  @Test
  @DisplayName("overlap must be strictly less than the chunk size")
  void overlap_must_be_strictly_less_than_chunk_size() {
    assertThatThrownBy(() -> new ChunkingConfig(STRATEGY, 100, 100))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be <");
  }

  @Test
  @DisplayName(
      "the pipeline version is deterministic and changes only with a chunk-affecting parameter")
  void pipeline_version_is_deterministic_and_changes_with_parameters() {
    ChunkingConfig a = new ChunkingConfig(STRATEGY, 500, 50);
    ChunkingConfig b = new ChunkingConfig(STRATEGY, 500, 50);
    ChunkingConfig resized = new ChunkingConfig(STRATEGY, 400, 50);

    assertThat(a.pipelineVersion()).isEqualTo(b.pipelineVersion());
    assertThat(a.pipelineVersion()).isNotEqualTo(resized.pipelineVersion());
    assertThat(a.pipelineVersion()).contains("cs=500");
  }

  @Test
  @DisplayName("equal parameters give equal configs; the strategy is part of equality")
  void equals_and_hash_code_by_parameters() {
    ChunkingConfig a = new ChunkingConfig(STRATEGY, 500, 50);
    ChunkingConfig b = new ChunkingConfig(STRATEGY, 500, 50);

    assertThat(a).isEqualTo(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode());
  }

  @Test
  @DisplayName("a null strategy is rejected")
  void a_null_strategy_is_rejected() {
    assertThatThrownBy(() -> new ChunkingConfig(null, 500, 50))
        .isInstanceOf(NullPointerException.class);
  }
}
