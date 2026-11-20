package com.example.agentflow.messaging.persistence;

import com.example.agentflow.messaging.domain.DlqRedriveAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Write / read path for the {@code dlq_redrive_attempts} table, owned by the {@code messaging}
 * module (S12-12).
 *
 * <p>The redrive attempt is written in the <b>same transaction</b> as the re-published {@code
 * outbox_events} row and the audit row (invariant 3 / S12-12: the redrive, the re-publish and the
 * audit are one atomic unit), so the attempt can never be recorded without its re-publish and vice
 * versa. The bean is only registered when a {@link DataSource} exists
 * ({@code @ConditionalOnBean(DataSource.class)}), so the application still boots without a database
 * (S01-02); {@link com.example.agentflow.messaging.application.DlqRedriveService} fails loudly when
 * the repository is absent.
 *
 * <p>The {@code dlq_id} primary key makes the redrive idempotent: one attempt per DLQ record, so a
 * second redrive of the same record is refused (REFUSED_ALREADY_RDRIVEN) rather than re-publishing
 * a second envelope.
 */
@Repository
@ConditionalOnBean(DataSource.class)
public class DlqRedriveAttemptRepository {

  private static final String TABLE = "dlq_redrive_attempts";

  private final JdbcTemplate jdbc;

  /**
   * @param jdbc the JDBC read/write path for {@code dlq_redrive_attempts}
   */
  public DlqRedriveAttemptRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Append one redrive attempt and record the linkage between the failed record and its fresh
   * re-delivery (invariant 4 / S12-12: the re-publish and the attempt commit or roll back
   * together).
   *
   * @param attempt the redrive attempt to append
   */
  public void insert(DlqRedriveAttempt attempt) {
    jdbc.update(
        "INSERT INTO "
            + TABLE
            + " (dlq_id, event_id, requeued_event_id, reason, operator, tenant_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, attempt.dlqId());
          ps.setObject(2, attempt.eventId());
          ps.setObject(3, attempt.requeuedEventId());
          ps.setString(4, attempt.reason());
          ps.setString(5, attempt.operator());
          ps.setObject(6, attempt.tenantId());
          ps.setObject(7, attempt.createdAt());
        });
  }

  /**
   * @param dlqId the DLQ record id
   * @return the redrive attempt already recorded for the record (the idempotency guard), or empty
   *     when the record has not been redriven
   */
  public Optional<DlqRedriveAttempt> findByDlqId(UUID dlqId) {
    List<DlqRedriveAttempt> rows =
        jdbc.query(
            "SELECT dlq_id, event_id, requeued_event_id, reason, operator, tenant_id, created_at "
                + "FROM "
                + TABLE
                + " WHERE dlq_id = ?",
            rowMapper,
            dlqId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  // A package-private row mapper (S12-12), kept standalone so it can be unit-tested directly with a
  // mock ResultSet, mirroring {@link DlqMessageRowMapper}.
  private final DlqRedriveAttemptRowMapper rowMapper = new DlqRedriveAttemptRowMapper();
}

/**
 * Maps a {@code dlq_redrive_attempts} row to a {@link DlqRedriveAttempt} (S12-12).
 *
 * <p>Kept as a standalone class so it can be unit-tested directly with a mock {@link ResultSet},
 * mirroring {@link DlqMessageRowMapper}. The {@code reason} and {@code operator} columns are
 * recorded by the operator and are NOT NULL; the {@code created_at} instant is assigned by the
 * application, also NOT NULL.
 */
final class DlqRedriveAttemptRowMapper implements RowMapper<DlqRedriveAttempt> {

  @Override
  public DlqRedriveAttempt mapRow(ResultSet rs, int rowNum) throws SQLException {
    return DlqRedriveAttempt.created(
        rs.getObject("dlq_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getObject("requeued_event_id", UUID.class),
        rs.getString("reason"),
        rs.getString("operator"),
        rs.getObject("tenant_id", UUID.class),
        timestamp(rs, "created_at"));
  }

  private static OffsetDateTime timestamp(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
  }
}
