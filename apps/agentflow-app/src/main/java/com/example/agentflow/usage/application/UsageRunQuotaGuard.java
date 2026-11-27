package com.example.agentflow.usage.application;

import com.example.agentflow.workflowexecution.application.RunQuotaGuard;
import com.example.agentflow.workflowexecution.domain.QuotaCheckResult;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * The {@link RunQuotaGuard} adapter the {@code usage} module supplies (S12-05): it delegates the
 * tenant-scoped parallel-run quota acquisition / release to the durable {@link QuotaService}.
 *
 * <p>It reuses the sanctioned {@code usage} → {@code workflowexecution} module pair (module-map
 * edge 9, the same edge the S12-04 {@code UsageBudgetGuard} already implements) - the execution-SPI
 * pattern: {@code workflowexecution} owns the port, {@code usage} owns the durable store and the
 * adapter. The bean is {@code @ConditionalOnBean(DataSource.class)} so it is absent when no
 * database is configured (the application still boots without a database, S01-02); {@code
 * workflowexecution.RunService} / {@code RunStateMachineService} then receive a {@code null} guard
 * and the quota acquire / release is skipped.
 *
 * <p>Every call is keyed by {@code (tenantId, runId)}, passed explicitly (no {@code ThreadLocal},
 * invariant 1), and delegated to the durable service that performs the tenant-scoped, row-lock
 * serialized queries, so another tenant's slots are indistinguishable from missing ones.
 *
 * @see RunQuotaGuard
 * @see QuotaService
 */
@Component
@ConditionalOnBean(DataSource.class)
public class UsageRunQuotaGuard implements RunQuotaGuard {

  private final QuotaService quotaService;

  @Autowired
  public UsageRunQuotaGuard(@Autowired(required = false) QuotaService quotaService) {
    this.quotaService = Objects.requireNonNull(quotaService, "quotaService must not be null");
  }

  @Override
  public QuotaCheckResult acquireRunSlot(UUID tenantId, UUID runId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(runId, "runId must not be null");
    return quotaService.acquireRunSlot(tenantId, runId);
  }

  @Override
  public void releaseRunSlot(UUID tenantId, UUID runId) {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(runId, "runId must not be null");
    quotaService.releaseRunSlot(tenantId, runId);
  }
}
