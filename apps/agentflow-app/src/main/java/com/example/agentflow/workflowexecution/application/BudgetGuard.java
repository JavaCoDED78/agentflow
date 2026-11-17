package com.example.agentflow.workflowexecution.application;

import com.example.agentflow.usage.domain.BudgetCheckResult;
import java.util.UUID;

/**
 * The budget gate {@code workflowexecution} uses before a run starts (S12-04), owned by {@code
 * workflowexecution} and named in {@code docs/architecture/module-api.md}.
 *
 * <p>The durable budget itself is owned by the {@code usage} module (its {@link
 * com.example.agentflow.usage.application.BudgetService}), but {@code workflowexecution} may not
 * compile-depend on {@code usage} — {@code usage} is the allowed direction, never the reverse (the
 * module-map arrow for {@code workflowexecution} lists {@code workflowdefinition}, {@code
 * messaging} and {@code audit}, not {@code usage}). So the gate is a <em>port</em> here and the
 * {@code usage} module supplies the {@code @ConditionalOnBean(DataSource.class)} adapter that
 * implements it, so a {@code usage} → {@code workflowexecution} edge (arrow 10) is the only compile
 * edge — exactly the execution-SPI pattern the {@code StepHandler} port records. This runtime
 * wiring keeps the module graph acyclic.
 *
 * <p>The {@code tenantId} comes from the verified security context (invariant 1): it is never taken
 * from the request body or headers.
 */
public interface BudgetGuard {

  /**
   * Whether the tenant may start another run without exceeding its durable budget (S12-04).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @return {@link BudgetCheckResult#allow()} while any configured dimension still has headroom; a
   *     tenant with no budget configured is allowed (unlimited, never blocked); {@link
   *     BudgetCheckResult#reject(String)} with the exhausted dimension otherwise
   */
  BudgetCheckResult checkBudget(UUID tenantId);
}
