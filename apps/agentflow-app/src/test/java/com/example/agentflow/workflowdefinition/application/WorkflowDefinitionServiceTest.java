package com.example.agentflow.workflowdefinition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
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

  @Test
  void createDefinition_buildsDraftWithTenantId() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository);
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
  }

  @Test
  void createDefinition_reportsDuplicateNameAsConflict() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(repository);
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
  void createDefinition_withoutDatabaseIsUnavailable() {
    WorkflowDefinitionService service = new WorkflowDefinitionService(null);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000003");

    assertThatThrownBy(() -> service.createDefinition(tenant, "local", "Report", null))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }
}
