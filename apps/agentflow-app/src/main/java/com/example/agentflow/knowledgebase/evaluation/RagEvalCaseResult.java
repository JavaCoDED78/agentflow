package com.example.agentflow.knowledgebase.evaluation;

/**
 * The scored outcome of one RAG eval case (S10-20): the retrieval metrics the plan fixes — recall@k
 * against the reference chunks — plus the two correctness metrics the platform invariants demand,
 * citation correctness and the evidence-policy verdict.
 *
 * @param caseId the stable case id (never the query text)
 * @param referenceCount how many reference chunks the question names (0 for the no-evidence case)
 * @param retrievedCount how many chunks the hybrid retrieval returned
 * @param recallAt1 the fraction of reference chunks present in the top-1 ({@code 1.0} vacuously
 *     when the question names no reference)
 * @param recallAt3 the fraction of reference chunks present in the top-3
 * @param recallAt5 the fraction of reference chunks present in the top-5
 * @param recallAt10 the fraction of reference chunks present in the top-10
 * @param citationsCorrect {@code true} when every built citation resolves to an owned corpus chunk
 *     with the exact document / page metadata and one citation exists per retrieved chunk — never a
 *     citation for a chunk the retrieval did not return or another tenant owns
 * @param evidencePolicyMet {@code true} when the grounding matched the expectation — {@code
 *     Grounded} for an evidence question, {@code InsufficientEvidence} for the no-evidence case
 * @param passed {@code true} when the case holds: full recall at the target depth, correct
 *     citations and the expected evidence verdict
 */
public record RagEvalCaseResult(
    String caseId,
    int referenceCount,
    int retrievedCount,
    double recallAt1,
    double recallAt3,
    double recallAt5,
    double recallAt10,
    boolean citationsCorrect,
    boolean evidencePolicyMet,
    boolean passed) {

  /** Canonical constructor. */
  public RagEvalCaseResult {
    if (caseId == null || caseId.isBlank()) {
      throw new IllegalArgumentException("caseId must not be null or blank");
    }
  }
}
