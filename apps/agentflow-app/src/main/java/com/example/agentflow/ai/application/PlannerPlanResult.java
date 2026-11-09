package com.example.agentflow.ai.application;

/**
 * The outcome of a {@link PlannerService#propose} call: the proposed plan plus the metadata a model
 * attempt must record (S09-06).
 *
 * <p>The {@link #plan} is the boundary shape ({@link PlannerPlanDto}); the metadata ties the
 * attempt to its prompt and provider:
 *
 * <ul>
 *   <li>{@link #model} — the model id the provider reports produced the completion;
 *   <li>{@link #promptVersion} — the {@link PromptVersion} the planner planned against (S09-04);
 *   <li>{@link #usage} — the token accounting the provider reports, or {@code null} when the
 *       provider reports none.
 * </ul>
 *
 * <p>The metadata is not part of the plan: it is observability and metering for a plan the backend
 * will validate server-side before any step runs (S09-07 / {@code ADR-006}), so the attempt stays
 * traceable and costed without leaking the prompt or the (untrusted) injected content (invariant
 * 8).
 */
public record PlannerPlanResult(
    PlannerPlanDto plan, String model, PromptVersion promptVersion, ChatUsage usage) {}
