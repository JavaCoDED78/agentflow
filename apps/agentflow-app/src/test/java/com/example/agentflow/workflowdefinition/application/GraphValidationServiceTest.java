package com.example.agentflow.workflowdefinition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the {@link GraphValidationService} validation use case (S05-13) in isolation.
 *
 * <p>It confirms that a valid graph returns a valid {@link GraphValidationResult}, that an invalid
 * one returns the ordered {@code violations}, that a cross-tenant id is a {@code 404} (the
 * tenant-scoped definition lookup returns empty for the wrong tenant), and that a {@code null}
 * repository (no database) is a {@code 503}. No version is created and nothing is audited — the
 * service is read only.
 */
@ExtendWith(MockitoExtension.class)
class GraphValidationServiceTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static final UUID WORKFLOW_ID = UUID.randomUUID();

  private static final String VALID_GRAPH =
      "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"finalize-1\",\"type\":\"FINALIZE\"}],\"edges\":[]}";

  private static final String INVALID_GRAPH =
      "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"s1\",\"type\":\"SCRIPT\"}]}";

  @Mock WorkflowDefinitionRepository definitionRepo;

  /**
   * @return a service wired to the mocked definition repository
   */
  private GraphValidationService wiredService() {
    return new GraphValidationService(definitionRepo);
  }

  /**
   * @return a service with no definition repository (no database wired)
   */
  private GraphValidationService unservice() {
    return new GraphValidationService(null);
  }

  @Test
  void validate_valid_graph_returns_valid_result() {
    when(definitionRepo.findByTenantAndId(TENANT, WORKFLOW_ID))
        .thenReturn(Optional.of(WorkflowDefinition.draft(TENANT, "Report", null, "local")));

    GraphValidationResult result = wiredService().validateGraph(TENANT, WORKFLOW_ID, VALID_GRAPH);

    assertThat(result.valid()).isTrue();
    assertThat(result.violations()).isEmpty();
  }

  @Test
  void validate_invalid_graph_returns_ordered_violations() {
    when(definitionRepo.findByTenantAndId(TENANT, WORKFLOW_ID))
        .thenReturn(Optional.of(WorkflowDefinition.draft(TENANT, "Report", null, "local")));

    GraphValidationResult result = wiredService().validateGraph(TENANT, WORKFLOW_ID, INVALID_GRAPH);

    assertThat(result.valid()).isFalse();
    assertThat(result.violations()).hasSize(1);
    GraphValidationResult.Violation v = result.violations().get(0);
    assertThat(v.path()).isEqualTo("nodes[0]");
    assertThat(v.message()).isEqualTo("unknown workflow node type: SCRIPT");
  }

  @Test
  void validate_cross_tenant_id_returns_not_found() {
    // The tenant-scoped lookup returns empty for the wrong tenant.
    when(definitionRepo.findByTenantAndId(TENANT, WORKFLOW_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> wiredService().validateGraph(TENANT, WORKFLOW_ID, VALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void validate_without_database_returns_service_unavailable() {
    assertThatThrownBy(() -> unservice().validateGraph(TENANT, WORKFLOW_ID, VALID_GRAPH))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
