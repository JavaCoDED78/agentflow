package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code S10-18} no-evidence result, {@link GroundedAnswer}.
 *
 * <p>The type is sealed: the only ways to report a grounding are a {@link GroundedAnswer.Grounded
 * grounding} or an {@link GroundedAnswer.InsufficientEvidence evidence gap}, so this suite pins the
 * two record contracts and their guards.
 */
class GroundedAnswerTest {

  private static final UUID SEARCH = UUID.randomUUID();
  private static final CitationDto CITATION =
      new CitationDto(
          UUID.randomUUID(), UUID.randomUUID(), "fixture-doc", 1, 2, 0.9, "fragment text");

  @Test
  void a_grounding_carrying_real_citations_is_a_Grounded() {
    GroundedAnswer.Grounded grounding = new GroundedAnswer.Grounded(List.of(CITATION));

    assertThat(grounding).isInstanceOf(GroundedAnswer.Grounded.class);
    assertThat(grounding.citations()).containsExactly(CITATION);
  }

  @Test
  void a_Grounded_takes_a_defensive_copy_of_the_citations() {
    // A mutable input so List.copyOf produces a distinct, unmodifiable copy.
    var mutable = new ArrayList<>(List.of(CITATION));
    GroundedAnswer.Grounded grounding = new GroundedAnswer.Grounded(mutable);

    assertThat(grounding.citations()).isUnmodifiable();
    assertThat(grounding.citations()).isNotSameAs(mutable);
  }

  @Test
  void a_Grounded_with_an_empty_list_is_a_grounding_in_nothing() {
    GroundedAnswer.Grounded grounding = new GroundedAnswer.Grounded(List.of());

    assertThat(grounding).isInstanceOf(GroundedAnswer.Grounded.class);
    assertThat(grounding.citations()).isEmpty();
  }

  @Test
  void the_grounding_constructor_rejects_null_citations() {
    assertThatThrownBy(() -> new GroundedAnswer.Grounded(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("citations");
  }

  @Test
  void an_evidence_gap_reports_the_search_id_only() {
    GroundedAnswer.InsufficientEvidence gap = new GroundedAnswer.InsufficientEvidence(SEARCH);

    assertThat(gap).isInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(gap.searchId()).isEqualTo(SEARCH);
  }

  @Test
  void the_evidence_gap_constructor_rejects_null_search_id() {
    assertThatThrownBy(() -> new GroundedAnswer.InsufficientEvidence(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("searchId");
  }

  @Test
  void the_two_variants_are_distinct_and_both_are_GroundedAnswer() {
    assertThat(new GroundedAnswer.Grounded(List.of()))
        .isNotInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(new GroundedAnswer.InsufficientEvidence(SEARCH))
        .isNotInstanceOf(GroundedAnswer.Grounded.class);
    assertThat(new GroundedAnswer.Grounded(List.of())).isInstanceOf(GroundedAnswer.class);
    assertThat(new GroundedAnswer.InsufficientEvidence(SEARCH)).isInstanceOf(GroundedAnswer.class);
  }
}
