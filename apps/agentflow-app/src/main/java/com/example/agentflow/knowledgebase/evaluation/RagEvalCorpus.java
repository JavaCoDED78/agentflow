package com.example.agentflow.knowledgebase.evaluation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fixed RAG evaluation corpus (S10-20): two tenant fixture ids (the eval tenant and a foreign
 * "decoy" tenant), three synthetic sample documents owned by the eval tenant, their indexed chunks
 * with page references, one maximally-close foreign chunk, and fifteen fixed questions with their
 * reference chunks — the fixed set every retrieval change is compared against (the S10-20
 * acceptance).
 *
 * <p>All documents are <b>synthetic</b>, authored for this eval: no third-party text, no personal
 * data, no secrets — the "разрешённых sample documents" the plan asks for. Every id is a labelled
 * fixture constant, so a report produced against the corpus is reproducible byte for byte.
 *
 * <p>The corpus exercises four retrieval behaviours on one fixed set:
 *
 * <ul>
 *   <li><b>recall</b> — the twelve evidence questions name the chunk(s) the answer must be found
 *       in; recall@k scores whether the hybrid retrieval surfaces them in the top-k;
 *   <li><b>tenant isolation</b> — {@code a-tenant-predicate-placement} vectorises closest to the
 *       <em>foreign</em> decoy chunk (its wording deliberately mirrors the owned fragment), so it
 *       passes only while the tenant predicate keeps the foreign chunk out of the result;
 *   <li><b>no evidence</b> — {@code x-no-evidence} asks about nothing in the corpus and must report
 *       {@code InsufficientEvidence}, never an invented citation;
 *   <li><b>citation correctness</b> — every citation the runner builds must resolve to an owned
 *       corpus chunk with the exact document / page metadata the fixture fixes.
 * </ul>
 *
 * <p>The embedding of every chunk and query is the deterministic {@link RagEvalEmbedding} — a pure
 * function, no model, no network — and the corpus pins the {@link #embeddingModel()} label and
 * {@link #evalTopK()} bound the eval wiring must use. A plain value: no Spring, no I/O, no clock,
 * no randomness (invariant 10).
 */
public final class RagEvalCorpus {

  /** The eval fixture tenant — a labelled fixture id, never an authorization source. */
  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-00000000e710");

  /** The foreign "decoy" tenant owning the maximally-close isolation chunk. */
  private static final UUID FOREIGN_TENANT =
      UUID.fromString("00000000-0000-0000-0000-00000000f710");

  /** The eval fixture knowledge base of the eval tenant. */
  private static final UUID KNOWLEDGE_BASE =
      UUID.fromString("00000000-0000-0000-0000-00000000b710");

  /** The embedding-model label the eval wiring resolves for the fixture collection. */
  private static final String EMBEDDING_MODEL = "rag-eval-hash-768/v1";

  /** The pipeline version stamped on every fixture chunk. */
  private static final String PIPELINE_VERSION = "rag-eval-pipeline-v1";

  /** The top-k the runner requests — also the {@code max-top-k} the eval wiring configures. */
  private static final int EVAL_TOP_K = 10;

  private final List<RagEvalDocument> documents;
  private final List<RagEvalChunk> chunks;
  private final List<RagEvalChunk> foreignChunks;
  private final List<RagEvalQuestion> questions;
  private final Map<UUID, RagEvalChunk> ownedChunkById;

  private RagEvalCorpus(
      List<RagEvalDocument> documents,
      List<RagEvalChunk> chunks,
      List<RagEvalChunk> foreignChunks,
      List<RagEvalQuestion> questions) {
    this.documents = List.copyOf(documents);
    this.chunks = List.copyOf(chunks);
    this.foreignChunks = List.copyOf(foreignChunks);
    this.questions = List.copyOf(questions);
    Map<UUID, RagEvalChunk> byId = new HashMap<>(chunks.size());
    for (RagEvalChunk chunk : chunks) {
      byId.put(chunk.id(), chunk);
    }
    this.ownedChunkById = Map.copyOf(byId);
  }

  /**
   * @return the fixed corpus: three owned sample documents, thirteen owned chunks, one foreign
   *     decoy chunk and fifteen questions; never {@code null}
   */
  public static RagEvalCorpus baseline() {
    RagEvalDocument docA =
        new RagEvalDocument(
            UUID.fromString("00000000-0000-0000-0000-00000000d0a0"),
            "agentflow-retrieval-runbook.md",
            3);
    RagEvalDocument docB =
        new RagEvalDocument(
            UUID.fromString("00000000-0000-0000-0000-00000000d0b0"),
            "warehouse-inventory-policy.md",
            2);
    RagEvalDocument docC =
        new RagEvalDocument(
            UUID.fromString("00000000-0000-0000-0000-00000000d0c0"),
            "q3-platform-uptime-report.md",
            2);
    RagEvalDocument docForeign =
        new RagEvalDocument(
            UUID.fromString("00000000-0000-0000-0000-00000000d0f0"),
            "tenant-isolation-brief.md",
            1);

    List<RagEvalChunk> owned = new ArrayList<>();
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c0",
            docA,
            TENANT,
            1,
            1,
            "The retrieval service must resolve the knowledge base embedding model before"
                + " searching. A cross-tenant knowledge base is indistinguishable from a missing"
                + " one: the lookup runs by the (tenant_id, id) pair, so a foreign collection"
                + " yields an empty result instead of an error."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c1",
            docA,
            TENANT,
            1,
            1,
            "The vector half embeds the query through the neutral embedding port and runs a"
                + " nearest-neighbour search. The tenant and collection predicates run inside"
                + " the query before top-k, so a foreign chunk can never surface no matter how"
                + " close its embedding is."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c2",
            docA,
            TENANT,
            2,
            2,
            "The lexical half matches chunk text with an english full-text index. The keyword is"
                + " tokenised with plainto_tsquery, so an untrusted search string can never"
                + " become a malformed operator."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c3",
            docA,
            TENANT,
            2,
            2,
            "Hybrid fusion combines the two halves with reciprocal-rank fusion: the score is one"
                + " divided by K plus rank, summed over the halves. A chunk matched by both"
                + " halves outranks one matched by a single half, and the fused list is"
                + " deduplicated by chunk id and capped at max-top-k."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c4",
            docA,
            TENANT,
            3,
            3,
            "A candidate whose cosine distance exceeds the configured threshold is dropped. The"
                + " default top-k bounds the result size, and a caller supplied top-k is clamped"
                + " to the configured maximum."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000a0c5",
            docA,
            TENANT,
            3,
            3,
            "Citations are built one per retrieved chunk and never invented. An empty retrieval"
                + " reports insufficient evidence, so the model answers without a source rather"
                + " than fabricating one."));

    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000b0c0",
            docB,
            TENANT,
            1,
            1,
            "Cycle counting audits a subset of bins every week. A bin counted in week N must not"
                + " be recounted within eight weeks, and a count variance above two percent opens"
                + " a recount task."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000b0c1",
            docB,
            TENANT,
            1,
            1,
            "The reorder point of a stock keeping unit is average daily demand multiplied by the"
                + " supplier lead time in days, plus safety stock. When the on-hand amount falls"
                + " to the reorder point, a purchase requisition is raised automatically."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000b0c2",
            docB,
            TENANT,
            2,
            2,
            "Damaged goods are moved to the quarantine zone within one hour of discovery."
                + " Quarantined stock is excluded from the available-to-promise figure until"
                + " quality releases it."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000b0c3",
            docB,
            TENANT,
            2,
            2,
            "Picking follows first-in first-out within each zone. Lots with an earlier received"
                + " date ship first, and lot splitting is allowed only when the full lot exceeds"
                + " the order amount."));

    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000c0c0",
            docC,
            TENANT,
            1,
            1,
            "The platform recorded 99.95 percent uptime in the third quarter across all regions."
                + " The europe region logged 43 minutes of planned maintenance, the only"
                + " downtime recorded in the quarter."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000c0c1",
            docC,
            TENANT,
            2,
            2,
            "Two incidents breached the five minute alert threshold in the third quarter."
                + " Incident 4711, a stuck consumer lag on the ingestion topic, was mitigated by"
                + " redriving the dead-letter queue."));
    owned.add(
        chunk(
            "00000000-0000-0000-0000-00000000c0c2",
            docC,
            TENANT,
            2,
            2,
            "No customer data was lost in the third quarter. Every recovery point objective was"
                + " met, and the worst restore took eleven minutes against a four hour"
                + " objective."));

    // The decoy: owned by the FOREIGN tenant, worded to vectorise maximally close to the
    // a-tenant-predicate-placement question — retrieval passes only while the tenant predicate
    // (applied inside the query, never as a post-filter) keeps it out of the result.
    List<RagEvalChunk> foreign =
        List.of(
            chunk(
                "00000000-0000-0000-0000-00000000f0c0",
                docForeign,
                FOREIGN_TENANT,
                1,
                1,
                "The tenant filter predicates run inside the nearest-neighbour query before"
                    + " top-k. Chunks of one tenant are never returned to another tenant, even"
                    + " when their embeddings are maximally close."));

    List<RagEvalQuestion> questions =
        List.of(
            question(
                "a-model-resolution",
                "How does the retrieval service resolve the embedding model before searching?",
                true,
                "00000000-0000-0000-0000-00000000a0c0"),
            question(
                "a-cross-tenant-empty",
                "What happens when a search runs against another tenant knowledge base?",
                true,
                "00000000-0000-0000-0000-00000000a0c0"),
            question(
                "a-tenant-predicate-placement",
                "Where do the tenant filter predicates run in the nearest-neighbour search?",
                true,
                "00000000-0000-0000-0000-00000000a0c1"),
            question(
                "a-lexical-tokenisation",
                "How is an untrusted keyword tokenised for the full-text search?",
                true,
                "00000000-0000-0000-0000-00000000a0c2"),
            question(
                "a-hybrid-fusion",
                "How does hybrid fusion rank a chunk that both halves matched?",
                true,
                "00000000-0000-0000-0000-00000000a0c3"),
            question(
                "a-distance-threshold",
                "What happens to a candidate whose cosine distance exceeds the configured"
                    + " threshold?",
                true,
                "00000000-0000-0000-0000-00000000a0c4"),
            question(
                "a-insufficient-evidence",
                "What does an empty retrieval report instead of citations?",
                true,
                "00000000-0000-0000-0000-00000000a0c5"),
            question(
                "b-cycle-count",
                "When does a cycle count variance open a recount task?",
                true,
                "00000000-0000-0000-0000-00000000b0c0"),
            question(
                "b-reorder-point",
                "How is the reorder point of a stock keeping unit calculated?",
                true,
                "00000000-0000-0000-0000-00000000b0c1"),
            question(
                "b-quarantine",
                "How is quarantined stock treated in the available-to-promise figure?",
                true,
                "00000000-0000-0000-0000-00000000b0c2"),
            question(
                "b-fifo",
                "Which lot ships first when picking an order?",
                true,
                "00000000-0000-0000-0000-00000000b0c3"),
            question(
                "c-uptime",
                "What uptime did the platform record in the third quarter?",
                true,
                "00000000-0000-0000-0000-00000000c0c0"),
            question(
                "c-incident",
                "How was incident 4711 mitigated?",
                true,
                "00000000-0000-0000-0000-00000000c0c1"),
            question(
                "c-recovery",
                "Were any recovery point objectives missed in the third quarter?",
                true,
                "00000000-0000-0000-0000-00000000c0c2"),
            question(
                "x-no-evidence",
                "What is the maternity leave duration for warehouse staff?",
                false));

    return new RagEvalCorpus(List.of(docA, docB, docC, docForeign), owned, foreign, questions);
  }

  private static RagEvalChunk chunk(
      String id,
      RagEvalDocument document,
      UUID tenantId,
      int pageFrom,
      int pageTo,
      String content) {
    return new RagEvalChunk(
        UUID.fromString(id), tenantId, document.id(), document.name(), pageFrom, pageTo, content);
  }

  private static RagEvalQuestion question(
      String id, String query, boolean expectsEvidence, String... referenceChunkIds) {
    List<UUID> references = new ArrayList<>(referenceChunkIds.length);
    for (String reference : referenceChunkIds) {
      references.add(UUID.fromString(reference));
    }
    return new RagEvalQuestion(id, query, java.util.Set.copyOf(references), expectsEvidence);
  }

  /**
   * @return the eval fixture tenant; never {@code null}
   */
  public UUID tenantId() {
    return TENANT;
  }

  /**
   * @return the foreign decoy tenant; never {@code null}
   */
  public UUID foreignTenantId() {
    return FOREIGN_TENANT;
  }

  /**
   * @return the eval fixture knowledge base of {@link #tenantId()}; never {@code null}
   */
  public UUID knowledgeBaseId() {
    return KNOWLEDGE_BASE;
  }

  /**
   * @return the embedding-model label the eval wiring must resolve; never {@code null}
   */
  public String embeddingModel() {
    return EMBEDDING_MODEL;
  }

  /**
   * @return the pipeline version stamped on every fixture chunk; never {@code null}
   */
  public String pipelineVersion() {
    return PIPELINE_VERSION;
  }

  /**
   * @return the top-k the runner requests (the eval wiring's {@code max-top-k}); at least 1
   */
  public int evalTopK() {
    return EVAL_TOP_K;
  }

  /**
   * @return all corpus documents (owned and foreign); never {@code null}
   */
  public List<RagEvalDocument> documents() {
    return documents;
  }

  /**
   * @return the eval tenant's chunks; never {@code null}
   */
  public List<RagEvalChunk> chunks() {
    return chunks;
  }

  /**
   * @return the foreign tenant's decoy chunks; never {@code null}
   */
  public List<RagEvalChunk> foreignChunks() {
    return foreignChunks;
  }

  /**
   * @return every chunk any tenant owns — the fixture store the in-memory wiring searches
   */
  public List<RagEvalChunk> allChunks() {
    List<RagEvalChunk> all = new ArrayList<>(chunks.size() + foreignChunks.size());
    all.addAll(chunks);
    all.addAll(foreignChunks);
    return List.copyOf(all);
  }

  /**
   * @return the fixed questions, in stable report order; never {@code null}
   */
  public List<RagEvalQuestion> questions() {
    return questions;
  }

  /**
   * Look up one <em>owned</em> corpus chunk — the lookup the citation-correctness metric resolves
   * citations against. A foreign or unknown id yields {@code null}, so a citation pointing at one
   * can never count as correct.
   *
   * @param chunkId the chunk id to resolve
   * @return the owned chunk, or {@code null} for a foreign / unknown id
   */
  public RagEvalChunk ownedChunkById(UUID chunkId) {
    return chunkId == null ? null : ownedChunkById.get(chunkId);
  }
}
