package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read/write path for the {@code workflow_events} table, owned by the {@code messaging} module.
 *
 * <p>This is the single appender for the append-only run/step history and the single replay reader.
 * Every {@link #insert} adds exactly one row; the row is never updated or deleted, and there is no
 * update/delete API over the history (S05-09). The {@link #findByTenantAndRunId} replay read
 * returns a run's ordered history (most-recent-first) so a run's state can be reconstructed (the
 * S05-09 acceptance criterion); the {@code tenant_id} predicate is part of the {@code WHERE} clause
 * so a cross-tenant id is indistinguishable from a missing run (invariant 1).
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); {@link com.example.agentflow.messaging.application.WorkflowEventService} fails loudly
 * when the repository is absent.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowEventRepository {

  private static final String TABLE = "workflow_events";

  private final JdbcTemplate jdbc;
  private final WorkflowEventRowMapper mapper = new WorkflowEventRowMapper();

  public WorkflowEventRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one transition fact and return its assigned id. The row is tenant-scoped (invariant 1)
   * and the insert is the sole write path, so the history stays append-only — there is no
   * update/delete API for history.
   *
   * @param event the transition fact to append
   * @return the persisted event id
   */
  public UUID insert(WorkflowEvent event) {
    UUID id = event.id();
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, tenant_id, run_id, step_run_id, event_type, payload_redacted, occurred_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, id);
          ps.setObject(2, event.tenantId());
          ps.setObject(3, event.runId());
          ps.setObject(4, event.stepRunId());
          ps.setString(5, event.eventType());
          ps.setString(6, event.payloadRedacted());
          ps.setObject(7, event.occurredAt());
        });
    return id;
  }

  /**
   * Replay a run's transition history, ordered by {@code occurred_at} (most-recent-first), so the
   * run's state can be reconstructed (S05-09). The {@code tenant_id} predicate is part of the
   * {@code WHERE} clause (invariant 1), so a cross-tenant id returns an empty list —
   * indistinguishable from a missing run.
   *
   * @param tenantId the owning tenant
   * @param runId the run whose history to replay
   * @return the run's events ordered by {@code occurred_at} (never {@code null})
   */
  public List<WorkflowEvent> findByTenantAndRunId(UUID tenantId, UUID runId) {
    return jdbc.query(
        "SELECT id, tenant_id, run_id, step_run_id, event_type, payload_redacted, occurred_at "
            + "FROM "
            + TABLE
            + " WHERE run_id = ? AND tenant_id = ? ORDER BY occurred_at",
        mapper,
        runId,
        tenantId);
  }
}
