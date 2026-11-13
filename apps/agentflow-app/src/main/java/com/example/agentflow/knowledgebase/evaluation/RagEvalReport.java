package com.example.agentflow.knowledgebase.evaluation;

import java.util.List;
import java.util.Locale;

/**
 * The scored report of an RAG eval run (S10-20): one {@link RagEvalCaseResult} per question, in
 * corpus order, plus the aggregate that answers "did this retrieval configuration hold the
 * baseline".
 *
 * <p>{@link #render()} produces a stable, plain-text rendering — no timestamps, no randomness, case
 * order fixed by the fixture, fixed {@code \n} line endings (never the platform line separator) —
 * so two runs of different retrieval configurations (a retuned threshold, a fusion change, a new
 * chunking pipeline) diff directly and the regression names the case and the metric (the S10-20
 * acceptance: retrieval changes are compared on a fixed set). The rendering carries only case ids,
 * counts and verdicts: no query text, no snippet content (invariants 7/8 — the untrusted document
 * text never reaches the report).
 *
 * @param results the per-case results in run order; {@code null} defaults to empty
 */
public record RagEvalReport(List<RagEvalCaseResult> results) {

  /** Canonical constructor. */
  public RagEvalReport {
    results = List.copyOf(results == null ? List.of() : results);
  }

  /**
   * @return the number of cases in the run
   */
  public int total() {
    return results.size();
  }

  /**
   * @return the number of cases that passed (full recall, correct citations, expected evidence)
   */
  public int passedCount() {
    return (int) results.stream().filter(RagEvalCaseResult::passed).count();
  }

  /**
   * @return {@code true} only when every case passed; an empty run passes vacuously
   */
  public boolean allPassed() {
    return results.stream().allMatch(RagEvalCaseResult::passed);
  }

  /**
   * A deterministic, diffable one-line-per-case rendering of the report, in the form:
   *
   * <pre>{@code
   * rag-eval baseline: 15/15 cases passed
   * PASS a-model-resolution refs=1 retrieved=1 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=grounded
   * PASS x-no-evidence refs=0 retrieved=0 recall@1=1.00 recall@3=1.00 recall@5=1.00 recall@10=1.00 citations=correct evidence=insufficient
   * }</pre>
   *
   * <p>Failed cases append the failing metrics ({@code citations=broken}, {@code evidence=wrong},
   * {@code recall-below-target@5}) so a diff names the regression rather than only flagging it.
   * Lines are separated by a fixed {@code \n} so the rendering is byte-identical on every OS a
   * baseline diff may be produced on.
   *
   * @return the stable text rendering; never {@code null}
   */
  public String render() {
    StringBuilder out = new StringBuilder();
    out.append("rag-eval baseline: ").append(passedCount()).append('/').append(total());
    out.append(" cases passed\n");
    for (RagEvalCaseResult result : results) {
      out.append(result.passed() ? "PASS " : "FAIL ").append(result.caseId());
      out.append(" refs=").append(result.referenceCount());
      out.append(" retrieved=").append(result.retrievedCount());
      out.append(" recall@1=").append(format(result.recallAt1()));
      out.append(" recall@3=").append(format(result.recallAt3()));
      out.append(" recall@5=").append(format(result.recallAt5()));
      out.append(" recall@10=").append(format(result.recallAt10()));
      out.append(" citations=").append(result.citationsCorrect() ? "correct" : "broken");
      out.append(" evidence=").append(result.evidencePolicyMet() ? "as-expected" : "wrong");
      if (!result.passed()) {
        if (result.referenceCount() > 0 && result.recallAt5() < 1.0) {
          out.append(" recall-below-target@5");
        }
      }
      out.append('\n');
    }
    return out.toString();
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
