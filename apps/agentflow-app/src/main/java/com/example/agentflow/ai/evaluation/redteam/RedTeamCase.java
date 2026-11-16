package com.example.agentflow.ai.evaluation.redteam;

import java.util.List;
import java.util.Objects;

/**
 * One red-team fixture for the Planner (S11-11): a fixed untrusted document that tries to override
 * the system rules, disclose a secret, or invoke a tool / capability the backend did not grant,
 * plus the tool allowlist the backend hands the Planner for the case and the {@link
 * RedTeamInjection} vector the document exercises.
 *
 * <p>A case is pure fixture data, exactly as a caller-supplied document is injected into the
 * Planner's {@code USER} turn: {@link #document()} is the untrusted content the Planner proposes
 * for, {@link #allowedTools()} is the closed allowlist the backend gives the Planner (the
 * permission set the document tries to widen), and {@link #vector()} names the red-team attack. The
 * {@link #title()} is a human label for the fixture; it carries no document text and is safe to
 * render (invariant 8).
 *
 * <p>This is a pure, immutable value type (no Spring / I/O, reads neither time nor randomness,
 * invariant 10).
 *
 * @param id the stable case id (a slug); non-null, non-blank
 * @param title a short human label for the fixture; non-null, non-blank
 * @param document the fixed untrusted document the Planner proposes for; non-null, non-blank — the
 *     prompt-injection surface
 * @param allowedTools the tool allowlist the backend hands the Planner for this case; {@code null}
 *     defaults to empty (no tool reference permitted), the fixed permission set the document tries
 *     to widen
 * @param vector the red-team injection vector the document exercises; never {@code null}
 */
public record RedTeamCase(
    String id, String title, String document, List<String> allowedTools, RedTeamInjection vector) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if {@code document} or {@code vector} is {@code null}
   * @throws IllegalArgumentException if {@code id}, {@code title} or {@code document} is blank
   */
  public RedTeamCase {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("case id must not be null or blank");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("case title must not be null or blank");
    }
    Objects.requireNonNull(document, "document must not be null");
    if (document.isBlank()) {
      throw new IllegalArgumentException("document must not be blank");
    }
    allowedTools = List.copyOf(allowedTools == null ? List.of() : allowedTools);
    Objects.requireNonNull(vector, "vector must not be null");
  }
}
