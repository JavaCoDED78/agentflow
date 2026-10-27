package com.example.agentflow.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.messaging.domain.WorkflowEvent;
import com.example.agentflow.messaging.persistence.WorkflowEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowEventServiceTest {

  @Mock private WorkflowEventRepository repository;

  @Test
  void append_builds_record_and_inserts_it() {
    WorkflowEventService service = new WorkflowEventService(repository);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    UUID id = UUID.randomUUID();

    Mockito.when(repository.insert(Mockito.any(WorkflowEvent.class))).thenReturn(id);

    UUID persisted = service.append(tenant, run, null, "RUNNING", "prior=QUEUED new=RUNNING");

    Mockito.verify(repository).insert(Mockito.any(WorkflowEvent.class));
    assertThat(persisted).isEqualTo(id);
  }

  @Test
  void historyByRun_forwards_tenant_and_run() {
    WorkflowEventService service = new WorkflowEventService(repository);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    List<WorkflowEvent> expected =
        List.of(
            new WorkflowEvent(
                UUID.randomUUID(),
                tenant,
                run,
                null,
                "RUNNING",
                "prior=QUEUED new=RUNNING",
                java.time.Instant.now()));

    Mockito.when(repository.findByTenantAndRunId(tenant, run)).thenReturn(expected);

    assertThat(service.historyByRun(tenant, run)).isEqualTo(expected);
    Mockito.verify(repository).findByTenantAndRunId(tenant, run);
  }

  @Test
  void operations_fail_loudly_without_database() {
    WorkflowEventService service = new WorkflowEventService(null);
    UUID tenant = UUID.randomUUID();
    UUID run = UUID.randomUUID();

    assertThatThrownBy(
            () -> service.append(tenant, run, null, "RUNNING", "prior=QUEUED new=RUNNING"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection is configured");
    assertThatThrownBy(() -> service.historyByRun(tenant, run))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no database connection is configured");
  }
}
