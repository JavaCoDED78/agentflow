package com.example.agentflow.usage.application;

import com.example.agentflow.usage.domain.BudgetCheckResult;
import com.example.agentflow.workflowexecution.application.BudgetGuard;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * The {@link BudgetGuard} adapter the {@code usage} module supplies (S12-04): it delegates the
 * tenant-scoped budget check to the durable {@link BudgetService}.
 *
 * <p>It is the single compile edge {@code usage} → {@code workflowexecution} (module-map edge 9),
 * the execution-SPI pattern the {@code StepHandler} port uses: {@code usage} implements the {@code
 * workflowexecution}-owned port. The bean is {@code @ConditionalOnBean(DataSource.class)} so it is
 * absent when no database is configured (the application still boots without a database, S01-02);
 * {@code workflowexecution.RunService} then receives a {@code null} guard and the run-start budget
 * check is skipped.
 *
 * <p>Every call is keyed by {@code tenantId}, passed explicitly (no {@code ThreadLocal}, invariant
 * 1), and delegated to the durable service that performs the tenant-scoped query, so another
 * tenant's budget is indistinguishable from a missing one.
 *
 * @see BudgetGuard
 * @see BudgetService
 */
@Component
@ConditionalOnBean(DataSource.class)
public class UsageBudgetGuard implements BudgetGuard {

  private final BudgetService budgetService;

  @Autowired
  public UsageBudgetGuard(@Autowired(required = false) BudgetService budgetService) {
    this.budgetService = Objects.requireNonNull(budgetService, "budgetService must not be null");
  }

  @Override
  public BudgetCheckResult checkBudget(UUID tenantId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    return budgetService.checkBudget(tenantId);
  }
}
