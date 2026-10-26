package com.example.agentflow.workflowdefinition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionServiceTest {

  @Mock private WorkflowDefinitionRepository repository;
  @Mock private AuditService auditService;

  @Test
  void create_definition_builds_draft_with_tenant_id() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");

    WorkflowDefinition stub = WorkflowDefinition.draft(tenant, "Report", null, "local");
    when(repository.insert(any(WorkflowDefinition.class))).thenReturn(stub);

    WorkflowDefinition result = service.createDefinition(tenant, "local", "Report", null);

    assertThat(result).isSameAs(stub);
    ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
    verify(repository).insert(captor.capture());
    WorkflowDefinition created = captor.getValue();
    assertThat(created.tenantId()).isEqualTo(tenant);
    assertThat(created.name()).isEqualTo("Report");
    assertThat(created.createdBy()).isEqualTo("local");
    assertThat(created.status()).isEqualTo(WorkflowDefinition.DRAFT);
    assertThat(created.version()).isEqualTo(0);

    // a successful create records an audit event (S04-12); it is linked to the calling actor and
    // carries no credential
    verify(auditService)
        .record(
            eq(tenant),
            eq("local"),
            eq(AuditAction.CREATE),
            eq(AuditResourceType.WORKFLOW_DEFINITION),
            eq(stub.id()));
  }

  @Test
  void create_definition_reports_duplicate_name_as_conflict() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000002");
    when(repository.insert(any(WorkflowDefinition.class)))
        .thenThrow(new DataIntegrityViolationException("unique (tenant_id, name) violated"));

    assertThatThrownBy(() -> service.createDefinition(tenant, "local", "Report", null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void create_definition_does_not_record_audit_when_insert_fails() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000002");
    when(repository.insert(any(WorkflowDefinition.class)))
        .thenThrow(new DataIntegrityViolationException("unique (tenant_id, name) violated"));

    assertThatThrownBy(() -> service.createDefinition(tenant, "local", "Report", null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));

    // no definition was created, so no audit event is written
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void create_definition_without_database_is_unavailable() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(null, null);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000003");

    assertThatThrownBy(() -> service.createDefinition(tenant, "local", "Report", null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void list_definitions_returns_tenant_scoped_page_with_pagination_metadata() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");
    WorkflowDefinition a = WorkflowDefinition.draft(tenant, "A", null, "local");
    WorkflowDefinition b = WorkflowDefinition.draft(tenant, "B", null, "local");
    when(repository.listByTenant(eq(tenant), anyInt(), anyInt())).thenReturn(List.of(a, b));
    when(repository.countByTenant(eq(tenant))).thenReturn(2L);

    WorkflowDefinitionPageDto page = service.listDefinitions(tenant, 0, 50);

    assertThat(page.items()).extracting(WorkflowDefinitionDto::name).containsExactly("A", "B");
    assertThat(page.page()).isEqualTo(0);
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.totalElements()).isEqualTo(2L);
    assertThat(page.totalPages()).isEqualTo(1);
    assertThat(page.hasNext()).isFalse();
    assertThat(page.hasPrevious()).isFalse();
  }

  @Test
  void list_definitions_clamps_oversized_size_to_max_page_size() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");
    when(repository.listByTenant(any(UUID.class), anyInt(), anyInt()))
        .thenReturn(Collections.emptyList());
    when(repository.countByTenant(any(UUID.class))).thenReturn(0L);

    WorkflowDefinitionPageDto page = service.listDefinitions(tenant, 0, 10_000);

    assertThat(page.size()).isEqualTo(100);
    ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
    verify(repository).listByTenant(any(UUID.class), captor.capture(), anyInt());
    assertThat(captor.getValue()).isEqualTo(100);
  }

  @Test
  void list_definitions_rejects_non_positive_size() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");

    assertThatThrownBy(() -> service.listDefinitions(tenant, 0, 0))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void list_definitions_rejects_negative_page() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");

    assertThatThrownBy(() -> service.listDefinitions(tenant, -1, 10))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void list_definitions_without_database_is_unavailable() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(null, null);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000009");

    assertThatThrownBy(() -> service.listDefinitions(tenant, 0, 10))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void get_definition_returns_tenant_scoped_definition() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    UUID id = UUID.randomUUID();
    WorkflowDefinition def = WorkflowDefinition.draft(tenant, "Report", null, "local");
    when(repository.findByTenantAndId(eq(tenant), eq(id))).thenReturn(Optional.of(def));

    WorkflowDefinition result = service.getDefinition(tenant, id);

    assertThat(result).isSameAs(def);
  }

  @Test
  void get_definition_cross_tenant_id_returns_not_found() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository, auditService);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    UUID id = UUID.randomUUID();
    // another tenant's id: the tenant-scoped lookup returns empty, so this is a 404, not a 403
    when(repository.findByTenantAndId(any(UUID.class), any(UUID.class)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getDefinition(tenant, id))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }
}
