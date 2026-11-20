package com.example.agentflow.messaging.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.domain.DlqRedriveAttempt;
import com.example.agentflow.messaging.domain.InboxMessage;
import com.example.agentflow.messaging.domain.OutboxEvent;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import com.example.agentflow.messaging.persistence.DlqRedriveAttemptRepository;
import com.example.agentflow.messaging.persistence.InboxMessageRepository;
import com.example.agentflow.messaging.persistence.OutboxEventRepository;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator-controlled redrive of one dead-lettered message (S12-12).
 *
 * <p>The DLQ is a pointer to a failed envelope, not the envelope itself (S12-10 / `audit-contract`
 * §4.6), so the redrive re-publishes the <b>original</b> {@code outbox_events} row &mdash; its
 * payload and correlation headers live in that row &mdash; under a <b>fresh</b> envelope id. The
 * inbox dedup is keyed by {@code (consumer, event_id)}: the original {@code event_id} was already
 * claimed and failed, so reusing it would run <b>nothing</b>; a fresh {@code event_id} makes the
 * inbox re-run the business change exactly once (S12-12: "not repeat the terminal business
 * result"). The re-published envelope carries the original tenant / aggregate / topic / payload, so
 * the consumer re-checks the same tenant context (invariant 1).
 *
 * <p>The redrive is <b>idempotent, auditable and non-duplicating</b> (S12-12 acceptance):
 *
 * <ul>
 *   <li><b>Idempotent.</b> The {@code dlq_redrive_attempts} row is keyed by the DLQ record id
 *       ({@link DlqRedriveAttemptRepository#findByDlqId}), so a second redrive of the same record
 *       is refused rather than re-publishing a second envelope — a re-published event can never be
 *       a same-event id re-run, which the {@code dlq_redrive_attempts} primary key would block.
 *   <li><b>Guarded.</b> The inbox is the source of truth (invariant 2): a {@code PROCESSED} inbox
 *       row (a completed business change) or a {@code PENDING} row (an in-flight delivery) makes
 *       the redrive refuse {@code 409}, so a completed or concurrent side effect is never
 *       duplicated.
 *   <li><b>Auditable.</b> The re-publish, the {@code dlq_redrive_attempts} linkage row and the
 *       operator {@link AuditAction#READ} / {@link AuditResourceType#DLQ} audit row are written in
 *       <b>one transaction</b> (invariant 3): they commit or roll back together, so the audit never
 *       diverges from the re-publish.
 *   <li><b>Operator-scoped.</b> The endpoint is gated by the {@code ops:dlq} rule; the reason is
 *       mandatory (a redrive without an audit-trail reason is an unaccounted re-delivery) and is
 *       recorded redacted (invariant 8).
 * </ul>
 *
 * <p>The {@code DlqMessageRepository} / {@link OutboxEventRepository} / {@link
 * InboxMessageRepository} / {@link DlqRedriveAttemptRepository} beans are only registered when a
 * {@link DataSource} exists ({@code @ConditionalOnBean(DataSource.class)}); the dependency is
 * {@link Autowired optional} so the application still boots without a database (S01-02). When
 * absent the service fails loud with a {@code 503}, exactly like {@code DlqInspectionService}. The
 * transaction boundary is a manual {@link TransactionTemplate} over a {@code
 * DataSourceTransactionManager} (the same pattern the messaging module's {@code InboxDedupService}
 * uses), so the service is unit- and integration-testable without Spring.
 */
@Service
public class DlqRedriveService {

  private final DlqMessageRepository dlq;
  private final OutboxEventRepository outbox;
  private final InboxMessageRepository inbox;
  private final DlqRedriveAttemptRepository redriveAttempts;
  private final AuditService auditService;
  private final TransactionTemplate transactionTemplate;

  /**
   * @param dlq the dead-letter repository
   * @param outbox the outbox write/read path (the redrive re-publishes from the original envelope)
   * @param inbox the inbox repository (the source of truth the redrive guards against)
   * @param redriveAttempts the redrive-attempt repository (the idempotency anchor)
   * @param auditService the audit trail port
   * @param dataSource the datasource the transaction runs over (optional; absent without a
   *     database, per S01-02). A {@code null} datasource leaves the service disabled (a {@code
   *     503})
   */
  public DlqRedriveService(
      @Autowired(required = false) DlqMessageRepository dlq,
      @Autowired(required = false) OutboxEventRepository outbox,
      @Autowired(required = false) InboxMessageRepository inbox,
      @Autowired(required = false) DlqRedriveAttemptRepository redriveAttempts,
      AuditService auditService,
      @Autowired(required = false) DataSource dataSource) {
    this.dlq = dlq;
    this.outbox = outbox;
    this.inbox = inbox;
    this.redriveAttempts = redriveAttempts;
    this.auditService = auditService;
    this.transactionTemplate =
        dataSource != null
            ? new TransactionTemplate(new DataSourceTransactionManager(dataSource))
            : null;
  }

  /**
   * Redrive one dead-lettered message (S12-12): re-publish the original envelope under a fresh id,
   * guarded by the inbox source of truth, recorded in the idempotency table and audited — all in
   * one transaction.
   *
   * <p>The sequence (all inside the transaction): resolve the DLQ record and the original envelope
   * ({@code 404} when either is gone); refuse a {@code PROCESSED} (completed) or {@code PENDING}
   * (in-flight) inbox row with {@code 409}; return the already-recorded redrive on a replay (the
   * idempotency anchor, {@code 200}); otherwise re-publish the original envelope under a fresh
   * envelope id, append the {@code dlq_redrive_attempts} linkage row and record the operator {@code
   * READ} / {@code DLQ} audit row. The reason is mandatory (a redrive without a reason is an
   * unaccounted re-delivery) and is recorded redacted in the audit trail.
   *
   * @param dlqId the DLQ row id to redrive
   * @param tenantId the operator's tenant, recorded in the audit trail (resolved from the trusted
   *     context; the DLQ / inbox lookups are tenant-wide)
   * @param actor the authenticated actor recorded in the audit trail
   * @param request the operator-supplied reason ({@code reason} must be non-blank)
   * @return the redrive result ({@link DlqRedriveResult}) — the re-published envelope id, or the
   *     existing redrive on a replay
   * @throws ResponseStatusException {@code 400} (blank reason), {@code 404} (no DLQ row / no
   *     original envelope), {@code 409} (a completed / in-flight inbox row — the business change
   *     must not be duplicated), {@code 503} (no database is configured)
   */
  public DlqRedriveResult redrive(
      UUID dlqId, UUID tenantId, String actor, DlqRedriveRequest request) {
    requireRepository();
    if (request == null || request.reason() == null || request.reason().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "redrive requires a non-blank operator reason");
    }

    String reason = request.reason();

    return transactionTemplate.execute(
        status -> {
          DlqMessage message =
              dlq.findById(dlqId)
                  .orElseThrow(
                      () ->
                          new ResponseStatusException(
                              HttpStatus.NOT_FOUND, "no DLQ message with id " + dlqId));

          // Idempotency anchor: the redrive is recorded once per DLQ record (S12-12). A replay
          // returns the existing redrive regardless of the current inbox state (a re-run business
          // change may already be PROCESSED), never re-publishing a second envelope.
          Optional<DlqRedriveAttempt> existing = redriveAttempts.findByDlqId(dlqId);
          if (existing.isPresent()) {
            DlqRedriveAttempt attempt = existing.get();
            return DlqRedriveResult.redriven(
                dlqId,
                message.eventId(),
                attempt.requeuedEventId(),
                message.topic(),
                message.consumerName(),
                attempt.reason(),
                attempt.operator(),
                attempt.createdAt());
          }

          // The DLQ is only a pointer (S12-10): re-publish the original envelope from its row.
          OutboxEvent original =
              outbox
                  .findByEventId(message.eventId())
                  .orElseThrow(
                      () ->
                          new ResponseStatusException(
                              HttpStatus.NOT_FOUND,
                              "no original outbox envelope for event " + message.eventId()));

          // The inbox is the source of truth (invariant 2): refuse a completed or in-flight change
          // so the redrive never duplicates a terminal side effect or runs a concurrent delivery
          // twice. A FAILED / absent inbox row is safe to re-run.
          inbox
              .findByConsumerAndEvent(message.consumerName(), message.eventId())
              .ifPresent(
                  inboxMessage -> {
                    if (inboxMessage.status() == InboxMessage.Status.PROCESSED) {
                      throw new ResponseStatusException(
                          HttpStatus.CONFLICT, "message already processed; redrive refused");
                    }
                    if (inboxMessage.status() == InboxMessage.Status.PENDING) {
                      throw new ResponseStatusException(
                          HttpStatus.CONFLICT, "message is in-flight; redrive refused");
                    }
                  });

          // Re-publish the original envelope under a fresh envelope id so the inbox re-runs the
          // business change exactly once (invariant 3); the inbox dedup key by the fresh id makes
          // it a fresh claim, never a same-event re-run.
          UUID requeuedEventId = UUID.randomUUID();
          outbox.insert(
              OutboxEvent.pending(
                  UUID.randomUUID(),
                  requeuedEventId,
                  original.topic(),
                  original.topic(),
                  original.aggregateType(),
                  original.aggregateId(),
                  original.tenantId(),
                  original.payloadJson(),
                  original.headersJson()));

          // The linkage row (invariant 3 / S12-12): one atomic unit with the re-publish and the
          // audit row.
          redriveAttempts.insert(
              DlqRedriveAttempt.created(
                  dlqId,
                  message.eventId(),
                  requeuedEventId,
                  reason,
                  actor,
                  tenantId,
                  java.time.OffsetDateTime.now()));

          // The audit trail (invariant 3 / S12-07): the re-drive is a DLQ operator action. The verb
          // reuses the DLQ family's {@code READ} verb (audit-contract §4.6; no ops-specific verb),
          // and the reason is redacted through the audit port (invariant 8).
          auditService.record(
              tenantId, actor, AuditAction.READ, AuditResourceType.DLQ, dlqId, reason);

          return DlqRedriveResult.redriven(
              dlqId,
              message.eventId(),
              requeuedEventId,
              message.topic(),
              message.consumerName(),
              reason,
              actor,
              java.time.OffsetDateTime.now());
        });
  }

  /**
   * @throws ResponseStatusException {@code 503} when no database (hence no DLQ / outbox / inbox /
   *     redrive-attempt repository and no transaction boundary) is configured, so a caller can
   *     never mistake an unavailable store for an empty one
   */
  private void requireRepository() {
    if (dlq == null
        || outbox == null
        || inbox == null
        || redriveAttempts == null
        || transactionTemplate == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "DLQ redrive is unavailable: no database is configured");
    }
  }
}
