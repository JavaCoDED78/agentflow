package com.example.agentflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.persistence.AuditRepository;
import com.example.agentflow.messaging.application.DlqRedriveRequest;
import com.example.agentflow.messaging.application.DlqRedriveResult;
import com.example.agentflow.messaging.application.DlqRedriveService;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.DlqRedriveAttempt;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import com.example.agentflow.messaging.persistence.DlqRedriveAttemptRepository;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Redrive acceptance tests for Sprint 12 DLQ redrive (S12-12) on <b>real PostgreSQL</b>
 * (Testcontainers), the storage-level guarantee the DB-free unit test cannot cover.
 *
 * <p>The repositories and the {@link DlqRedriveService} run over one real connection factory; the
 * service builds its own {@link DataSourceTransactionManager} transaction exactly as the app wires
 * it, so the re-published {@code outbox_events} row, the {@code dlq_redrive_attempts} linkage row
 * and the {@code audit_events} row commit together (invariant 3) and a later read on a fresh
 * connection observes them only when the transaction commits.
 *
 * <ul>
 *   <li><b>Atomic write.</b> A redrive leaves a fresh {@code outbox_events} row (same tenant /
 *       payload, fresh event id), one {@code dlq_redrive_attempts} row and one {@code audit_events}
 *       {@code READ}/{@code DLQ} row &mdash; the whole unit or nothing.
 *   <li><b>Inbox guard.</b> A {@code PROCESSED} inbox row (a completed business change) makes the
 *       redrive refuse {@code 409} and re-publish nothing.
 *   <li><b>Idempotent at the storage layer.</b> {@code dlq_redrive_attempts.dlq_id} is the primary
 *       key, so a second redrive of the same record returns the original fresh re-delivery and a
 *       second envelope is never created.
 *   <li><b>Guarded reason.</b> A blank operator reason is a {@code 400} that writes nothing.
 * </ul>
 */
@Testcontainers
public class DlqRedriveServiceIT {

  private static final String EVENT_TYPE = "workflow.run.requested.v1";
  private static final String AGGREGATE_TYPE = "workflow_run";
  private static final String TOPIC = "agentflow.workflow.run-requested.v1";
  private static final String CONSUMER = "workflowexecution:run-requested";
  private static final String REASON = "verified safe to retry";
  private static final String ACTOR = "operator";

  @Container
  static final PostgreSQLContainer postgres =
      new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18");

  private UUID tenant;
  private UUID seededEventId;
  private JdbcTemplate jdbc;
  private DlqMessageRepository dlq;
  private OutboxEventRepository outbox;
  private InboxMessageRepository inbox;
  private DlqRedriveAttemptRepository redriveAttempts;
  private DlqRedriveService service;

  @BeforeEach
  void bootRealPostgresAndSchema() {
    org.flywaydb.core.Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("classpath:migrations")
        .load()
        .migrate();

    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(ds);
    // outbox_events.tenant_id and audit_events.tenant_id both reference tenants(id): seed one.
    // The slug embeds the id so the ON CONFLICT guard never skips the insert across the shared,
    // per-class container (a fixed slug would leave the fresh UUID tenantless on the 2nd run).
    this.tenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO tenants (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
        tenant,
        "tenant-it-" + tenant);

    this.dlq = new DlqMessageRepository(jdbc);
    this.outbox = new OutboxEventRepository(jdbc);
    this.inbox = new InboxMessageRepository(jdbc);
    this.redriveAttempts = new DlqRedriveAttemptRepository(jdbc);
    AuditService audit = new AuditService(new AuditRepository(jdbc));
    this.service = new DlqRedriveService(dlq, outbox, inbox, redriveAttempts, audit, ds);
  }

  /**
   * S12-12 acceptance (atomic write): a redrive of a dead-lettered record re-publishes the original
   * envelope under a fresh id, records the linkage row and the operator audit row &mdash; all three
   * in one committed transaction &mdash; so a later read on a fresh connection observes all of
   * them.
   */
  @Test
  void redrive_writes_the_attempt_the_republish_and_the_audit_row_atomically() {
    UUID dlqId = seedRedriveFixture();

    DlqRedriveResult result = service.redrive(dlqId, tenant, ACTOR, new DlqRedriveRequest(REASON));

    assertThat(result.dlqId()).isEqualTo(dlqId);
    assertThat(result.originalEventId()).isEqualTo(seededEventId);
    assertThat(result.requeuedEventId()).isNotNull();
    // the re-published envelope carries a FRESH event id, never the same failed event id.
    assertThat(result.requeuedEventId()).isNotEqualTo(seededEventId);

    // Re-published envelope: a real outbox row with the SAME tenant and payload, fresh event id.
    assertThat(outbox.findByEventId(result.requeuedEventId())).isPresent();
    OutboxEvent republished = outbox.findByEventId(result.requeuedEventId()).orElseThrow();
    assertThat(republished.tenantId()).isEqualTo(tenant);
    assertThat(republished.aggregateType()).isEqualTo(AGGREGATE_TYPE);

    // Linkage row: one attempt for this DLQ record, original -> fresh re-delivery.
    assertThat(redriveAttempt(dlqId)).isPresent();
    assertThat(redriveAttempt(dlqId).orElseThrow().requeuedEventId())
        .isEqualTo(result.requeuedEventId());

    // Audit trail: exactly one READ / DLQ row for the operator action, tenant-scoped.
    assertThat(auditRows(dlqId)).hasSize(1);
  }

