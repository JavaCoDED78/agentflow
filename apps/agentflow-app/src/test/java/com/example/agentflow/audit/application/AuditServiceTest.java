package com.example.agentflow.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditEvent;
import com.example.agentflow.audit.domain.AuditOutcome;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.audit.persistence.AuditRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

  @Mock private AuditRepository repository;

  @Test
  void record_appends_succeeded_event_linked_to_actor() {
    AuditService service = new AuditService(repository);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID resource = UUID.randomUUID();

    service.record(
        tenant, "alice", AuditAction.CREATE, AuditResourceType.WORKFLOW_DEFINITION, resource);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(repository).insert(captor.capture());
    AuditEvent event = captor.getValue();

    assertThat(event.tenantId()).isEqualTo(tenant);
    assertThat(event.actorSubject()).isEqualTo("alice");
    assertThat(event.action()).isEqualTo(AuditAction.CREATE);
    assertThat(event.resourceType()).isEqualTo(AuditResourceType.WORKFLOW_DEFINITION);
    assertThat(event.resourceId()).isEqualTo(resource);
    assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
    assertThat(event.id()).isNotNull();
    assertThat(event.createdAt()).isNotNull();
    // no credential / no correlation / no metadata: the event carries only the actor subject
    assertThat(event.correlationId()).isNull();
    assertThat(event.metadataRedacted()).isNull();
  }

  @Test
  void record_without_database_throws_unavailability() {
    AuditService service = new AuditService(null);
    UUID tenant = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertThatThrownBy(
            () ->
                service.record(
                    tenant,
                    "alice",
                    AuditAction.CREATE,
                    AuditResourceType.WORKFLOW_DEFINITION,
                    null))
        .isInstanceOf(IllegalStateException.class);
  }
}
