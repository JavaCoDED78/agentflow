package com.example.agentflow;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.error.GlobalExceptionHandler;
import com.example.agentflow.workflowdefinition.application.GraphValidationResult;
import com.example.agentflow.workflowdefinition.application.GraphValidationService;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionPageDto;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.web.WorkflowDefinitionController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the workflow definition endpoints against the controller in isolation with {@link
 * MockMvc}. The create endpoint (S03-13) returns {@code 201 Created} on a valid request and {@code
 * 400} on a blank {@code name} (both via the real {@link GlobalExceptionHandler}). The list and get
 * endpoints (S03-14) return a paginated page / the definition on a valid request and a {@code 404}
 * {@code not_found} problem for a missing id; a non-UUID path value is a {@code 400} (page/size
 * validation lives in the {@link WorkflowDefinitionService}, covered by its own unit test). The
 * service is Mockito-mocked so neither path touches a database, and a real Jakarta validator drives
 * the {@code @Valid} check.
 *
 * <p>The {@code validate} endpoint (S05-13) is a read-only preview: it returns {@code 200} with a
 * {@link GraphValidationResult} for both a valid (the {@code valid} flag {@code true}, empty {@code
 * violations}) and an invalid graph (the {@code false} flag plus the ordered {@code violations}
 * carrying each JSON {@code path} and {@code message}), creating no version and auditing nothing. A
 * cross-tenant definition id is reported exactly like a missing one ({@code 404} {@code
 * not_found}), matching the service-level {@code 404} the mocked service throws.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionControllerTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private MockMvc mockMvc;
  private WorkflowDefinitionController controller;
  private WorkflowDefinitionService service;
  private GraphValidationService graphValidationService;

  @BeforeEach
  void setUp() {
    service = mock(WorkflowDefinitionService.class);
    graphValidationService = mock(GraphValidationService.class);
    controller =
        new WorkflowDefinitionController(
            service, graphValidationService, TENANT.toString(), "local");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(new LocalValidatorFactoryBean())
            .build();
  }

  @Test
  void create_valid_request_returns_created() throws Exception {
    WorkflowDefinition fixture = WorkflowDefinition.draft(TENANT, "Report", null, "local");
    when(service.createDefinition(any(UUID.class), anyString(), eq("Report"), any()))
        .thenReturn(fixture);

    MockHttpServletRequestBuilder request =
        post("/api/v1/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Report\"}");

    mockMvc
        .perform(request)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name", equalTo("Report")))
        .andExpect(jsonPath("$.status", equalTo("DRAFT")));
  }

  @Test
  void create_blank_name_returns_validation_bad_request() throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v1/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}");

    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_class", equalTo("validation")));
  }

  @Test
  void list_valid_request_returns_paged_list() throws Exception {
    WorkflowDefinition fixture = WorkflowDefinition.draft(TENANT, "Report", null, "local");
    WorkflowDefinitionPageDto fixturePage =
        new WorkflowDefinitionPageDto(
            List.of(WorkflowDefinitionDto.from(fixture)), 0, 20, 1, 1, false, false);
    when(service.listDefinitions(any(UUID.class), anyInt(), anyInt())).thenReturn(fixturePage);

    mockMvc
        .perform(get("/api/v1/workflows").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name", equalTo("Report")))
        .andExpect(jsonPath("$.page", equalTo(0)))
        .andExpect(jsonPath("$.size", equalTo(20)))
        .andExpect(jsonPath("$.totalElements", equalTo(1)))
        .andExpect(jsonPath("$.totalPages", equalTo(1)))
        .andExpect(jsonPath("$.hasNext", equalTo(false)));
  }

  @Test
  void get_existing_definition_returns_ok() throws Exception {
    WorkflowDefinition fixture = WorkflowDefinition.draft(TENANT, "Report", null, "local");
    when(service.getDefinition(any(UUID.class), any(UUID.class))).thenReturn(fixture);

    mockMvc
        .perform(get("/api/v1/workflows/" + fixture.id()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", equalTo("Report")));
  }

  @Test
  void get_missing_definition_returns_not_found() throws Exception {
    when(service.getDefinition(any(UUID.class), any(UUID.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));

    mockMvc
        .perform(
            get("/api/v1/workflows/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_class", equalTo("not_found")));
  }

  /**
   * A definition that belongs to another tenant is reported exactly like a missing one: {@code 404}
   * with {@code error_class: not_found} (the 403/404 masking policy, S04-09, invariant 1). The
   * tenant-scoped lookup returns empty for the wrong tenant, so the caller cannot tell a foreign
   * resource from a non-existent one by the status or the problem body — guessing another tenant's
   * UUID neither reads nor changes it. This is the HTTP-boundary half of the acceptance criterion
   * (the service-level mapping is covered by {@code
   * WorkflowDefinitionServiceTest.getDefinition_crossTenantIdReturnsNotFound}).
   */
  @Test
  void get_cross_tenant_id_returns_not_found_identical_to_missing() throws Exception {
    when(service.getDefinition(any(UUID.class), any(UUID.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));

    mockMvc
        .perform(
            get("/api/v1/workflows/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_class", equalTo("not_found")));
  }

  @Test
  void get_invalid_id_returns_bad_request() throws Exception {
    // a non-UUID path value is a client error, not a 404
    mockMvc
        .perform(get("/api/v1/workflows/not-a-uuid").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  /**
   * The validate endpoint (S05-13) is a read-only preview: a valid graph returns {@code 200} with
   * {@code valid = true} and an empty {@code violations} array, and no version is created. The
   * mocked service returns the result so the controller path is exercised without a database.
   */
  @Test
  void validate_valid_graph_returns_ok_with_valid_result() throws Exception {
    GraphValidationResult result = new GraphValidationResult(true, List.of());
    when(graphValidationService.validateGraph(any(UUID.class), any(UUID.class), anyString()))
        .thenReturn(result);

    mockMvc
        .perform(
            post("/api/v1/workflows/" + UUID.randomUUID() + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"a\",\"type\":\"FINALIZE\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid", equalTo(true)))
        .andExpect(jsonPath("$.violations", hasSize(0)));
  }

  /**
   * An invalid graph returns {@code 200} with {@code valid = false} and the full, ordered list of
   * {@code violations}, each carrying its JSON {@code path} and {@code message} (S05-13). The
   * client can locate and fix every problem in one response, with no version created.
   */
  @Test
  void validate_invalid_graph_returns_ok_with_structured_violations() throws Exception {
    GraphValidationResult.Violation nodeViolation =
        new GraphValidationResult.Violation("nodes[1].type", "unknown workflow node type: SCRIPT");
    GraphValidationResult.Violation edgeViolation =
        new GraphValidationResult.Violation(
            "edges[0].from", "workflow graph edge edges[0] field 'from' must not be blank");
    GraphValidationResult result =
        new GraphValidationResult(false, List.of(nodeViolation, edgeViolation));
    when(graphValidationService.validateGraph(any(UUID.class), any(UUID.class), anyString()))
        .thenReturn(result);

    mockMvc
        .perform(
            post("/api/v1/workflows/" + UUID.randomUUID() + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"a\",\"type\":\"FINALIZE\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valid", equalTo(false)))
        .andExpect(jsonPath("$.violations[0].path", equalTo("nodes[1].type")))
        .andExpect(
            jsonPath("$.violations[0].message", equalTo("unknown workflow node type: SCRIPT")))
        .andExpect(jsonPath("$.violations[1].path", equalTo("edges[0].from")))
        .andExpect(
            jsonPath(
                "$.violations[1].message",
                equalTo("workflow graph edge edges[0] field 'from' must not be blank")));
  }

  /**
   * A definition that belongs to another tenant is reported exactly like a missing one: {@code 404}
   * with {@code error_class: not_found} (the 403/404 masking policy, S04-09, invariant 1). The
   * tenant-scoped validation lookup returns empty for the wrong tenant, so the caller cannot tell a
   * foreign resource from a non-existent one by the status or the problem body.
   */
  @Test
  void validate_cross_tenant_id_returns_not_found_identical_to_missing() throws Exception {
    when(graphValidationService.validateGraph(any(UUID.class), any(UUID.class), anyString()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));

    mockMvc
        .perform(
            post("/api/v1/workflows/" + UUID.randomUUID() + "/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"schemaVersion\":\"1.0.0\",\"nodes\":[{\"id\":\"a\",\"type\":\"FINALIZE\"}]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_class", equalTo("not_found")));
  }
}
