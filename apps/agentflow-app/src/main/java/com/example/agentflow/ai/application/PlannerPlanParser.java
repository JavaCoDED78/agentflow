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
 * IllegalArgumentException}). Jackson 3 wraps the record canonical-constructor violation in a
 * {@code ValueInstantiationException} — itself a {@link JacksonException} — so a structural
 * violation arrives as the <em>cause</em> of the parsing exception, never as a raw {@link
 * IllegalArgumentException}; {@link #parse(String)} walks the cause chain to keep the documented
 * distinction ({@link PlannerFailure#SCHEMA_MISMATCH} for well-formed JSON that violates a plan
 * invariant, {@link PlannerFailure#MALFORMED_JSON} for content that is not JSON at all). Both are
 * one clear signal, so the caller never has to distinguish a formatting fault from a structural
 * one.
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
   * @param json the completion text; must not be {@code null}
   * @return the validated plan
   * @throws IllegalArgumentException if {@code json} is {@code null}
   * @throws PlannerPlanParseException if {@code json} is not valid plan JSON, or a structurally
   *     invalid plan (duplicate / dangling / self dependency)
   */
  public PlannerPlan parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("json must not be null");
    }
    try {
      return mapper.readValue(json, PlannerPlan.class);
    } catch (JacksonException e) {
      // A structural plan violation (a duplicate / dangling / self dependency) surfaces from the
      // plan's canonical constructor, which Jackson 3 wraps in a ValueInstantiationException — a
      // JacksonException — so it lands here, not in the IllegalArgumentException catch below.
      // Unwrap the cause chain to keep the documented distinction: well-formed JSON that violates
      // a plan invariant is SCHEMA_MISMATCH; content that is not well-formed JSON at all stays
      // MALFORMED_JSON.
      IllegalArgumentException structural = findCause(e, IllegalArgumentException.class);
      if (structural != null) {
        throw new PlannerPlanParseException(
            "planner response is not a valid plan: " + structural.getMessage(),
            PlannerFailure.SCHEMA_MISMATCH,
            e);
      }
      throw new PlannerPlanParseException(
          "planner response is not valid plan JSON: " + e.getMessage(),
          PlannerFailure.MALFORMED_JSON,
          e);
    } catch (IllegalArgumentException e) {
      // A direct canonical-construction failure (no Jackson wrapping on this path).
      throw new PlannerPlanParseException(
          "planner response is not a valid plan: " + e.getMessage(),
          PlannerFailure.SCHEMA_MISMATCH,
          e);
    }
  }

  /**
   * Walks the cause chain looking for a specific fault type.
   *
   * @param failure the thrown exception (never {@code null})
   * @param type the cause type to find
   * @return the first cause assignable to {@code type}, or {@code null} when the chain holds none
   */
  private static <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
    }
    return null;
  }
}
