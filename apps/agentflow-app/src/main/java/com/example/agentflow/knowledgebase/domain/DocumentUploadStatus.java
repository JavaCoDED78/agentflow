package com.example.agentflow.knowledgebase.domain;

/**
 * The lifecycle states of an uploaded document, {@code documents.status} (the {@code S10-03} upload
 * returns the {@link #UPLOADED} state; the full state machine, the transitions and their guards
 * live in {@code docs/architecture/document-lifecycle.md}, {@code S10-01}…{@code S10-13}).
 *
 * <p>The set is the canonical vocabulary from {@code docs/domain-glossary.md} §7: {@code UPLOADED →
 * QUEUED → PROCESSING → INDEXED}, with {@code FAILED}, {@code DELETE_REQUESTED} and {@code DELETED}
 * as the failure, deletion-requested and deletion-complete states. The three terminal states
 * ({@link #INDEXED}, {@link #FAILED}, {@link #DELETED}) are absorbing: the transition table has no
 * outgoing edge from them, so a second transition on a document in a terminal state is a domain
 * error (the same guarantee the run state machine gives its terminal states).
 */
public enum DocumentUploadStatus {

  /**
   * The object is stored and the metadata row is committed, but ingestion has not been enqueued.
   */
  UPLOADED,

  /**
   * The {@code ingestion-requested} event is enqueued via the outbox; the document awaits a worker.
   */
  QUEUED,

  /** An ingestion worker holds the lease and is extracting → chunking → embedding → indexing. */
  PROCESSING,

  /** Every chunk is indexed (pgvector + FTS). The document is ready for tenant-scoped search. */
  INDEXED,

  /** Processing failed permanently, or a retryable failure exhausted its bounded retry budget. */
  FAILED,

  /**
   * Deletion was requested; the document is drained before the object and its chunks are removed.
   */
  DELETE_REQUESTED,

  /** The object was removed from object storage and its chunks deleted from PostgreSQL. */
  DELETED;

  /**
   * @return {@code true} for the terminal states ({@link #INDEXED}, {@link #FAILED}, {@link
   *     #DELETED}), which are absorbing — a document never leaves a terminal state
   */
  public boolean isTerminal() {
    return this == INDEXED || this == FAILED || this == DELETED;
  }
}
