package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.workflowdefinition.domain.GraphValidationIssue;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowGraphParser;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Application use case for validating a raw workflow-graph payload before a version is created or
 * activated (S05-13).
 *
 * <p>The {@code POST /api/v1/workflows/{id}/validate} endpoint delegates here. Unlike {@link
 * com.example.agentflow.workflowdefinition.application.WorkflowVersionService} this service never
 * writes anything: it is a read-only preview that reports the full, structured set of validation
 * problems (with their JSON paths) so a client can fix its payload before {@code POST /versions}.
 * No version, audit or other side effect is produced at validation time (S05-13 — "not create side
 * effects at validation-only request").
 *
 * <p>Tenant isolation (invariant 1): the definition is looked up {@code (tenantId, id)} first, so a
 * cross-tenant or missing id is a {@code 404} — the validation is scoped to a definition the tenant
 * owns and another tenant's resource is indistinguishable from a missing one.
 *
 * <p>The graph itself is client input and is treated as untrusted <em>data</em> — the parser runs
 * it through the closed-set graph gate (S05-04/S05-05) and the domain structural gate, never
 * executing anything. The {@link WorkflowGraphParser} is a pure helper (no bean in this module), so
 * it is constructed here, exactly as {@link
 * com.example.agentflow.workflowdefinition.application.WorkflowVersionService} does.
 */
@Service
public class GraphValidationService {

  private final WorkflowDefinitionRepository definitionRepo;
  private final WorkflowGraphParser parser;

  @Autowired
  public GraphValidationService(
      @Autowired(required = false) WorkflowDefinitionRepository definitionRepo) {
    this.definitionRepo = definitionRepo;
    this.parser = new WorkflowGraphParser(new ObjectMapper());
  }

  /**
   * @return {@code true} if a database (definition repository) is wired — the API answers {@code
   *     503} otherwise
   */
  public boolean isWired() {
    return definitionRepo != null;
  }

  /**
   * Validates a raw graph payload against a definition (S05-13).
   *
   * <p>Looks the definition up tenant-scoped first (a cross-tenant or missing id is a {@code 404}),
   * then runs the payload through the parser's single input + structural gate and returns every
   * problem with its JSON path. A valid graph returns a valid result, and an invalid one returns
   * {@link GraphValidationResult#invalid(List)} with the full, ordered list of violations.
   * violations. No version is created and nothing is audited — this is a read-only preview.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param workflowId the parent definition id
   * @param rawGraphJson the raw graph payload to validate (client input — never executed)
   * @return the validation result; {@code valid = true} with no violations, or {@code valid =
   *     false} with every collected issue
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database (the
   *     tenant-scoped definition lookup cannot be performed), {@code 404} if the definition is not
   *     owned by the tenant
   */
  public GraphValidationResult validateGraph(UUID tenantId, UUID workflowId, String rawGraphJson) {
    requireRepository();
    definitionRepo
        .findByTenantAndId(tenantId, workflowId)
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "workflow definition '" + workflowId + "' not found"));

    List<GraphValidationIssue> issues = parser.collectIssues(rawGraphJson);
    List<GraphValidationResult.Violation> violations =
        issues.stream()
            .map(issue -> new GraphValidationResult.Violation(issue.path(), issue.message()))
            .toList();
    return violations.isEmpty()
        ? new GraphValidationResult(true, List.of())
        : GraphValidationResult.invalid(violations);
  }

  private void requireRepository() {
    if (!isWired()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "workflow graph validation requires a database");
    }
  }
}
