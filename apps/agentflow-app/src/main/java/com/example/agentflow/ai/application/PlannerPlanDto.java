package com.example.agentflow.ai.application;

import com.example.agentflow.ai.domain.plan.PlannerPlan;
import java.util.List;
import java.util.Map;

/**
 * The {@code ai} module's public boundary type for a proposed plan (S09-06): the model-to-DTO
 * mapping the S09-05 changelog promises.
 *
 * <p>It is a serializable, provider-agnostic mirror of {@link PlannerPlan} that carries only plain
 * types ({@code String}, {@code List}, {@code Map}, number, boolean, {@code null}), so another
 * module can reference a plan through {@code ai.application} without importing the {@code
 * ai.domain.plan} value types, which are internal (the {@code ai} package boundary, {@code
 * ADR-006}). The component set is exactly the plan's: an ordered {@code steps} list whose entries
 * carry {@code id / type / toolId / dependsOn / arguments} — nothing more — so the DTO exposes no
 * place for chain-of-thought or an executable payload to hide.
 *
 * <p>The {@link #from(PlannerPlan)} mapping is the model-to-DTO mapping: it snapshots the validated
 * domain plan into this boundary shape. The {@code dependsOn} and {@code arguments} come from the
 * domain value object already defaulted to empty, unmodifiable containers by {@link PlannerPlan}
 * ({@code ADR-006} / S09-05), so a step with no predecessor or arguments is represented as an empty
 * container, never a {@code null}.
 */
public record PlannerPlanDto(List<PlanStepDto> steps) {

  /**
   * One step of a {@link PlannerPlanDto}, mirroring {@link
   * com.example.agentflow.ai.domain.plan.PlanStep}.
   */
  public record PlanStepDto(
      String id,
      String type,
      String toolId,
      List<String> dependsOn,
      Map<String, Object> arguments) {}

  /**
   * Maps a validated {@link PlannerPlan} into its boundary representation.
   *
   * @param plan the plan to expose across the module boundary; may be {@code null}
   * @return the DTO form of the plan; {@code null} defaults to an empty plan
   */
  public static PlannerPlanDto from(PlannerPlan plan) {
    if (plan == null) {
      return new PlannerPlanDto(List.of());
    }
    List<PlanStepDto> steps =
        plan.steps().stream()
            .map(
                step ->
                    new PlanStepDto(
                        step.id(), step.type(), step.toolId(), step.dependsOn(), step.arguments()))
            .toList();
    return new PlannerPlanDto(steps);
  }
}
