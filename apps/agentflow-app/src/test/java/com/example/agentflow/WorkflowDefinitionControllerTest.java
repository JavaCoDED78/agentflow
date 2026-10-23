package com.example.agentflow;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.error.GlobalExceptionHandler;
import com.example.agentflow.workflowdefinition.application.WorkflowDefinitionService;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.web.WorkflowDefinitionController;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Verifies the workflow definition create endpoint (S03-13) against the controller in isolation
 * with {@link MockMvc}: a valid request returns {@code 201 Created} with the {@code draft} DTO, and
 * a blank {@code name} returns a {@code 400} validation Problem Details via the real {@link
 * GlobalExceptionHandler}. The service is Mockito-mocked so the 201 path never touches a database,
 * and a real Jakarta validator drives the {@code @Valid} check.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionControllerTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private MockMvc mockMvc;
  private WorkflowDefinitionController controller;
  private WorkflowDefinitionService service;

  @BeforeEach
  void setUp() {
    service = mock(WorkflowDefinitionService.class);
    controller = new WorkflowDefinitionController(service, TENANT.toString(), "local");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(new LocalValidatorFactoryBean())
            .build();
  }

  @Test
  void create_validRequest_returnsCreated() throws Exception {
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
  void create_blankName_returnsValidationBadRequest() throws Exception {
    MockHttpServletRequestBuilder request =
        post("/api/v1/workflows")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}");

    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_class", equalTo("validation")));
  }
}
