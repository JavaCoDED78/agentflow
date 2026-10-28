package com.example.agentflow.workflowdefinition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersion;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the version application use cases (S05-12) against a mocked persistence layer: the
 * create gate (parse + validate + size-bound), the pinned {@code checksum} over the stored bytes,
 * the tenant-scoped lookups, and the {@code DRAFT -> ACTIVATED} transition that pins the version as
 * immutable. The {@code WorkflowGraphParser}/{@link WorkflowGraphCodec} are real (pure round-trip
 * helpers), so the graph parse/validate/checksum path is exercised end to end; only the database
 * repositories and the audit sink are mocked.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowVersionServiceTest {

  private static final String VALID_GRAPH =
      """
      {"schemaVersion":"1.0.0","nodes":[{"id":"n1","type":"RETRIEVE_CONTEXT","config":{}},{"id":"n2","type":"FINALIZE","config":{}}],"edges":[{"from":"n1","to":"n2"}]}""";

  private static final String INVALID_GRAPH =
      """
      {"schemaVersion":"1.0.0","nodes":[{"id":"n1","type":"SCRIPT"}]}""";

  @Mock private WorkflowDefinitionRepository definitionRepository;
  @Mock private WorkflowVersionRepository versionRepository;
  @Mock private AuditService auditService;

  private final AgentFlowProperties properties = new AgentFlowProperties();

  private WorkflowVersionService service() {
    return new WorkflowVersionService(
        definitionRepository, versionRepository, auditService, properties);
  }

  private WorkflowVersion version(int version, String status, String checksum) {
    return new WorkflowVersion(
        UUID.randomUUID(),
        UUID.randomUUID(),
        version,
        null,
        checksum,
        status,
        "alice",
        Instant.now());
  }

  // --- create ----------------------------------------------------------------

  @Test
  void create_version_validates_serializes_and_inserts_a_draft_with_pinned_checksum() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    WorkflowVersion stored =
        new WorkflowVersion(
            UUID.randomUUID(),
            workflow,
            1,
            null,
            "stored-checksum",
            WorkflowVersionStatus.DRAFT,
            "alice",
            Instant.now());
    when(definitionRepository.findByTenantAndId(tenant, workflow))
        .thenReturn(Optional.of(WorkflowDefinition.draft(tenant, "Report", null, "local")));
    when(versionRepository.nextVersionNumber(tenant, workflow)).thenReturn(1);
    when(versionRepository.create(any(), any(), anyInt(), any(), any(), any()))
        .thenReturn(Optional.of(stored));

    WorkflowVersionDto dto = service.createVersion(tenant, workflow, "alice", VALID_GRAPH);

    assertThat(dto.id()).isNotNull();
    assertThat(dto.workflowId()).isEqualTo(workflow);
    assertThat(dto.version()).isEqualTo(1);
    assertThat(dto.status()).isEqualTo(WorkflowVersionStatus.DRAFT);
    // the checksum is pinned over exactly the stored graph bytes (computed by the service)
    ArgumentCaptor<String> graph = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> checksum = ArgumentCaptor.forClass(String.class);
    verify(versionRepository)
        .create(any(), any(), anyInt(), graph.capture(), checksum.capture(), anyString());
    assertThat(checksum.getValue()).isEqualTo(WorkflowVersionService.checksum(graph.getValue()));
    assertThat(dto.checksum()).isEqualTo(stored.checksum());
    // a successful create records an audit event (S04-12), linked to the actor, no credential
    verify(auditService)
        .record(
            tenant,
            "alice",
            AuditAction.CREATE,
            AuditResourceType.WORKFLOW_DEFINITION,
            stored.id());
  }

  @Test
  void create_version_invalid_graph_is_rejected_before_storage() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    when(definitionRepository.findByTenantAndId(tenant, workflow))
        .thenReturn(Optional.of(WorkflowDefinition.draft(tenant, "Report", null, "local")));

    assertThatThrownBy(() -> service.createVersion(tenant, workflow, "alice", INVALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    // invalid graph never reaches the database and never audits
    verify(versionRepository, never()).create(any(), any(), anyInt(), any(), any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void create_version_cross_tenant_definition_is_not_found() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    when(definitionRepository.findByTenantAndId(any(UUID.class), any(UUID.class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createVersion(tenant, workflow, "alice", VALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    verify(versionRepository, never()).create(any(), any(), any(), any(), any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void create_version_conflict_when_definition_is_not_writable_records_no_audit() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();
    when(definitionRepository.findByTenantAndId(tenant, workflow))
        .thenReturn(Optional.of(WorkflowDefinition.draft(tenant, "Report", null, "local")));
    when(versionRepository.nextVersionNumber(tenant, workflow)).thenReturn(1);
    when(versionRepository.create(any(), any(), anyInt(), any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createVersion(tenant, workflow, "alice", VALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void create_version_without_database_is_unavailable() {
    WorkflowVersionService service =
        new WorkflowVersionService(null, null, auditService, properties);
    UUID tenant = UUID.randomUUID();
    UUID workflow = UUID.randomUUID();

    assertThatThrownBy(() -> service.createVersion(tenant, workflow, "alice", VALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  // --- activate ------------------------------------------------------------

  @Test
  void activate_version_transitions_and_audits() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    WorkflowVersion draft = version(2, WorkflowVersionStatus.DRAFT, "draft-checksum");
    UUID versionId = draft.id();
    WorkflowVersion activated = version(2, WorkflowVersionStatus.ACTIVATED, "draft-checksum");
    when(versionRepository.get(tenant, versionId))
        .thenReturn(Optional.of(draft))
        .thenReturn(Optional.of(activated));
    when(versionRepository.activate(tenant, versionId, draft.checksum())).thenReturn(1);

    WorkflowVersionDto dto =
        service.activateVersion(tenant, draft.workflowId(), versionId, "alice");

    assertThat(dto.status()).isEqualTo(WorkflowVersionStatus.ACTIVATED);
    assertThat(dto.checksum()).isEqualTo("draft-checksum");
    verify(versionRepository).activate(tenant, versionId, draft.checksum());
    verify(auditService)
        .record(
            tenant,
            "alice",
            AuditAction.ACTIVATE,
            AuditResourceType.WORKFLOW_DEFINITION,
            activated.id());
  }

  @Test
  void activate_already_activated_version_is_idempotent_without_a_second_audit() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    WorkflowVersion activated = version(1, WorkflowVersionStatus.ACTIVATED, "c");
    UUID versionId = activated.id();
    when(versionRepository.get(tenant, versionId)).thenReturn(Optional.of(activated));

    // Re-activation returns the same immutable version — no error, no transition, no second audit.
    WorkflowVersionDto dto =
        service.activateVersion(tenant, activated.workflowId(), versionId, "alice");

    assertThat(dto.status()).isEqualTo(WorkflowVersionStatus.ACTIVATED);
    assertThat(dto.id()).isEqualTo(activated.id());
    verify(versionRepository, never()).activate(any(), any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void activate_a_version_out_of_its_workflow_is_not_found() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    WorkflowVersion draft = version(1, WorkflowVersionStatus.DRAFT, "c");
    UUID versionId = draft.id();
    // get returns the tenant's draft, but the addressed workflowId does not match the version's
    // parent workflow.
    when(versionRepository.get(tenant, versionId)).thenReturn(Optional.of(draft));

    assertThatThrownBy(() -> service.activateVersion(tenant, UUID.randomUUID(), versionId, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    // nothing transitions and nothing is audited
    verify(versionRepository, never()).activate(any(), any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void activate_a_version_with_a_tampered_checksum_is_mapped_to_a_409_conflict() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    WorkflowVersion draft = version(1, WorkflowVersionStatus.DRAFT, "c");
    UUID versionId = draft.id();
    when(versionRepository.get(tenant, versionId)).thenReturn(Optional.of(draft));
    // the conditional transition changes zero rows (the stored checksum does not match the call's)
    when(versionRepository.activate(tenant, versionId, "c")).thenReturn(0);

    // The transition IS attempted (the conditional update returns 0 — the stored checksum did not
    // match) but the invariant breach is surfaced as a 409 Conflict (invariant 6), never a silent
    // success and never a 500: the owning service turns it into a ResponseStatusException inline,
    // so the common error handler can render it without importing this module's domain types.
    assertThatThrownBy(
            () -> service.activateVersion(tenant, draft.workflowId(), versionId, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // The transition was attempted (activate returned 0) and then refused as a conflict; no audit
    // row is written for the refused transition.
    verify(versionRepository).activate(any(), any(), any());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void activate_cross_tenant_version_is_not_found() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(versionRepository.get(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.activateVersion(tenant, UUID.randomUUID(), versionId, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void activate_version_without_database_is_unavailable() {
    WorkflowVersionService service =
        new WorkflowVersionService(null, null, auditService, properties);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    assertThatThrownBy(() -> service.activateVersion(tenant, UUID.randomUUID(), versionId, "alice"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  // --- get -----------------------------------------------------------------

  @Test
  void get_version_returns_tenant_scoped_version() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    WorkflowVersion stored = version(3, WorkflowVersionStatus.ACTIVATED, "c");
    when(versionRepository.get(tenant, versionId)).thenReturn(Optional.of(stored));

    WorkflowVersionDto dto = service.getVersion(tenant, versionId);

    assertThat(dto.id()).isEqualTo(stored.id());
    assertThat(dto.status()).isEqualTo(WorkflowVersionStatus.ACTIVATED);
  }

  @Test
  void get_cross_tenant_version_is_not_found() {
    WorkflowVersionService service = service();
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    when(versionRepository.get(any(UUID.class), any(UUID.class))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getVersion(tenant, versionId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void get_version_without_database_is_unavailable() {
    WorkflowVersionService service =
        new WorkflowVersionService(null, null, auditService, properties);
    UUID tenant = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    assertThatThrownBy(() -> service.getVersion(tenant, versionId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  // --- checksum ------------------------------------------------------------

  @Test
  void checksum_is_the_sha256_of_the_stored_graph_json() {
    // SHA-256 of "hello" (well-known constant):
    // 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
    assertThat(WorkflowVersionService.checksum("hello"))
        .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertThat(WorkflowVersionService.checksum("")).isNotBlank();
    // different bytes produce different checksums (stable, order-sensitive)
    assertThat(WorkflowVersionService.checksum("abc"))
        .isNotEqualTo(WorkflowVersionService.checksum("abd"));
  }
}