  /**
   * S12-12 acceptance (inbox guard): a {@code PROCESSED} inbox row (a completed business change)
   * makes the redrive refuse {@code 409} and re-publish nothing &mdash; a completed side effect is
   * never duplicated.
   */
  @Test
  void redrive_is_refused_when_the_inbox_already_committed_the_change() {
    UUID dlqId = seedRedriveFixture();
    // The business change already committed: claim then complete the original event in the inbox.
    assertThat(inbox.claim(InboxMessage.claiming(CONSUMER, seededEventId))).isTrue();
    inbox.markProcessed(CONSUMER, seededEventId, "applied");

    assertThatThrownBy(() -> service.redrive(dlqId, tenant, ACTOR, new DlqRedriveRequest(REASON)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // Nothing was re-published, no linkage row, no audit row.
    assertThat(redriveAttempt(dlqId)).isEmpty();
    assertThat(auditRows(dlqId)).isEmpty();
  }

  /**
   * S12-12 acceptance (idempotent at the storage layer): a second redrive of the same record
   * returns the original fresh re-delivery and creates neither a second envelope nor a second
   * linkage row &mdash; {@code dlq_redrive_attempts.dlq_id} is the primary key.
   */
  @Test
  void a_second_redrive_returns_the_original_requeued_event_without_a_second_publish() {
    UUID dlqId = seedRedriveFixture();

    DlqRedriveResult first = service.redrive(dlqId, tenant, ACTOR, new DlqRedriveRequest(REASON));
    DlqRedriveResult second = service.redrive(dlqId, tenant, ACTOR, new DlqRedriveRequest(REASON));

    assertThat(second.requeuedEventId()).isEqualTo(first.requeuedEventId());

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_id = ?",
                Integer.class,
                first.requeuedEventId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM dlq_redrive_attempts WHERE dlq_id = ?", Integer.class, dlqId))
        .isEqualTo(1);
    // a replay returns the existing redrive without writing a second audit row.
    assertThat(auditRows(dlqId)).hasSize(1);
  }

  /**
   * S12-12 acceptance (guarded reason): a blank operator reason is a {@code 400} that writes no
   * linkage row and no audit row &mdash; a redrive without an audit-trail reason is an unaccounted
   * re-delivery.
   */
  @Test
  void a_blank_reason_is_a_400_and_writes_nothing() {
    UUID dlqId = seedRedriveFixture();

    assertThatThrownBy(() -> service.redrive(dlqId, tenant, ACTOR, new DlqRedriveRequest("   ")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(redriveAttempt(dlqId)).isEmpty();
    assertThat(auditRows(dlqId)).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // Fixtures and helpers
  // ---------------------------------------------------------------------------

  /**
   * Seed a dead-lettered record pointing at a real (tenant-scoped) original outbox envelope, and
   * return the DLQ record id to redrive.
   */
  private UUID seedRedriveFixture() {
    UUID eventId = UUID.randomUUID();
    this.seededEventId = eventId;

    // The original (failed) envelope lives only in the outbox; the DLQ is a pointer to it.
    outbox.insert(
        OutboxEvent.pending(
            UUID.randomUUID(),
            eventId,
            EVENT_TYPE,
            TOPIC,
            AGGREGATE_TYPE,
            UUID.randomUUID(),
            tenant,
            "{\"runId\":\"seed\"}",
            "{\"traceparent\":\"00-abc\"}"));

    DlqMessage dead =
        DlqMessage.deadLetter(
            eventId,
            TOPIC,
            CONSUMER,
            DlqMessage.Status.DEAD_LETTER,
            DlqMessage.ErrorClass.PERMANENT,
            3,
            "redacted boom");
    dlq.record(dead);
    return dead.id();
  }

  private Optional<DlqRedriveAttempt> redriveAttempt(UUID dlqId) {
    return redriveAttempts.findByDlqId(dlqId);
  }

  /**
   * @return the audit rows the redrive wrote for the DLQ record (the {@code READ}/{@code DLQ}
   *     operator action), scoped by the operator's tenant.
   */
  private List<Map<String, Object>> auditRows(UUID dlqId) {
    return jdbc.queryForList(
        "SELECT action, resource_type, resource_id, tenant_id FROM audit_events "
            + "WHERE action = 'READ' AND resource_type = 'DLQ' AND resource_id = ? AND tenant_id = ?",
        dlqId,
        tenant);
  }
}
