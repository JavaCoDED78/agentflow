package com.example.agentflow.workflowdefinition.domain;

import java.util.List;
import java.util.Set;

/**
 * The closed registry of {@link WorkflowNode node types} a workflow graph may use.
 *
 * <p>This is the single source of truth for the allowed node set (S05-07). It is intentionally
 * <em>closed</em>: the vocabulary is fixed at this one place and there is no public way to add to
 * it, so a client or the untrusted planner (invariant 7 / ADR-006) cannot dynamically register its
 * own executable type. Any {@code type} that is not in {@link #KNOWN_TYPES} is rejected by the
 * pre-persistence gate {@link WorkflowGraphValidator#validate(WorkflowGraph)} — before a version is
 * serialized or an activated version is created — so an unknown type never reaches storage.
 *
 * <p>The allowed set is anchored verbatim to the domain glossary "Workflow DSL / graph" ({@code
 * RETRIEVE_CONTEXT}, {@code LLM_PLAN}, {@code TOOL_CALL}, {@code HUMAN_APPROVAL}, {@code
 * LLM_EVALUATE}, {@code FINALIZE}); keep this list in lockstep with the glossary and ADR-005.
 * Adding a node type is a real change, not a config flip: it needs a new ADR and a new registry
 * entry.
 *
 * <p>The {@code type} itself stays a plain {@code String} in {@link WorkflowNode}: it is compared
 * against this registry, never interpreted as code, and the registry holds no behaviour that
 * mutates state — so it can safely live in the domain layer and be read by the codec and parser.
 *
 * @see WorkflowGraphValidator the pre-persistence gate that enforces {@link #contains(String)}
 * @see <a href="../../../../../../../docs/adr/ADR-005-versioned-workflow-dsl.md">ADR-005</a>
 */
public final class WorkflowNodeTypeRegistry {

  /**
   * The closed vocabulary of allowed node types, anchored verbatim to the domain glossary "Workflow
   * DSL / graph" (S05-07). Declared in definition order so the registry, the glossary and the
   * contract's {@code nodeType} enum stay readable side by side.
   */
  private static final List<String> KNOWN_TYPES =
      List.of(
          "RETRIEVE_CONTEXT",
          "LLM_PLAN",
          "TOOL_CALL",
          "HUMAN_APPROVAL",
          "LLM_EVALUATE",
          "FINALIZE");

  private static final Set<String> KNOWN = Set.copyOf(KNOWN_TYPES);

  private WorkflowNodeTypeRegistry() {}

  /**
   * @param type the declared node type; may be {@code null} or blank
   * @return {@code true} only when {@code type} is one of the closed allowed set; any other value —
   *     {@code null}, blank or an unknown type such as {@code SCRIPT} — is {@code false}
   */
  public static boolean contains(String type) {
    return type != null && !type.isBlank() && KNOWN.contains(type);
  }

  /**
   * Asserts that {@code type} is a known node type.
   *
   * @param type the declared node type
   * @throws InvalidGraphException if {@code type} is {@code null}, blank or not in the closed set —
   *     thrown before a version is persisted, so a client/LLM can never register a new executable
   *     type (S05-07)
   */
  public static void assertKnown(String type) {
    if (!contains(type)) {
      throw new InvalidGraphException("unknown workflow node type: " + type);
    }
  }

  /**
   * @return an unmodifiable snapshot of the closed allowed set, anchored verbatim to the domain
   *     glossary (S05-07). Exposed only for tests and references; it is unmodifiable so it cannot
   *     be used to extend the registry at runtime.
   */
  public static List<String> knownTypes() {
    return KNOWN_TYPES;
  }
}
