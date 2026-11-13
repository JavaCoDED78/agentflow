package com.example.agentflow.knowledgebase.application;

import java.util.List;

/**
 * Builds citations from retrieved evidence (S10-17) — the {@code knowledgebase} layer's counterpart
 * to {@link RankFusion}: a <b>pure</b> transformer, no Spring, no database, no I/O.
 *
 * <p>It obeys the glossary &sect;8 rule that <i>only real retrieved chunks are cited</i>: it emits
 * <b>one citation per retrieved chunk</b> and never invents one. A {@link SearchResult} the caller
 * cannot see (another tenant's, or empty) therefore can never yield a citation — an empty result
 * produces an empty list, and every citation resolves to a chunk the retrieval actually returned.
 *
 * <p>Each citation's {@link CitationDto#chunkId()} resolves to the <em>same</em> tenant- and
 * collection-scoped chunk the retrieval returned: the {@link ChunkDto} it is built from was
 * produced by {@link RetrievalService} from a query whose tenant / collection predicates run inside
 * the SQL (invariant 1), so the citation cannot point at another tenant's document.
 *
 * <p>{@link CitationDto#snippet()} is untrusted document text (invariant 7); the caller may feed it
 * to a model only as {@code USER} content and never logs it (invariant 8). {@link
 * CitationDto#documentName()} is trusted document metadata, not the snippet.
 */
public final class CitationBuilder {

  private CitationBuilder() {
    // pure static utility — never instantiated
  }

  /**
   * Build one {@link CitationDto} per retrieved chunk, in the retrieved order (which the retrieval
   * halves already ranked). The list is empty when {@code result} is empty.
   *
   * @param result the retrieved evidence (already tenant- and collection-scoped); never {@code
   *     null}
   * @return one citation per chunk in {@code result}, in retrieved order; empty when the result is
   *     empty — never an invented citation
   * @throws IllegalArgumentException if {@code result} is {@code null}
   */
  public static List<CitationDto> cite(SearchResult result) {
    if (result == null) {
      throw new IllegalArgumentException("result must not be null");
    }
    return result.chunks().stream().map(CitationBuilder::toCitation).toList();
  }

  private static CitationDto toCitation(ChunkDto chunk) {
    return new CitationDto(
        chunk.documentId(),
        chunk.id(),
        chunk.documentName(),
        chunk.pageFrom(),
        chunk.pageTo(),
        chunk.score(),
        chunk.content());
  }
}
