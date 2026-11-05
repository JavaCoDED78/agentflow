package com.example.agentflow.workflowexecution.persistence;

import com.example.agentflow.workflowexecution.domain.ConflictException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Write / read path for the {@code workflow_step_attempt_history} table (S07-14), owned by the
 * {@code workflowexecution} module.
 *
 * <p>Each attempt a step makes is one durable row here (the {@code attempt number, start/finish,
 * worker instance, safe error category and duration} that S07-14 asks for). The writes join the
 * single {@link org.springframework.transaction.annotation.Transactional} step-writer, {@code
 * RunStateMachineService}, so an attempt begins when the step enters {@code RUNNING} and concludes
 * when it leaves it — in the same transaction as the state change (invariant 3).
 *
 * <p>The table stores no {@code tenant_id}: tenant isolation flows through the parent run, so every
 * read and every write joins {@code workflow_runs} and filters by the parent's {@code tenant_id}
 * (invariant 1) — a cross-tenant run is indistinguishable from a missing one.
 *
 * <p>The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02).
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class WorkflowStepAttemptHistoryRepository {

  private static final String TABLE = "workflow_step_attempt_history";

  private final JdbcTemplate jdbc;

  public WorkflowStepAttemptHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Begin an attempt — the step has entered {@code RUNNING} and is about to perform its side effect
   * (invariant 6). The attempt number is {@code MAX(attempt) + 1} for {@code (run_id, step_key)},
   * so a retry gets a fresh, monotonic number; the row starts with {@code outcome NULL} and {@code
   * finished_at NULL} and is concluded by {@link #concludeAttempt} when the attempt leaves {@code
   * RUNNING}. Tenant isolation is inherited from the caller (the step is already verified to belong
   * to {@code tenantId} by the transition service's tenant-scoped read); {@code run_id} is a
   * primary key, so the insert can only ever touch the row the caller scoped.
   *
   * @param tenantId the owning tenant (verified by the caller)
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param workerInstance the worker that is beginning the attempt (nullable)
   */
  public void beginAttempt(UUID tenantId, UUID runId, String stepKey, String workerInstance) {
    int attempt = nextAttemptNumber(runId, stepKey);
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (id, run_id, step_key, attempt, worker_instance, outcome, started_at, created_at)"
            + " VALUES (?, ?, ?, ?, ?, NULL, now(), now())",
        ps -> {
          ps.setObject(1, UUID.randomUUID());
          ps.setObject(2, runId);
          ps.setString(3, stepKey);
          ps.setInt(4, attempt);
          if (workerInstance == null) {
            ps.setNull(5, java.sql.Types.VARCHAR);
          } else {
            ps.setString(5, workerInstance);
          }
        });
  }

  /**
   * Conclude the current attempt — the step has left {@code RUNNING} for a terminal or transient
   * outcome (invariant 6). The highest-attempt row for {@code (run_id, step_key)} is updated with
   * its {@code outcome}, its {@code finished_at} and, when a fault was classified, the safe {@code
   * error_class} token and redacted {@code error_summary} (S07-11 / S07-14). The update is
   * tenant-scoped (invariant 1): the {@code EXISTS} guard matches only a row whose parent run
   * belongs to {@code tenantId}, so a foreign run can never be written.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run the step belongs to
   * @param stepKey the graph node the step runs
   * @param outcome the status the attempt reached (e.g. {@code RETRY_WAIT}, {@code FAILED}, {@code
   *     SUCCEEDED})
   * @param errorClass the fault's {@code ErrorClass} token (nullable: records no error)
   * @param errorSummary the short redacted reason (nullable: records no error)
   * @throws ConflictException if zero rows match (missing / cross-tenant)
   */
  public void concludeAttempt(
      UUID tenantId,
      UUID runId,
      String stepKey,
      String outcome,
      String errorClass,
      String errorSummary) {
    int affected =
        jdbc.update(
            "UPDATE "
                + TABLE
                + " h SET outcome = ?, finished_at = now(),"
                + " error_class = COALESCE(?, error_class), error_summary = COALESCE(?, error_summary)"
                + " WHERE h.run_id = ? AND h.step_key = ? AND h.attempt = ("
                + "SELECT COALESCE(MAX(attempt), 0) FROM "
                + TABLE
                + " WHERE run_id = ? AND step_key = ?)"
                + " AND EXISTS (SELECT 1 FROM workflow_runs r WHERE r.id = h.run_id AND r.tenant_id = ?)",
            ps -> {
              ps.setString(1, outcome);
              ps.setString(2, errorClass);
              ps.setString(3, errorSummary);
              ps.setObject(4, runId);
              ps.setString(5, stepKey);
              ps.setObject(6, runId);
              ps.setString(7, stepKey);
              ps.setObject(8, tenantId);
            });
    if (affected == 0) {
      throw new ConflictException(
          "attempt history for step '" + stepKey + "' on run " + runId + " is out of date");
    }
  }

  /**
   * Read the attempt history of every step of a run, tenant-scoped (S07-14). The {@code tenant_id}
   * predicate is part of the {@code JOIN ... WHERE} clause, so a run owned by another tenant is
   * indistinguishable from a missing one and returns no rows. The ordering is {@code step_key},
   * {@code attempt} (ascending), so each step's attempts are grouped chronologically.
   *
   * @param tenantId the owning tenant (matched against the parent run in the {@code WHERE})
   * @param runId the run whose attempt history to read
   * @return the attempt rows grouped by step and attempt (empty when the run has no attempts for
   *     this tenant)
   */
  public List<AttemptHistoryRow> findHistory(UUID tenantId, UUID runId) {
    return jdbc.query(
        "SELECT h.attempt, h.step_key, h.worker_instance, h.outcome, h.started_at, h.finished_at,"
            + " h.error_class, h.error_summary FROM "
            + TABLE
            + " h "
            + "JOIN workflow_runs r ON h.run_id = r.id "
            + "WHERE h.run_id = ? AND r.tenant_id = ? "
            + "ORDER BY h.step_key ASC, h.attempt ASC",
        (rs, rowNum) ->
            new AttemptHistoryRow(
                rs.getInt("attempt"),
                rs.getString("step_key"),
                rs.getString("worker_instance"),
                rs.getString("outcome"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getString("error_class"),
                rs.getString("error_summary")),
        runId,
        tenantId);
  }

  /**
   * @return the next attempt number for {@code (run_id, step_key)}, i.e. {@code MAX(attempt) + 1}
   *     (or {@code 1} when no attempt has been recorded yet).
   */
  private int nextAttemptNumber(UUID runId, String stepKey) {
    Integer max =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(attempt), 0) FROM " + TABLE + " WHERE run_id = ? AND step_key = ?",
            Integer.class,
            runId,
            stepKey);
    return max == null ? 1 : max + 1;
  }
}
