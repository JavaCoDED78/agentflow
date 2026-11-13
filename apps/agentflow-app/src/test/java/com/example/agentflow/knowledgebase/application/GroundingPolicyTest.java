package com.example.agentflow.knowledgebase.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code S10-18} no-evidence decision, {@link GroundingPolicy}.
 *
 * <p>Pure and side-effect free: given the citations the retrieval returned and a {@code searchId},
 * the policy either {@link GroundedAnswer.Grounded reports a grounding} or {@link
 * GroundedAnswer.InsufficientEvidence reports an evidence gap}. This suite pins the bar, the empty
 * result and the argument guards.
 */
class GroundingPolicyTest {

  private static final UUID SEARCH = UUID.randomUUID();
  private static final CitationDto CITATION =
      new CitationDto(
          UUID.randomUUID(), UUID.randomUUID(), "fixture-doc", 1, 2, 0.9, "fragment text");

  /** The policy the glossary §8 fixes: {@code 0} — any non-empty retrieval grounds the answer. */
  @Test
  void the_default_policy_grounds_any_non_empty_retrieval() {
    assertThat(GroundingPolicy.DEFAULT.minimumCitations()).isZero();
    assertThat(GroundingPolicy.DEFAULT.decide(List.of(CITATION), SEARCH))
        .isInstanceOf(GroundedAnswer.Grounded.class);
  }

  @Test
  void the_default_policy_reports_insufficient_evidence_for_an_empty_retrieval() {
    GroundedAnswer answer = GroundingPolicy.DEFAULT.decide(List.of(), SEARCH);

    assertThat(answer).isInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(((GroundedAnswer.InsufficientEvidence) answer).searchId()).isEqualTo(SEARCH);
  }

  @Test
  void a_non_minimum_bar_requires_that_many_citations() {
    GroundingPolicy policy = new GroundingPolicy(2);

    assertThat(policy.decide(List.of(CITATION), SEARCH))
        .isInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(policy.decide(List.of(CITATION, CITATION), SEARCH))
        .isInstanceOf(GroundedAnswer.Grounded.class);
  }

  @Test
  void the_policy_barely_passes_at_the_bar_and_fails_below_it() {
    GroundingPolicy policy = new GroundingPolicy(1);

    assertThat(policy.decide(List.of(), SEARCH))
        .isInstanceOf(GroundedAnswer.InsufficientEvidence.class);
    assertThat(policy.decide(List.of(CITATION), SEARCH))
        .isInstanceOf(GroundedAnswer.Grounded.class);
  }

  @Test
  void the_policy_rejects_a_negative_minimum() {
    assertThatThrownBy(() -> new GroundingPolicy(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minimumCitations");
  }

  @Test
  void decide_rejects_null_arguments() {
    assertThatThrownBy(() -> GroundingPolicy.DEFAULT.decide(null, SEARCH))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GroundingPolicy.DEFAULT.decide(List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decide_reports_the_search_id_of_the_call_that_found_nothing() {
    UUID otherSearch = UUID.randomUUID();
    GroundedAnswer.InsufficientEvidence gap =
        (GroundedAnswer.InsufficientEvidence) new GroundingPolicy(0).decide(List.of(), otherSearch);

    assertThat(gap.searchId()).isEqualTo(otherSearch);
  }
}
