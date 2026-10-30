package com.example.agentflow.workflowexecution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.error.GlobalExceptionHandler;
import com.example.agentflow.workflowexecution.application.CreateRunRequest;
import com.example.agentflow.workflowexecution.application.RunDto;
import com.example.agentflow.workflowexecution.application.RunService;
import com.example.agentflow.workflowexecution.domain.WorkflowRunState;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the create-run endpoint against {@link WorkflowRunController} in isolation with {@link
 * MockMvc}. The endpoint returns {@code 202 Accepted} (invariant 11) with the run, answers
 * idempotently under the {@code Idempotency-Key}, renders a {@code 409 conflict} when the service
 * reports a payload mismatch, and a {@code 400} when the body is not the expected JSON. The service
 * is Mockito-mocked so the path never touches a database.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRunControllerTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final UUID WORKFLOW = UUID.randomUUID();

  @Captor private ArgumentCaptor<CreateRunRequest> requestCaptor;
  @Captor private ArgumentCaptor<String> fingerprintCaptor;

  private MockMvc mockMvc;
  private RunService runService;
  private WorkflowRunController controller;

  @BeforeEach
  void setUp() {
    runService = Mockito.mock(RunService.class);
    controller = new WorkflowRunController(runService, TENANT.toString(), "local");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(new LocalValidatorFactoryBean())
            .build();
  }

  @Test
  void create_without_key_returns_202_accepted_with_a_queued_run() throws Exception {
    RunDto run = queuedRun();
    when(runService.createRun(any(), any(), any(), isNull())).thenReturn(run);

    MockHttpServletRequestBuilder request =
        post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body());

    mockMvc
        .perform(request)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value(run.id().toString()))
        .andExpect(jsonPath("$.status").value("QUEUED"));

    // An unkeyed create carries no idempotency key and no fingerprint to the service.
    verify(runService).createRun(eq(TENANT), eq("local"), requestCaptor.capture(), isNull());
    assertThat(requestCaptor.getValue().idempotencyKey()).isNull();
    assertThat(requestCaptor.getValue().workflowId()).isEqualTo(WORKFLOW);
  }

  @Test
  void create_with_idempotency_key_header_passes_the_key_and_a_fingerprint() throws Exception {
    UUID key = UUID.randomUUID();
    RunDto run = queuedRun();
    when(runService.createRun(any(), any(), any(), notNull())).thenReturn(run);

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key.toString())
                .content(body()))
        .andExpect(status().isAccepted());

    // The header key is deduplicated under, and a payload fingerprint is computed alongside it.
    verify(runService)
        .createRun(any(), any(), requestCaptor.capture(), fingerprintCaptor.capture());
    assertThat(requestCaptor.getValue().idempotencyKey()).isEqualTo(key);
    assertThat(requestCaptor.getValue().workflowId()).isEqualTo(WORKFLOW);
    assertThat(fingerprintCaptor.getValue()).isNotBlank();
  }

  @Test
  void a_conflict_from_the_service_is_rendered_as_conflict() throws Exception {
    when(runService.createRun(any(), any(), any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error_class").value("conflict"));

    // The service is invoked exactly once with the keyed request and raises the conflict; the
    // controller renders it as a 409 without retrying (a conflict is terminal, non-retryable).
    verify(runService).createRun(any(), any(), any(), any());
  }

  @Test
  void a_malformed_body_is_a_bad_request() throws Exception {
    mockMvc
        .perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error_class").value("validation"));
  }

  private static RunDto queuedRun() {
    return new RunDto(
        UUID.randomUUID(), WORKFLOW, WorkflowRunState.QUEUED.name(), 1, OffsetDateTime.now());
  }

  private static String body() {
    return "{\"workflowId\":\"" + WORKFLOW + "\"}";
  }
}
