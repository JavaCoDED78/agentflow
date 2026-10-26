package com.example.agentflow.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.identity.application.authorization.AccessMatrix;
import com.example.agentflow.identity.application.authorization.AccessMatrix.EndpointRule;
import com.example.agentflow.identity.domain.Permission;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the S04-08 endpoint access matrix ({@link AccessMatrix}). It proves the least
 * privilege (each endpoint maps to a single minimum permission), the catalogue contract (every
 * required permission is a catalogue id), and the separation of user and operator operations.
 */
class AccessMatrixTest {

  @Test
  void every_endpoint_requires_exactly_one_catalogue_permission() {
    AccessMatrix matrix = new AccessMatrix();

    for (EndpointRule rule : matrix.rules()) {
      // Each required permission must be a member of the closed catalogue (deny-by-default sanity).
      assertThat(Permission.fromId(rule.requiredPermission().id()))
          .isPresent()
          .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(rule.requiredPermission().id()));
    }
  }

  @Test
  void create_workflow_requires_write_while_list_and_get_require_read() {
    AccessMatrix matrix = new AccessMatrix();

    EndpointRule create = find(matrix, "POST", "/api/v1/workflows");
    EndpointRule list = find(matrix, "GET", "/api/v1/workflows");
    EndpointRule getById = find(matrix, "GET", "/api/v1/workflows/*");

    assertThat(create.requiredPermission()).isEqualTo(Permission.WORKFLOW_WRITE);
    assertThat(list.requiredPermission()).isEqualTo(Permission.WORKFLOW_READ);
    assertThat(getById.requiredPermission()).isEqualTo(Permission.WORKFLOW_READ);
  }

  @Test
  void referenced_permissions_are_subset_of_catalogue() {
    AccessMatrix matrix = new AccessMatrix();

    Set<String> referenced =
        matrix.referencedPermissions().stream().map(Permission::id).collect(Collectors.toSet());

    assertThat(referenced).isNotEmpty();
    assertThat(referenced).isSubsetOf(Permission.ids());
  }

  @Test
  void user_operations_do_not_reference_operator_permissions() {
    // admin/operator operations (ops:dlq) are separated from user operations in the matrix.
    AccessMatrix matrix = new AccessMatrix();

    for (EndpointRule rule : matrix.rules()) {
      assertThat(rule.requiredPermission())
          .as("every user operation must require a user permission (never an operator one)")
          .isIn(
              Permission.WORKFLOW_READ,
              Permission.WORKFLOW_WRITE,
              Permission.WORKFLOW_ACTIVATE,
              Permission.DOCUMENT_READ,
              Permission.DOCUMENT_WRITE,
              Permission.RUN_EXECUTE,
              Permission.RUN_APPROVE);
    }
  }

  private static EndpointRule find(AccessMatrix matrix, String method, String path) {
    return matrix.rules().stream()
        .filter(rule -> rule.method().equals(method) && rule.pathPattern().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no matrix rule for " + method + " " + path));
  }
}
