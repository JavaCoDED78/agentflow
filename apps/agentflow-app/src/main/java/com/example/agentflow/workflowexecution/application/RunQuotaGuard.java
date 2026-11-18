package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.usage.domain.QuotaCheckResult;
import java.util.UUID;

/**
 * The parallel-run quota gate {@code workflowexecution} uses around a run's lifetime (S12-05),
 * owned by {@code workflowexecution} and named in {@code docs/architecture/module-api.md}.
 *
 * <p>The durable quota itself is owned by the {@code usage} module (its {@link
 * com.example.agentflow.usage.application.QuotaService}), but {@code workflowexecution} may not
 * compile-depend on {@code usage.application} — {@code usage} is the allowed direction, never the
 * reverse (the module-map arrow for {@code workflowexecution} lists {@code workflowdefinition},
 * {@code messaging} and {@code audit}, not {@code usage}). So the gate is a <em>port</em> here and
 * the {@code usage} module supplies the {@code @ConditionalOnBean(DataSource.class)} adapter that
 * implements it - the exact execution-SPI pattern the {@code BudgetGuard} port (S12-04) and the
 * {@code StepHandler} port record. This runtime wiring keeps the module graph acyclic.
 *
 * <p>The seam is deliberate: a run <em>acquires</em> its slot when it is created (before any charge
 * is made) and <em>releases</em> it when the run reaches a terminal state through the single
 * transition service, so the slot lifetime is exactly the run's non-terminal lifetime. The {@code
 * tenantId} comes from the verified security context (invariant 1): it is never taken from the
 * request body or headers.
 */
public interface RunQuotaGuard {

  /**
   * Acquire the run's quota slot before the run is created. Refuses (without inserting anything)
   * when the tenant already holds its limit of non-terminal runs.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run acquiring the slot
   * @return {@link QuotaCheckResult#allow()} when the slot is acquired (or the run already holds it
   *     - a duplicate delivery / idempotent replay is never counted twice); {@link
   *     QuotaCheckResult#reject(String)} with the exhausted limit otherwise
   */
  QuotaCheckResult acquireRunSlot(UUID tenantId, UUID runId);

  /**
   * Release the run's quota slot at its terminal transition. Idempotent: releasing a run that holds
   * no slot changes nothing, so an at-least-once repeat is safe (invariant 3).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param runId the run releasing the slot
   */
  void releaseRunSlot(UUID tenantId, UUID runId);
}
