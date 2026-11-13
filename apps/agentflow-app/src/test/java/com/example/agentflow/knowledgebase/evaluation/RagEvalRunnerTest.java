package com.example.agentflow.knowledgebase.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.ai.application.EmbeddingUsage;
import com.example.agentflow.knowledgebase.application.RetrievalService;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRow;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The offline gate of the RAG evaluation corpus (S10-20): it wires the production {@link
 * RetrievalService} to an in-memory fixture store over the {@link RagEvalCorpus} — a scripted
 * {@link EmbeddingPort} answering with the deterministic {@link RagEvalEmbedding}, and two
 * repository subclasses emulating the production query semantics (the tenant predicate applied
 * inside the query, cosine ordering, lexical token match) — and runs the {@link RagEvalRunner}
 * baseline against it. No database, no Kafka, no model, no network.
 *
 * <p>The suite pins the S10-20 acceptance — retrieval changes are compared on a fixed set: the
 * baseline passes end to end (full recall, correct citations, the expected evidence verdict for
 * every case), the foreign decoy never surfaces, the off-topic question reports an evidence gap,
 * and the rendered report is byte-stable across runs.
 */
class RagEvalRunnerTest {

  private static final double THRESHOLD = 0.85;
  private static final OffsetDateTime CREATED_AT =
      OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC);

  private final RagEvalCorpus corpus = RagEvalCorpus.baseline();
  private final RetrievalService retrieval =
      new RetrievalService(
          new InMemoryChunkRepository(corpus),
          new InMemoryKnowledgeBaseRepository(corpus),
          new EvalEmbeddingPort(corpus),
          5,
          corpus.evalTopK(),
          THRESHOLD,
          null,
          null);
  private final RagEvalRunner runner = new RagEvalRunner(retrieval, corpus);

  private RagEvalReport baseline;

  @BeforeEach
  void run_baseline() {
    baseline = runner.runBaseline();
  }

  @Test
  void the_baseline_passes_every_case() {
    assertThat(baseline.allPassed()).as(() -> "baseline report:\n" + baseline.render()).isTrue();
    assertThat(baseline.total()).isEqualTo(corpus.questions().size());
    assertThat(baseline.passedCount()).isEqualTo(corpus.questions().size());
  }

  @Test
  void every_evidence_question_reaches_full_recall_at_the_target_depth() {
    List<RagEvalCaseResult> evidenceCases =
        baseline.results().stream().filter(result -> result.referenceCount() > 0).toList();
    assertThat(evidenceCases).isNotEmpty();
    assertThat(evidenceCases)
        .allSatisfy(
            result -> {
              assertThat(result.recallAt5()).as("recall@5 of %s", result.caseId()).isEqualTo(1.0);
              assertThat(result.retrievedCount()).isGreaterThanOrEqualTo(1);
            });
  }

  @Test
  void the_foreign_decoy_chunk_is_never_retrieved_even_when_maximally_close() {
    UUID decoyChunkId = corpus.foreignChunks().get(0).id();
    RagEvalCaseResult decoyCase =
        baseline.results().stream()
            .filter(result -> result.caseId().equals("a-tenant-predicate-placement"))
            .findFirst()
            .orElseThrow();
    // The case passes (the owned fragment is recalled and cited) — which is only possible when
    // the tenant predicate keeps the closer foreign chunk out of the result.
    assertThat(decoyCase.passed()).isTrue();
    assertThat(decoyCase.recallAt1()).isEqualTo(1.0);
    assertThat(corpus.ownedChunkById(decoyChunkId)).isNull();
  }

  @Test
  void an_off_topic_question_reports_insufficient_evidence_and_invents_nothing() {
    RagEvalCaseResult noEvidenceCase =
        baseline.results().stream()
            .filter(result -> result.caseId().equals("x-no-evidence"))
            .findFirst()
            .orElseThrow();
    assertThat(noEvidenceCase.passed()).isTrue();
    assertThat(noEvidenceCase.referenceCount()).isZero();
    assertThat(noEvidenceCase.retrievedCount()).isZero();
    assertThat(noEvidenceCase.citationsCorrect()).isTrue();
  }

  @Test
  void the_report_renders_every_case_on_one_stable_line() {
    String[] lines = baseline.render().split("\n", -1);
    assertThat(lines[0])
        .isEqualTo(
            "rag-eval baseline: "
                + baseline.passedCount()
                + "/"
                + baseline.total()
                + " cases passed");
    assertThat(lines).hasSize(baseline.total() + 2); // header + one line per case + trailing empty
    for (int i = 1; i <= baseline.total(); i++) {
      assertThat(lines[i]).startsWith("PASS ").contains(" recall@5=");
    }
  }

  @Test
  void the_rendered_report_is_byte_stable_across_runs() {
    assertThat(runner.runBaseline().render()).isEqualTo(baseline.render());
  }

  @Test
  void the_runner_rejects_null_wiring() {
    assertThatThrownBy(() -> new RagEvalRunner(null, corpus))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RagEvalRunner(retrieval, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The scripted {@code ai} {@link EmbeddingPort} of the eval: every text vectorised by the
   * deterministic {@link RagEvalEmbedding}, labelled with the corpus's fixture model.
   */
  private static final class EvalEmbeddingPort implements EmbeddingPort {

    private final RagEvalCorpus corpus;

    private EvalEmbeddingPort(RagEvalCorpus corpus) {
      this.corpus = corpus;
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      return new EmbeddingResponse(
          corpus.embeddingModel(),
          request.texts().stream().map(RagEvalEmbedding::of).toList(),
          new EmbeddingUsage(0, RagEvalEmbedding.DIMENSIONS));
    }
  }

  /**
   * The in-memory {@code document_chunks} read path of the eval wiring: it emulates the production
   * query semantics — the {@code tenant_id} predicate applied <em>inside</em> the query (a foreign
   * chunk is filtered before the ordering, never returned and post-dropped), cosine-distance
   * ordering with the width guard, and the lexical token match with a rank-approximation — over the
   * corpus fixture store. Only the two retrieval reads are overridden; nothing is persisted.
   */
  private static final class InMemoryChunkRepository extends DocumentChunkRepository {

    private final RagEvalCorpus corpus;

    private InMemoryChunkRepository(RagEvalCorpus corpus) {
      super(null);
      this.corpus = corpus;
    }

    @Override
    public List<DocumentChunkRow> findNearest(
        UUID tenantId, UUID knowledgeBaseId, float[] query, int limit) {
      if (query.length != 768) {
        throw new IllegalArgumentException(
            "query vector must be 768 dimensions (nomic-embed-text), was " + query.length);
      }
      return corpus.allChunks().stream()
          .filter(chunk -> chunk.tenantId().equals(tenantId))
          .map(chunk -> row(chunk, RagEvalEmbedding.cosineDistance(chunk.vector(), query)))
          .sorted(Comparator.comparingDouble(DocumentChunkRow::distance))
          .limit(limit)
          .toList();
    }

    @Override
    public List<DocumentChunkRow> findByKeyword(
        UUID tenantId, UUID knowledgeBaseId, String keyword, int limit) {
      Set<String> keywordTokens = new HashSet<>(RagEvalEmbedding.tokens(keyword));
      return corpus.allChunks().stream()
          .filter(chunk -> chunk.tenantId().equals(tenantId))
          .map(chunk -> row(chunk, lexicalRank(chunk, keywordTokens)))
          .filter(row -> row.rank() > 0)
          .sorted(Comparator.comparingDouble(DocumentChunkRow::rank).reversed())
          .limit(limit)
          .toList();
    }

    private static double lexicalRank(RagEvalChunk chunk, Set<String> keywordTokens) {
      Set<String> chunkTokens = new HashSet<>(RagEvalEmbedding.tokens(chunk.content()));
      chunkTokens.retainAll(keywordTokens);
      return chunkTokens.size();
    }

    private static DocumentChunkRow row(RagEvalChunk chunk, double distance) {
      return new DocumentChunkRow(
          chunk.id(),
          chunk.tenantId(),
          chunk.documentId(),
          chunk.documentName(),
          "rag-eval-pipeline-v1",
          0,
          chunk.pageFrom(),
          chunk.pageTo(),
          chunk.content(),
          "rag-eval-fixture",
          "rag-eval",
          CREATED_AT,
          distance,
          0);
    }
  }

  /**
   * The in-memory {@code knowledge_bases} read path of the eval wiring: the fixture collection
   * resolves for the eval tenant, everything else (including the foreign tenant) is a miss —
   * mirroring the production {@code (tenant_id, id)} lookup.
   */
  private static final class InMemoryKnowledgeBaseRepository extends KnowledgeBaseRepository {

    private final RagEvalCorpus corpus;

    private InMemoryKnowledgeBaseRepository(RagEvalCorpus corpus) {
      super(null);
      this.corpus = corpus;
    }

    @Override
    public Optional<String> findEmbeddingModelByIdAndTenant(UUID tenantId, UUID knowledgeBaseId) {
      return corpus.tenantId().equals(tenantId) && corpus.knowledgeBaseId().equals(knowledgeBaseId)
          ? Optional.of(corpus.embeddingModel())
          : Optional.empty();
    }
  }
}
