package com.example.agentflow.messaging.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.messaging.domain.DlqMessage;
import com.example.agentflow.messaging.persistence.DlqMessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator inspection use cases for the dead-letter queue (S12-11).
 *
 * <p>The DLQ is a single, tenant-wide store keyed by {@code (consumer, event)} — it is not
 * tenant-scoped, so these methods take no tenant for the *lookup*; the tenant/actor are recorded
 * only in the audit trail (S12-07, invariant 3) from the trusted security context.
 *
 * <p>The {@link DlqMessageRepository} bean is only registered when a {@link
 * org.springframework.jdbc.core.JdbcTemplate} (hence a {@link javax.sql.DataSource}) is available
 * (see {@code DlqMessageRepository}); the dependency is therefore {@link Autowired optional} so the
 * application still boots without a database (S01-02). When absent the service fails loud with a
 * {@code 503} rather than returning a silent, misleading page, exactly like {@code
 * WorkflowDefinitionService}.
 *
 * <p>Security (who may inspect) is decided by {@link SecurityConfiguration} via the {@code ops:dlq}
 * rule in {@link com.example.agentflow.identity.application.authorization.AccessMatrix}; this
 * service decides only the data shape: paginated, read-only, no raw payload, and an audited detail
 * view.
 */
@Service
public class DlqInspectionService {

  /** Hard cap on how many rows a single {@link #listDlqMessages} page may return (invariant 11). */
  private static final int MAX_PAGE_SIZE = 100;

  private final DlqMessageRepository repository;
  private final AuditService auditService;

  public DlqInspectionService(
      @Autowired(required = false) DlqMessageRepository repository, AuditService auditService) {
    this.repository = repository;
    this.auditService = auditService;
  }

  /**
   * Page the whole DLQ store, newest first (deterministic order by {@code recorded_at DESC, id
   * DESC} — invariant 10's ordering rule, so a page is stable across repeated reads). The result is
   * the overview metadata only (no failure text); the full record is the audited detail view.
   *
   * @param page zero-based page index
   * @param requestedSize the requested page size; larger values are clamped to {@link
   *     #MAX_PAGE_SIZE}
   * @return the page of {@link DlqMessageDto} plus pagination metadata
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 400} if {@code
   *     page < 0} or {@code requestedSize < 1}
   */
  public DlqMessagePageDto listDlqMessages(int page, int requestedSize) {
    requireRepository();
    if (page < 0 || requestedSize < 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "pagination must use page >= 0 and size >= 1");
    }
    int size = Math.min(requestedSize, MAX_PAGE_SIZE);
    int offset = page * size;
    List<DlqMessage> rows = repository.findAllPageable(offset, size);
    long totalElements = repository.countAll();
    int totalPages = (int) Math.ceil((double) totalElements / size);
    return new DlqMessagePageDto(
        rows.stream().map(DlqMessageDto::from).toList(),
        page,
        size,
        totalElements,
        totalPages,
        page + 1 < totalPages,
        page > 0);
  }

  /**
   * The operator-only, audited inspection of one dead-letter message (S12-11): resolve the row by
   * id, and — because opening a message's full failure detail is the sensitive action — record a
   * {@link AuditAction#READ} on the {@link AuditResourceType#DLQ} in the append-only trail
   * (audit-contract §4.6 reuses the {@code READ} verb for operator inspection). The {@code
   * lastError} surfaced here is already redacted at write time (S12-09): a secret, full prompt or
   * full document never reaches the durable DLQ row, so it never reaches this view.
   *
   * @param dlqId the DLQ row id to inspect
   * @param tenantId the operator's tenant, recorded in the audit trail (resolved from the trusted
   *     context; the DLQ lookup itself is tenant-wide)
   * @param actor the authenticated actor recorded in the audit trail
   * @return the full inspection view ({@link DlqMessageDetailDto})
   * @throws ResponseStatusException {@code 503} if no database is configured, {@code 404} if no DLQ
   *     row has that id (a cross-tenant id is indistinguishable from a missing one; the DLQ is not
   *     tenant-scoped, so there is only "exists" or "does not exist")
   */
  public DlqMessageDetailDto inspectDlqMessage(UUID dlqId, UUID tenantId, String actor) {
    requireRepository();
    DlqMessage message =
        repository
            .findById(dlqId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no DLQ message with id " + dlqId));
    auditService.record(tenantId, actor, AuditAction.READ, AuditResourceType.DLQ, dlqId, null);
    return DlqMessageDetailDto.from(message);
  }

  /**
   * @throws ResponseStatusException {@code 503} when no database (hence no DLQ repository) is
   *     configured, so a caller can never mistake an unavailable store for an empty one
   */
  private void requireRepository() {
    if (repository == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "DLQ inspection is unavailable: no database is configured");
    }
  }
}
