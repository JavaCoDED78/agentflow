package com.example.agentflow.workflowdefinition.domain;

import java.util.List;

/**
 * A single, structured validation failure found while validating a {@link WorkflowGraph}: the JSON
 * path it comes from ({@code nodes[i]}, {@code nodes[i].id}, {@code edges[j]}, {@code
 * edges[j].from}, …) and a human-readable {@code message}.
 *
 * <p>It is the value carried by the collectable validation paths (S05-13). The throw-based gate
 * ({@link WorkflowGraphValidator#validate(WorkflowGraph)}) reports only the first issue; the
 * validation endpoint ({@link
 * com.example.agentflow.workflowdefinition.application.GraphValidationService}) collects every
 * issue so a client receives the full, structured set of problems with their paths. Whole-graph
 * rules that are not tied to one element use {@code "$"} as their path.
 *
 * <p>Kept in the domain layer so the parser (input boundary) and the validator (invariants) both
 * produce and consume it without crossing into the application layer.
 *
 * @param path the JSON path of the offending element (e.g. {@code nodes[1].type}); {@code "$"} for
 *     a whole-graph rule
 * @param message a human-readable description of the problem
 */
public record GraphValidationIssue(String path, String message) {

  /**
   * @param path the JSON path of the offending element, or {@code "$"} for a whole-graph rule
   * @param message a human-readable description of the problem
   * @return a validation issue
   */
  public static GraphValidationIssue of(String path, String message) {
    return new GraphValidationIssue(path, message);
  }

  /**
   * A whole-graph issue (an empty graph, a size limit) that is not tied to a single element.
   *
   * @param message a human-readable description of the problem
   * @return a validation issue with the {@code "$"} path
   */
  public static GraphValidationIssue wholeGraph(String message) {
    return new GraphValidationIssue("$", message);
  }

  /**
   * @param issues the issues to copy (may be {@code null} or empty)
   * @return an unmodifiable snapshot of the given issues
   */
  public static List<GraphValidationIssue> copyOf(List<GraphValidationIssue> issues) {
    return issues == null ? List.of() : List.copyOf(issues);
  }
}
