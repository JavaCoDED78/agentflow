package com.example.agentflow.workflowdefinition.application;

import java.util.List;

/**
 * The result of validating a raw workflow-graph payload against a definition (S05-13).
 *
 * <p>It is the HTTP contract of {@code POST /api/v1/workflows/{id}/validate}: the endpoint returns
 * this object for every request — {@code 200} with {@code valid = true} and no violations when the
 * graph is structurally sound, or {@code 200} with {@code valid = false} and the full, ordered list
 * of {@link Violation}s (each a JSON {@code path} plus a human {@code message}) when it is not. The
 * endpoint never creates a version: validation is a read-only preview, so a client can confirm
 * validity or fix its payload before {@code POST /api/v1/workflows/{id}/versions}.
 *
 * <p>The {@code violations} carry the JSON path of the offending element ({@code nodes[1].type},
 * {@code edges[0].from}, …) so a client can locate and fix each problem; whole-graph rules use the
 * {@code "$"} path.
 */
public record GraphValidationResult(boolean valid, List<Violation> violations) {

  /**
   * One structured validation problem: the JSON path it comes from and a human-readable message.
   *
   * @param path the JSON path of the offending element ({@code nodes[i]}, {@code edges[j]}, …) or
   *     {@code "$"} for a whole-graph rule
   * @param message a human-readable description of the problem
   */
  public record Violation(String path, String message) {}

  /**
   * @param violations the collected issues, in check order (may be empty, meaning the graph was
   *     valid)
   * @return a result reporting the collected violations ({@code valid = false}); the list is copied
   *     and made unmodifiable
   */
  public static GraphValidationResult invalid(List<Violation> violations) {
    return new GraphValidationResult(false, List.copyOf(violations));
  }
}
