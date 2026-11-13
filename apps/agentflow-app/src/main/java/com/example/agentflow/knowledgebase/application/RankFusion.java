package com.example.agentflow.knowledgebase.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rank fusion for the hybrid retrieval (S10-16).
 *
 * <p>Pure: no Spring, no database, no I/O. It fuses several independent <em>ranked</em> candidate
 * lists — one per retrieval half, each ordered from best to worst — into a single ordered,
 * deduplicated result, so the vector half ({@link RetrievalService#search}) and the lexical half
 * ({@link RetrievalService#lexicalSearch}) combine into one {@code SearchResult}.
 *
 * <p>The fusion is position based (reciprocal-rank fusion): a chunk that appears in more than one
 * half, or earlier in a half, scores higher. A chunk's fused score is the sum of {@code 1 / (K +
 * rank)} over the halves it appears in, where {@code rank} is its zero-based position in that half.
 * A chunk present in both halves therefore outranks one present in a single half, and within a half
 * an earlier position always scores higher — the two independent relevance signals are combined
 * without ever comparing their different score scales (cosine distance vs. {@code ts_rank}).
 *
 * <p>The output is ordered by fused score (descending); ties are broken by the best (lowest)
 * position any half gave the chunk, then by the chunk id, so a fixed set of queries always yields
 * the same ordered set — deterministic, which the {@code S10-16} acceptance relies on.
 *
 * <p>The fusion operates on chunk ids only: it deduplicates by id (a chunk in both halves is
 * emitted once) and touches no content, so no document text is handled here (invariants 7/8). The
 * service maps the fused ids back to their {@link ChunkDto}s and applies the final cap.
 *
 * <p>Why {@code K = 1} and not the web-search value {@code 60}: {@code RRF}'s {@code K = 60} exists
 * to down-weight absolute rank across thousands of web-scale candidates. Hybrid retrieval fuses at
 * most {@code max-top-k} candidates per half, where {@code 60} would collapse the within-half rank
 * differences to noise and make presence the only signal. {@code 1} keeps the small-set ordering
 * meaningful while still rewarding cross-half agreement.
 */
public final class RankFusion {

  /** The reciprocal-rank constant (see the class-level rationale for {@code 1} vs. {@code 60}). */
  private static final double K = 1.0;

  private RankFusion() {}

  /**
   * One fused candidate: its chunk id, the reciprocal-rank score (higher is better), and the best
   * (lowest) position any half gave it — the tie-break after the score.
   *
   * @param chunkId the chunk the candidate refers to
   * @param score the fused reciprocal-rank score (higher is better)
   * @param bestRank the best (lowest) zero-based position across the halves the chunk appeared in
   */
  public record FusedCandidate(UUID chunkId, double score, int bestRank) {}

  /**
   * Fuse the given ranked id lists (one per retrieval half) into one ordered, deduplicated result.
   *
   * @param rankedLists the ordered candidate-id lists to fuse (each half's order preserved); an
   *     empty or {@code null} list is ignored
   * @return the fused candidates ordered by fused score descending, then best rank ascending, then
   *     chunk id ascending — empty when every list is empty
   * @throws IllegalArgumentException if {@code rankedLists} is {@code null}
   */
  public static List<FusedCandidate> fuse(List<List<UUID>> rankedLists) {
    if (rankedLists == null) {
      throw new IllegalArgumentException("rankedLists must not be null");
    }
    Map<UUID, Double> score = new HashMap<>();
    Map<UUID, Integer> bestRank = new HashMap<>();
    for (List<UUID> ranked : rankedLists) {
      if (ranked == null) {
        continue;
      }
      for (int i = 0; i < ranked.size(); i++) {
        UUID id = ranked.get(i);
        if (id == null) {
          continue;
        }
        double contribution = 1.0 / (K + i);
        score.merge(id, contribution, Double::sum);
        bestRank.merge(id, i, Integer::min);
      }
    }
    List<FusedCandidate> fused = new ArrayList<>(score.size());
    for (Map.Entry<UUID, Double> entry : score.entrySet()) {
      fused.add(new FusedCandidate(entry.getKey(), entry.getValue(), bestRank.get(entry.getKey())));
    }
    fused.sort(
        Comparator.comparingDouble((FusedCandidate f) -> f.score())
            .reversed()
            .thenComparingInt((FusedCandidate f) -> f.bestRank())
            .thenComparing((FusedCandidate f) -> f.chunkId()));
    return fused;
  }
}
