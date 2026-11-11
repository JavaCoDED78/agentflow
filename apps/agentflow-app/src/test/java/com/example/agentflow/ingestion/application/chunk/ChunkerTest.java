package com.example.agentflow.ingestion.application.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.DocumentChunk;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.Strategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DB-free unit tests for the deterministic {@link Chunker} (S10-09).
 *
 * <p>The {@code Chunker} is pure, so the whole suite runs without a database, Kafka or a model. The
 * headline acceptance of {@code S10-09} &mdash; one input plus one pipeline version yields the same
 * chunk set &mdash; is pinned first; the rest cover the size bound, the word-boundary behaviour,
 * the context overlap, the per-page range, the SHA-256 content hash and the empty-input case.
 */
class ChunkerTest {

  /**
   * The SHA-256 hex of the chunk content produced by {@code SINGLE_PAGE} under {@code budget20}.
   */
  private static final String FIRST_CHUNK_HASH =
      "9ecb36561341d18eb65484e833efea61edc74b84cf5e6ae1b81c63533e25fc8f";

  private static final String SINGLE_PAGE = "the quick brown fox jumps over the lazy dog";

  private static final ChunkingConfig BUDGET_20_OVERLAP_5 =
      new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 20, 5);

  private static final ChunkingConfig BUDGET_20_NO_OVERLAP =
      new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 20, 0);

  private static ExtractionResult result(String text) {
    return ExtractionResult.of(1, text, Map.of(), false, "parser:v1");
  }

  @Test
  @DisplayName("one input and one pipeline version yield the same chunk set")
  void deterministic_same_input_and_config_yield_identical_chunk_set() {
    Chunker first = new Chunker(BUDGET_20_OVERLAP_5);
    Chunker second = new Chunker(BUDGET_20_OVERLAP_5);

    List<DocumentChunk> a = first.chunk(result(SINGLE_PAGE));
    List<DocumentChunk> b = second.chunk(result(SINGLE_PAGE));

    assertThat(a).isEqualTo(b); // field-everywhere, so index / pages / hash / version all match
    assertThat(a).isNotEmpty();
    // The content hash is part of the record: identical across runs (byte-stable, no
    // clock/randomness).
    assertThat(a.get(0).contentHash()).isEqualTo(b.get(0).contentHash());
    // Every chunk records the pipeline version of the configuration that produced it.
    assertThat(a)
        .allSatisfy(
            chunk ->
                assertThat(chunk.pipelineVersion())
                    .isEqualTo(BUDGET_20_OVERLAP_5.pipelineVersion()));
  }

  @Test
  @DisplayName("the chunker reports the configured strategy and pipeline version")
  void exposes_strategy_and_pipeline_version() {
    Chunker chunker = new Chunker(BUDGET_20_OVERLAP_5);

    assertThat(chunker.strategy()).isEqualTo(Strategy.CHARACTER_BOUNDARY);
    assertThat(chunker.pipelineVersion()).isEqualTo(BUDGET_20_OVERLAP_5.pipelineVersion());
  }

  @Test
  @DisplayName("each chunk stays within the character budget, respecting word boundaries")
  void each_chunk_stays_within_the_character_budget() {
    Chunker chunker = new Chunker(BUDGET_20_NO_OVERLAP);

    List<DocumentChunk> chunks = chunker.chunk(result(SINGLE_PAGE));

    assertThat(chunks).hasSize(3);
    for (DocumentChunk chunk : chunks) {
      assertThat(chunk.content()).hasSizeLessThanOrEqualTo(20);
      assertThat(chunk.content()).doesNotContain("\n");
    }
  }

  @Test
  @DisplayName("chunk indices are zero-based and ordered")
  void chunk_indices_are_zero_based_and_ordered() {
    Chunker chunker = new Chunker(BUDGET_20_NO_OVERLAP);

    List<DocumentChunk> chunks = chunker.chunk(result(SINGLE_PAGE));

    assertThat(chunks).hasSize(3);
    assertThat(chunks.get(0).index()).isZero();
    assertThat(chunks.get(2).index()).isEqualTo(2);
    assertThat(chunks).extracting(DocumentChunk::index).containsExactly(0, 1, 2);
  }

  @Test
  @DisplayName("a single word longer than the budget becomes its own chunk, never split")
  void a_single_long_word_becomes_its_own_chunk() {
    Chunker chunker = new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 10, 0));

    List<DocumentChunk> chunks = chunker.chunk(result("supercalifragilisticexpialidocious done"));

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).content()).isEqualTo("supercalifragilisticexpialidocious");
    assertThat(chunks.get(0).content())
        .hasSizeGreaterThan(10); // longer than the budget, kept whole
    assertThat(chunks.get(1).content()).isEqualTo("done");
  }

  @Test
  @DisplayName("overlap carries the tail of the previous chunk into the next")
  void overlap_carries_the_tail_of_the_previous_chunk_into_the_next() {
    Chunker chunker = new Chunker(BUDGET_20_OVERLAP_5);

    List<DocumentChunk> chunks = chunker.chunk(result(SINGLE_PAGE));

    assertThat(chunks).hasSize(3);
    // The previous chunk ended on "... brown fox"; the overlap tail ("fox") is prepended.
    assertThat(chunks.get(1).content()).isEqualTo("fox jumps over the lazy");
    assertThat(chunks.get(1).content()).startsWith("fox ");
  }

  @Test
  @DisplayName("the content hash is the SHA-256 hex of the normalised content")
  void content_hash_is_sha256_of_normalised_content() {
    Chunker chunker = new Chunker(BUDGET_20_NO_OVERLAP);

    List<DocumentChunk> chunks = chunker.chunk(result(SINGLE_PAGE));

    assertThat(chunks.get(0).contentHash()).isEqualTo(FIRST_CHUNK_HASH);
    assertThat(chunks.get(0).content()).isEqualTo("the quick brown fox");
  }

  @Test
  @DisplayName("a fragment that spans several pages records the full page range")
  void page_range_spans_the_pages_a_fragment_reaches() {
    String threePages =
        "page one alpha beta" + '\u000C' + "page two gamma delta" + '\u000C' + "page three epsilon";
    Chunker chunker = new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 1000, 0));

    List<DocumentChunk> chunks = chunker.chunk(result(threePages));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).pageFrom()).isEqualTo(1);
    assertThat(chunks.get(0).pageTo()).isEqualTo(3);
    assertThat(chunks.get(0).content()).contains("page one alpha beta");
    assertThat(chunks.get(0).content()).contains("page three epsilon");
  }

  @Test
  @DisplayName("a single-page text (no page separator) records pageFrom == pageTo == 1")
  void single_page_text_records_page_one() {
    Chunker chunker = new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 1000, 0));

    List<DocumentChunk> chunks = chunker.chunk(result(SINGLE_PAGE));

    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).pageFrom()).isEqualTo(1);
    assertThat(chunks.get(0).pageTo()).isEqualTo(1);
  }

  @Test
  @DisplayName("empty or blank text produces no chunks, never throwing")
  void empty_or_blank_text_produces_no_chunks() {
    Chunker chunker = new Chunker(BUDGET_20_OVERLAP_5);

    assertThat(chunker.chunk(ExtractionResult.empty("parser:v1"))).isEmpty();
    assertThat(chunker.chunk(result("   \n\t "))).isEmpty();
  }

  @Test
  @DisplayName("a null result produces no chunks")
  void null_result_produces_no_chunks() {
    Chunker chunker = new Chunker(BUDGET_20_OVERLAP_5);

    assertThat(chunker.chunk(null)).isEmpty();
  }
}
