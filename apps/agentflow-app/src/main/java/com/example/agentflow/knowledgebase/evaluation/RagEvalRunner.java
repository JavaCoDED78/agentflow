package com.example.agentflow.knowledgebase.evaluation;

import com.example.agentflow.knowledgebase.application.CitationDto;
import com.example.agentflow.knowledgebase.application.GroundedAnswer;
import com.example.agentflow.knowledgebase.application.GroundingPolicy;
import com.example.agentflow.knowledgebase.application.RetrievalService;
import com.example.agentflow.knowledgebase.application.SearchRequest;
import com.example.agentflow.knowledgebase.application.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The RAG baseline eval runner (S10-20): it drives the production {@link RetrievalService} through
 * the fixed {@link RagEvalCorpus} questions and scores every case on the retrieval metrics —
 * recall@1/3/5/10 against the reference chunks, citation correctness, the evidence verdict — so a
 * retrieval change (a retuned threshold, a fusion change, a new chunking or embedding pipeline) is
 * compared against the baseline on identical questions and expectations (the S10-20 acceptance).
 *
 * <p>The runner measures the <em>real</em> path, not a parallel one: each case goes through {@link
 * RetrievalService#hybridSearch} (the vector half + the lexical half + the reciprocal-rank fusion,
 * S10-14/15/16), the citations are built by the production {@link RetrievalService#citations} (the
 * S10-17 layer, one citation per real retrieved chunk), and the evidence verdict is the production
 * {@link GroundingPolicy#DEFAULT} the service itself falls back to (S10-18). The tenant and
 * knowledge base are the corpus's labelled fixture ids: the runner serves no request and reads no
 * security context, and tenant scoping is exercised by the decoy case and the repository ITs, not
 * re-derived here (invariant 1).
 *
 * <p>The eval embedding is the deterministic {@link RagEvalEmbedding}: the caller wires the {@code
 * ai} {@code EmbeddingPort} to it in the DB-free test, and an opt-in run against a real provider
 * follows the same runner with a provider-backed {@link RetrievalService}. This is a plain,
 * deterministic value (no Spring bean, no I/O, no clock, no randomness, invariant 10).
 *
 * <p>Nothing here logs or renders document text: snippets are compared against the corpus by id and
 * page only, and the report carries ids, counts and verdicts (invariants 7/8/9).
 */
public final class RagEvalRunner {

  /**
   * The fixed search id every grounding decision reports under — correlation only, never the query
   * text, so a stable constant keeps the rendering byte-stable.
   */
  private static final UUID SEARCH_ID = UUID.fromString("00000000-0000-0000-0000-00000000e5ea");

  /** The recall depth a case must reach to pass: every reference chunk inside the top-5. */
  private static final int TARGET_K = 5;

  private final RetrievalService retrieval;
  private final RagEvalCorpus corpus;
  private final GroundingPolicy policy;

  /**
   * @param retrieval the production retrieval configuration under test; never {@code null}
   * @param corpus the fixed eval corpus; never {@code null}
   * @throws IllegalArgumentException if an argument is {@code null}
   */
  public RagEvalRunner(RetrievalService retrieval, RagEvalCorpus corpus) {
    if (retrieval == null) {
      throw new IllegalArgumentException("retrieval must not be null");
    }
    if (corpus == null) {
      throw new IllegalArgumentException("corpus must not be null");
    }
    this.retrieval = retrieval;
    this.corpus = corpus;
    this.policy = GroundingPolicy.DEFAULT;
  }

  /**
   * Run the fixed {@link RagEvalCorpus#baseline()} question set.
   *
   * @return the scored report; never {@code null}
   */
  public RagEvalReport runBaseline() {
    return run(corpus.questions());
  }

  /**
   * Score the given questions, in order, one result per case.
   *
   * @param questions the questions to score; never {@code null}
   * @return the scored report; never {@code null}
   * @throws IllegalArgumentException if {@code questions} is {@code null}
   */
  public RagEvalReport run(List<RagEvalQuestion> questions) {
    if (questions == null) {
      throw new IllegalArgumentException("questions must not be null");
    }
    List<RagEvalCaseResult> results = new ArrayList<>(questions.size());
    for (RagEvalQuestion question : questions) {
      results.add(runCase(question));
    }
    return new RagEvalReport(results);
  }

  private RagEvalCaseResult runCase(RagEvalQuestion question) {
    SearchRequest request = new SearchRequest(question.query(), corpus.evalTopK());

    SearchResult hybrid =
        retrieval.hybridSearch(corpus.tenantId(), corpus.knowledgeBaseId(), request);
    List<UUID> rankedIds = hybrid.chunks().stream().map(chunk -> chunk.id()).toList();
    List<CitationDto> citations =
        retrieval.citations(corpus.tenantId(), corpus.knowledgeBaseId(), request);
    GroundedAnswer answer = policy.decide(citations, SEARCH_ID);

    double recallAt1 = recallAt(question, rankedIds, 1);
    double recallAt3 = recallAt(question, rankedIds, 3);
    double recallAt5 = recallAt(question, rankedIds, 5);
    double recallAt10 = recallAt(question, rankedIds, 10);
    boolean citationsCorrect = citationsCorrect(hybrid, citations);
    boolean evidencePolicyMet = evidencePolicyMet(question, answer);
    boolean passed =
        (question.expectsEvidence() ? recallAt5 >= 1.0 : true)
            && citationsCorrect
            && evidencePolicyMet;

    return new RagEvalCaseResult(
        question.id(),
        question.referenceChunkIds().size(),
        hybrid.chunks().size(),
        recallAt1,
        recallAt3,
        recallAt5,
        recallAt10,
        citationsCorrect,
        evidencePolicyMet,
        passed);
  }

  /**
   * The fraction of the question's reference chunks inside the first {@code k} retrieved ids —
   * {@code 1.0} vacuously when the question names no reference (the no-evidence case scores its
   * retrieval as empty, not as a recall miss).
   */
  private static double recallAt(RagEvalQuestion question, List<UUID> rankedIds, int k) {
    if (question.referenceChunkIds().isEmpty()) {
      return 1.0;
    }
    Set<UUID> top = new HashSet<>(rankedIds.subList(0, Math.min(k, rankedIds.size())));
    long found = question.referenceChunkIds().stream().filter(top::contains).count();
    return (double) found / question.referenceChunkIds().size();
  }

  /**
   * Citation correctness (the S10-17 acceptance restated for the corpus): one citation per
   * retrieved chunk, every citation resolving to an <em>owned</em> corpus chunk whose document /
   * page metadata matches the fixture exactly — so a citation can never point at a chunk the
   * retrieval did not return or another tenant owns, and an empty retrieval yields no citation at
   * all.
   */
  private boolean citationsCorrect(SearchResult hybrid, List<CitationDto> citations) {
    if (citations.size() != hybrid.chunks().size()) {
      return false;
    }
    Set<UUID> retrievedIds = new HashSet<>();
    hybrid.chunks().forEach(chunk -> retrievedIds.add(chunk.id()));
    for (CitationDto citation : citations) {
      RagEvalChunk chunk = corpus.ownedChunkById(citation.chunkId());
      if (chunk == null || !retrievedIds.contains(citation.chunkId())) {
        return false;
      }
      if (!chunk.documentId().equals(citation.documentId())
          || !chunk.documentName().equals(citation.documentName())
          || chunk.pageFrom() != citation.pageFrom()
          || chunk.pageTo() != citation.pageTo()) {
        return false;
      }
    }
    return true;
  }

  /**
   * The evidence-policy verdict (S10-18): an evidence question must ground ({@code Grounded} with
   * real citations), the off-topic fixture must report an evidence gap — never an invented
   * citation.
   */
  private static boolean evidencePolicyMet(RagEvalQuestion question, GroundedAnswer answer) {
    if (question.expectsEvidence()) {
      return answer instanceof GroundedAnswer.Grounded grounded && !grounded.citations().isEmpty();
    }
    return answer instanceof GroundedAnswer.InsufficientEvidence;
  }
}
