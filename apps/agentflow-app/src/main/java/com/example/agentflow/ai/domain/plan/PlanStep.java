package com.example.agentflow.ai.domain.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single step of a {@link PlannerPlan} the Planner proposes (S09-05).
 *
 * <p>A plan is a typed, ordered graph of these steps. Each step carries:
 *
 * <ul>
 *   <li>an {@code id} that is unique within the plan;
 *   <li>a {@code type} — one of the closed workflow node vocabulary ({@code RETRIEVE_CONTEXT},
 *       {@code LLM_PLAN}, {@code TOOL_CALL}, {@code HUMAN_APPROVAL}, {@code LLM_EVALUATE}, {@code
 *       FINALIZE}); the value is a plain {@code String} and is matched against the closed {@code
 *       workflowdefinition} node registry by the server-side validation gate (S09-07), never
 *       interpreted as code (invariant 7 / {@code ADR-006});
 *   <li>a {@code toolId} — only a {@code TOOL_CALL} step references a registered tool by name;
 *       other node types carry {@code null}. Referencing a tool is <em>not</em> execution and is
 *       not permission: the backend re-checks the tool registry and the caller's permissions before
 *       any step runs. A {@code null} means "this step calls no tool";
 *   <li>{@code dependsOn} — the ids of the predecessor steps whose output this step reads (empty
 *       for a step with no predecessor); and
 *   <li>{@code arguments} — the structured, typed inputs for the step.
 * </ul>
 *
 * <p>The {@code arguments} are structured data ({@code Map}/{@code List}/{@code String}/{@code
 * number}/{@code boolean}/{@code null}), never instructions: they are never executed. They are
 * validated against the referenced tool's machine-readable input JSON Schema by the validation gate
 * (S09-07), so a hostile or prompt-injected value can never become executable markup or code (the
 * same invariant that makes {@code workflowdefinition}'s {@code config} untrusted content). The
 * schema deliberately exposes <em>no</em> field for chain-of-thought, reasoning, instructions or a
 * free-text escape hatch: the component set is exactly {@code id / type / toolId / dependsOn /
 * arguments}, so there is nowhere for the model to smuggle a capability or a command.
 *
 * <p>This is a pure, immutable value type (no Spring / I/O, reads neither time nor randomness,
 * invariant 10). A {@code null} {@code dependsOn} or {@code arguments} (the Planner, or the JSON
 * deserializer in S09-06, omits them) defaults to an empty, unmodifiable container rather than
 * failing the read.
 *
 * @param id the step id; non-null, non-blank, unique within the {@link PlannerPlan}
 * @param type the node type; non-null, non-blank; one of the closed registry (validation-gate
 *     enforced, S09-07)
 * @param toolId the registered tool this step calls, or {@code null} for a non-tool step
 * @param dependsOn the ids of the predecessor steps; may be {@code null} (defaults to empty)
 * @param arguments the structured step inputs; may be {@code null} (defaults to empty)
 */
public record PlanStep(
    String id, String type, String toolId, List<String> dependsOn, Map<String, Object> arguments) {

  /**
   * Canonical constructor.
   *
   * <p>The identity ({@code id}) and the node type ({@code type}) are mandatory, so a {@code null}
   * or blank value is a contract violation. The wiring ({@code dependsOn}) and the {@code
   * arguments} are optional, so a {@code null} defaults to an empty, unmodifiable container rather
   * than failing the read (a model response omits optional fields).
   *
   * @throws NullPointerException if {@code id} or {@code type} is {@code null}
   * @throws IllegalArgumentException if {@code id} or {@code type} is blank
   */
  public PlanStep {
    Objects.requireNonNull(id, "id must not be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("step id must not be blank");
    }
    Objects.requireNonNull(type, "type must not be null");
    if (type.isBlank()) {
      throw new IllegalArgumentException("step type must not be blank");
    }
    dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
  }
}
