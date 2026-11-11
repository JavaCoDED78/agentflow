package com.example.agentflow.ingestion.domain;

import java.util.Objects;

/**
 * One deterministic fragment of an extracted document, ready for embedding and indexing (S10-09).
 *
 * <p>This is the domain mirror of the persisted {@code document_chunks} row the glossary §7 defines
 * for later sprints: it carries exactly the columns {@code S10-09} produces &mdash; {@code
 * chunk_index}, {@code page_from}, {@code page_to}, {@code content}, {@code content_hash} and
 * {@code pipeline_version} &mdash; without the {@code tenant_id} / {@code document_id} binding
 * (added by the persistence layer) or the {@code embedding} (added by {@code S10-11}). The tenant /
 * document binding is the persistence concern, never the chunker, so the chunk stays a pure,
 * tenant-free value.
 *
 * <p>Immutable and validated: a chunk is always a non-blank fragment on a real, ordered page range,
 * so an empty or inverted slice can never reach the embedding step. The {@link #contentHash()} is a
 * SHA-256 hex fingerprint of the (whitespace-normalised) content, so an identical fragment always
 * hashes identically &mdash; the idempotency seed {@code S10-13} keys on it.
 */
public final class DocumentChunk {

  private final int index;
  private final int pageFrom;
  private final int pageTo;
  private final String content;
  private final String contentHash;
  private final String pipelineVersion;

  /**
   * @param index the zero-based position of the chunk within its document's chunk set
   * @param pageFrom the first page the fragment spans (always &ge; 1)
   * @param pageTo the last page the fragment spans (always &ge; {@code pageFrom})
   * @param content the fragment text; non-blank (never {@code null})
   * @param contentHash the SHA-256 hex fingerprint of {@code content} (never {@code null} or blank)
   * @param pipelineVersion the chunking configuration version that produced the chunk (never {@code
   *     null} or blank)
   */
  public DocumentChunk(
      int index,
      int pageFrom,
      int pageTo,
      String content,
      String contentHash,
      String pipelineVersion) {
    if (index < 0) {
      throw new IllegalArgumentException("index must be >= 0, was " + index);
    }
    if (pageFrom < 1) {
      throw new IllegalArgumentException("pageFrom must be >= 1, was " + pageFrom);
    }
    if (pageTo < pageFrom) {
      throw new IllegalArgumentException("pageTo (" + pageTo + ") < pageFrom (" + pageFrom + ")");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be null or blank");
    }
    if (contentHash == null || contentHash.isBlank()) {
      throw new IllegalArgumentException("contentHash must not be null or blank");
    }
    if (pipelineVersion == null || pipelineVersion.isBlank()) {
      throw new IllegalArgumentException("pipelineVersion must not be null or blank");
    }
    this.index = index;
    this.pageFrom = pageFrom;
    this.pageTo = pageTo;
    this.content = content;
    this.contentHash = contentHash;
    this.pipelineVersion = pipelineVersion;
  }

  /**
   * @return the zero-based position of the chunk within its document's chunk set.
   */
  public int index() {
    return index;
  }

  /**
   * @return the first page the fragment spans (always &ge; 1).
   */
  public int pageFrom() {
    return pageFrom;
  }

  /**
   * @return the last page the fragment spans (always &ge; {@link #pageFrom()}).
   */
  public int pageTo() {
    return pageTo;
  }

  /**
   * @return the fragment text (whitespace-normalised, non-blank).
   */
  public String content() {
    return content;
  }

  /**
   * @return the SHA-256 hex fingerprint of {@link #content()}.
   */
  public String contentHash() {
    return contentHash;
  }

  /**
   * @return the chunking configuration version that produced this chunk (the {@code
   *     pipeline_version}).
   */
  public String pipelineVersion() {
    return pipelineVersion;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DocumentChunk other)) {
      return false;
    }
    return index == other.index
        && pageFrom == other.pageFrom
        && pageTo == other.pageTo
        && Objects.equals(content, other.content)
        && Objects.equals(contentHash, other.contentHash)
        && Objects.equals(pipelineVersion, other.pipelineVersion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, pageFrom, pageTo, content, contentHash, pipelineVersion);
  }

  @Override
  public String toString() {
    return "DocumentChunk[index="
        + index
        + ", pageFrom="
        + pageFrom
        + ", pageTo="
        + pageTo
        + ", content='"
        + content
        + '\''
        + ", contentHash='"
        + contentHash
        + '\''
        + ", pipelineVersion='"
        + pipelineVersion
        + "'}";
  }
}
