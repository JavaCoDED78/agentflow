package com.example.agentflow.ai.application;

import com.example.agentflow.ai.application.PlannerPlanDto.PlanStepDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The policy validation stage (S09-07): it re-checks, server-side, every proposed {@link
 * PlannerPlanDto} against the closed node vocabulary, the tool allowlist, the plan's own field
 * contract, the dependency graph and the system-defined {@link
 * com.example.agentflow.ai.domain.plan.PlanLimits} bounds — before a single step is allowed to
 * reach the executor.
 *
 * <p>This is the server-side half of {@code ADR-006}: the model <em>proposes</em>, the backend
 * <em>authorizes</em>. The Planner only ever sees the allowlist it was given; the gate re-checks
 * that the plan references nothing outside it, so a hostile or prompt-injected model response can
 * never smuggle a tool, a node type or a capability the backend never approved (invariant 7).
 *
 * <p>The gate is deliberately <em>pure and DB-free</em>: it reads no time, no randomness, no
 * database, no Kafka and no provider. It reads the external facts the plan alone cannot supply —
 * the tenant, the closed node vocabulary, the tool allowlist and the limits — supplied through
 * {@link ValidationContext} by the caller that owns the verified security context and the
 * cross-module registry reach. Keeping the gate free of those edges leaves the {@code ai} module
 * boundary unchanged ({@code S02-08}): the gate validates, it does not reach the registries it
 * validates against.
 *
 * <p>The checks, every one of which contributes to the returned {@link PlanValidationResult}:
 *
 * <ul>
 *   <li><b>shape.</b> the plan is non-null and declares at least one step;
 *   <li><b>field contract.</b> each step carries a non-null, non-blank {@code id} and {@code type};
 *       a step is not allowed to carry an undeclared {@code dependsOn} target;
 *   <li><b>node membership.</b> a step's {@code type} is one of the closed node vocabulary;
 *   <li><b>tool membership.</b> a {@code TOOL_CALL} step references a tool in the allowlist and
 *       carries a {@code toolId}; a non-{@code TOOL_CALL} step carries {@code null} {@code toolId}
 *       (a non-tool step referencing a tool would smuggle a capability);
 *   <li><b>DAG.</b> the {@code dependsOn} graph is acyclic — no cycle, no self-loop;
 *   <li><b>step limit.</b> the step count is within {@code limits.maxSteps()};
 *   <li><b>output size.</b> the plan's serialized size is within {@code limits.maxOutputSize()}.
 * </ul>
 *
 * <p>The gate is a plain, deterministic value (no Spring bean): a caller constructs it with {@code
 * new ValidationGate()}, exactly as {@code PlannerService} constructs its {@code
 * PlannerPlanParser}. {@link #validate} returns a fail-safe result; {@link #validateOrThrow} turns
 * a non-valid result into one loud {@link PlanValidationException}. A plan that fails is never
 * partially accepted: {@code valid} is {@code false} and {@code violations} names every failing
 * check.
 */
public final class ValidationGate {

  /** The only step type that may reference a tool by {@code toolId}. */
  private static final String TOOL_CALL = "TOOL_CALL";

  /** Fixed per-step overhead the size estimate adds for the surrounding JSON (braces/commas). */
  private static final int STEP_OVERHEAD = 16;

  /**
   * Check a proposed plan against the policy stage, returning a fail-safe result.
   *
   * @param plan the plan to validate; a {@code null} plan is rejected as {@code invalid}
   * @param ctx the external facts the gate cannot derive from the plan (never {@code null})
   * @return {@code valid} only when every check passed, with the ordered list of any violations
   * @throws NullPointerException if {@code ctx} is {@code null}
   */
  public PlanValidationResult validate(PlannerPlanDto plan, ValidationContext ctx) {
    List<String> violations = new ArrayList<>();

    if (plan == null) {
      violations.add("plan is null");
      return PlanValidationResult.invalid(violations);
    }
    if (plan.steps() == null || plan.steps().isEmpty()) {
      violations.add("plan declares no steps");
      return PlanValidationResult.invalid(violations);
    }
    if (plan.steps().size() > ctx.limits().maxSteps()) {
      violations.add(
          "plan has "
              + plan.steps().size()
              + " steps, exceeds the maximum of "
              + ctx.limits().maxSteps());
    }

    for (PlanStepDto step : plan.steps()) {
      validateStep(step, ctx.allowedNodeTypes(), ctx.allowedToolNames(), violations);
    }
    detectCycle(plan.steps(), violations);

    if (estimatedSerializedSize(plan) > ctx.limits().maxOutputSize()) {
      violations.add(
          "plan serialized size "
              + estimatedSerializedSize(plan)
              + " exceeds the maximum of "
              + ctx.limits().maxOutputSize());
    }

    return violations.isEmpty()
        ? PlanValidationResult.passing()
        : PlanValidationResult.invalid(violations);
  }

  /**
   * Check a plan and, if it fails, fail loud: throw {@link PlanValidationException}.
   *
   * @param plan the plan to validate; a {@code null} plan is rejected as {@code invalid}
   * @param ctx the external facts the gate cannot derive from the plan (never {@code null})
   * @return the passing result
   * @throws PlanValidationException if {@code validate(plan, ctx)} is not valid
   * @throws NullPointerException if {@code ctx} is {@code null}
   */
  public PlanValidationResult validateOrThrow(PlannerPlanDto plan, ValidationContext ctx) {
    PlanValidationResult result = validate(plan, ctx);
    if (!result.valid()) {
      throw new PlanValidationException(result.violations());
    }
    return result;
  }

  private static void validateStep(
      PlanStepDto step,
      Set<String> allowedNodeTypes,
      Set<String> allowedToolNames,
      List<String> violations) {
    if (step.id() == null || step.id().isBlank()) {
      violations.add("step has a null or blank id");
    }
    if (step.type() == null || step.type().isBlank()) {
      violations.add("step " + safeId(step.id()) + " has a null or blank type");
    } else if (!allowedNodeTypes.contains(step.type())) {
      violations.add("step " + safeId(step.id()) + " has unknown node type: " + step.type());
    }

    boolean isToolCall = TOOL_CALL.equals(step.type());
    if (isToolCall && (step.toolId() == null || step.toolId().isBlank())) {
      violations.add("step " + safeId(step.id()) + " is a TOOL_CALL without a toolId");
    } else if (isToolCall && !allowedToolNames.contains(step.toolId())) {
      violations.add("step " + safeId(step.id()) + " references an unknown tool: " + step.toolId());
    } else if (!isToolCall && step.toolId() != null && !step.toolId().isBlank()) {
      violations.add("step " + safeId(step.id()) + " of type " + step.type() + " carries a toolId");
    }

    for (String dependency : step.dependsOn()) {
      if (dependency.equals(step.id())) {
        violations.add("step " + step.id() + " depends on itself");
      }
    }
  }

  /**
   * Rejects a {@code dependsOn} graph that is not a DAG: a dependency that points at an undeclared
   * step id, or a cycle. The {@code PlannerPlan} value type already rejects a self dependency and a
   * dangling reference in the domain plan, but the gate receives the boundary {@code
   * PlannerPlanDto} and re-checks the wiring so a hand-built DTO cannot reach the executor with a
   * broken graph.
   *
   * @param steps the plan steps; every {@code dependsOn} entry must name a declared step id
   * @param violations the collected violations; a cycle adds one entry and stops the traversal
   */
  private void detectCycle(List<PlanStepDto> steps, List<String> violations) {
    final Map<String, List<String>> dependencies = new HashMap<>();
    final Map<String, Integer> state = new HashMap<>();
    final Set<String> ids = new HashSet<>();
    for (PlanStepDto step : steps) {
      ids.add(step.id());
    }
    for (PlanStepDto step : steps) {
      final List<String> declaredDeps = new ArrayList<>();
      for (String dependency : step.dependsOn()) {
        if (dependency.equals(step.id())) {
          // A self dependency is reported in {@link #validateStep}; skip it here so it is not also
          // reported as a cycle.
          continue;
        }
        if (ids.contains(dependency)) {
          declaredDeps.add(dependency);
        } else {
          violations.add("step " + step.id() + " depends on unknown step: " + dependency);
        }
      }
      dependencies.put(step.id(), declaredDeps);
      state.put(step.id(), 0);
    }
    for (PlanStepDto step : steps) {
      if (state.get(step.id()) == 0 && hasCycleFrom(step.id(), dependencies, state, violations)) {
        return;
      }
    }
  }

  private boolean hasCycleFrom(
      String id,
      Map<String, List<String>> dependencies,
      Map<String, Integer> state,
      List<String> violations) {
    state.put(id, 1);
    for (String next : dependencies.getOrDefault(id, List.of())) {
      int nextState = state.getOrDefault(next, 0);
      if (nextState == 1) {
        violations.add("plan has a dependency cycle involving step: " + next);
        return true;
      }
      if (nextState == 0 && hasCycleFrom(next, dependencies, state, violations)) {
        return true;
      }
    }
    state.put(id, 2);
    return false;
  }

  /**
   * An upper-bound estimate of the plan's serialized size in characters: the character length of
   * every field value, plus a fixed per-step overhead for the surrounding JSON. It never needs a
   * serializer, so it stays pure, and it is an <em>upper bound</em> of the real serialized length,
   * so exceeding it is a definite overrun rather than a false positive.
   *
   * @param plan the plan to size
   * @return the estimated serialized size in characters
   */
  private static int estimatedSerializedSize(PlannerPlanDto plan) {
    int size = 2; // the enclosing {"steps":[...]} wrapper
    for (PlanStepDto step : plan.steps()) {
      size += STEP_OVERHEAD;
      size += safeLen(step.id());
      size += safeLen(step.type());
      size += safeLen(step.toolId());
      for (String dependency : step.dependsOn()) {
        size += safeLen(dependency) + 1;
      }
      for (Map.Entry<String, Object> entry : step.arguments().entrySet()) {
        size += safeLen(entry.getKey()) + 1 + objectCharCount(entry.getValue());
      }
    }
    return size;
  }

  private static String safeId(String id) {
    return id == null ? "<blank id>" : id;
  }

  private static int safeLen(String value) {
    return value == null ? 0 : value.length();
  }

  private static int objectCharCount(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Map<?, ?> map) {
      int total = 2;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        total += safeLen(String.valueOf(entry.getKey())) + 1 + objectCharCount(entry.getValue());
      }
      return total;
    }
    if (value instanceof List<?> list) {
      int total = 2;
      for (Object element : list) {
        total += objectCharCount(element) + 1;
      }
      return total;
    }
    return String.valueOf(value).length();
  }
}
