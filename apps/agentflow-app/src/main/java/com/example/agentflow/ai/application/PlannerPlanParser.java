package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlannerPlan;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the planner's completion text into a validated {@link PlannerPlan} (S09-06).
 *
 * <p>This mirrors {@code workflowdefinition}'s {@code WorkflowGraphCodec}: it deserializes JSON
 * into a value object with the Jackson 3 {@link ObjectMapper} ({@code tools.jackson}), and lets the
 * value type's canonical constructor enforce its invariants. A {@code null} {@code dependsOn}/
 * {@code arguments}/{@code steps} (the Planner, or the JSON, omits them) defaults to an empty,
 * unmodifiable container rather than failing the read — exactly what {@link PlannerPlan} promises.
 *
 * <p>Anything that is not a well-formed, structurally valid plan is a {@link
 * PlannerPlanParseException}: an unparseable payload is a {@link JacksonException} (malformed
 * JSON), a structurally invalid graph is the plan's canonical-construction failure ({@link
 * IllegalArgumentException}). Both are one clear signal, so the caller never has to distinguish a
 * formatting fault from a structural one.
 */
public final class PlannerPlanParser {

  private final ObjectMapper mapper;

  /**
   * @param mapper the Jackson 3 mapper ({@code tools.jackson}); never {@code null}
   */
  public PlannerPlanParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Parses the planner's completion into a plan.
   *
   * @param json the completion text
   * @return the validated plan
   * @throws PlannerPlanParseException if {@code json} is not valid plan JSON, or a structurally
   *     invalid plan (duplicate / dangling / self dependency)
   */
  public PlannerPlan parse(String json) {
    try {
      return mapper.readValue(json, PlannerPlan.class);
    } catch (JacksonException e) {
      throw new PlannerPlanParseException(
          "planner response is not valid plan JSON: " + e.getMessage(),
          PlannerFailure.MALFORMED_JSON,
          e);
    } catch (IllegalArgumentException e) {
      throw new PlannerPlanParseException(
          "planner response is not a valid plan: " + e.getMessage(),
          PlannerFailure.SCHEMA_MISMATCH,
          e);
    }
  }
}
