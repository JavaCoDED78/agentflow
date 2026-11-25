package com.example.agentflow.workflowexecution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.error.GlobalExceptionHandler;
import com.example.agentflow.workflowexecution.application.ApprovalDecisionDto;
import com.example.agentflow.workflowexecution.application.ApprovalService;
import com.example.agentflow.workflowexecution.domain.ApprovalStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the approval-decision endpoint against {@link ApprovalController} in isolation with
 * MockMvc. The service is Mockito-mocked so the path never touches a database; the tests pin the
 * contract — {@code 200} with the decision for both {@code "approve"} and {@code "reject"}, the
 * {@code Idempotency-Key}/fingerprint threading, and the {@code 400} edge cases (an unknown
 * decision, malformed JSON, a malformed key).
 */
@ExtendWith(MockitoExtension.class)
class ApprovalControllerTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final UUID RUN = UUID.randomUUID();
  private static final UUID APPROVAL = UUID.randomUUID();

  @Captor private ArgumentCaptor<UUID> keyCaptor;
  @Captor private ArgumentCaptor<String> fingerprintCaptor;
  @Captor private ArgumentCaptor<String> commentCaptor;

  private MockMvc mockMvc;
  private ApprovalService approvalService;

  @BeforeEach
  void setUp() {
    approvalService = mock(ApprovalService.class);
    ApprovalController controller =
        new ApprovalController(approvalService, TENANT.toString(), "local");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(new LocalValidatorFactoryBean())
            .build();
  }

  @Test
  void approve_returns_200_with_the_persisted_decision() throws Exception {
    when(approvalService.approve(
            eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), isNull(), isNull(), isNull()))
        .thenReturn(decided());

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"approve\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(APPROVAL.toString()))
        .andExpect(jsonPath("$.runId").value(RUN.toString()))
        .andExpect(jsonPath("$.stepKey").value("send-report"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.decisionActor").value("local"))
        .andExpect(jsonPath("$.decisionComment").value("ok"));

    // An unkeyed approve carries no key, no fingerprint and no comment to the service.
    verify(approvalService)
        .approve(
            eq(TENANT),
            eq("local"),
            eq(RUN),
            eq(APPROVAL),
            commentCaptor.capture(),
            isNull(),
            isNull());
    assertThat(commentCaptor.getValue()).isNull();
  }

  @Test
  void approve_with_idempotency_key_passes_the_key_and_a_fingerprint() throws Exception {
    UUID key = UUID.randomUUID();
    when(approvalService.approve(
            any(), any(), any(), any(), any(), eq(key), fingerprintCaptor.capture()))
        .thenReturn(decided());

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key.toString())
                .content("{\"decision\":\"approve\",\"comment\":\"ship it\"}"))
        .andExpect(status().isOk());

    verify(approvalService)
        .approve(
            eq(TENANT),
            eq("local"),
            eq(RUN),
            eq(APPROVAL),
            eq("ship it"),
            keyCaptor.capture(),
            fingerprintCaptor.capture());
    assertThat(keyCaptor.getValue()).isEqualTo(key);
    // The fingerprint binds the approval id and the payload, so a key reused across two approvals
    // can never replay as the same request.
    assertThat(fingerprintCaptor.getValue()).isNotBlank();
  }

  @Test
  void the_same_logical_payload_fingerprints_identically_regardless_of_wire_format()
      throws Exception {
    UUID key = UUID.randomUUID();
    when(approvalService.approve(
            any(), any(), any(), any(), any(), eq(key), fingerprintCaptor.capture()))
        .thenReturn(decided());

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key.toString())
                .content("{\"decision\":\"approve\",\"comment\":\"ship it\"}"))
        .andExpect(status().isOk());
    // the same logical payload in a different property order / spacing must fingerprint identically
    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key.toString())
                .content("{ \"comment\" : \"ship it\" , \"decision\" : \"approve\" }"))
        .andExpect(status().isOk());

    assertThat(fingerprintCaptor.getAllValues()).hasSize(2);
    assertThat(fingerprintCaptor.getAllValues().get(1))
        .isEqualTo(fingerprintCaptor.getAllValues().get(0));
  }

  @Test
  void reject_routes_to_the_reject_half_and_returns_200_with_the_decision() throws Exception {
    when(approvalService.reject(
            eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), isNull(), isNull(), isNull()))
        .thenReturn(rejected());

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"reject\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalId").value(APPROVAL.toString()))
        .andExpect(jsonPath("$.status").value("REJECTED"));

    verify(approvalService)
        .reject(eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), isNull(), isNull(), isNull());
    verify(approvalService, never()).approve(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void an_unknown_decision_is_a_400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"maybe\"}"))
        .andExpect(status().isBadRequest());

    verify(approvalService, never()).approve(any(), any(), any(), any(), any(), any(), any());
    verify(approvalService, never()).reject(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void a_malformed_body_is_a_400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("not json"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void a_malformed_idempotency_key_is_a_400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "not-a-uuid")
                .content("{\"decision\":\"approve\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * A cross-tenant approval is reported exactly like a missing one: {@code 404} with {@code
   * error_class: not_found}, and the body carries nothing that identifies the resource — not the
   * approval id, not the run, not the decision (the 403/404 masking policy, invariant 1). The
   * tenant-scoped lookup misses, so the caller cannot tell a foreign approval from a non-existent
   * one by the status or the problem body.
   */
  @Test
  void approve_cross_tenant_approval_is_not_found_identical_to_missing() throws Exception {
    when(approvalService.approve(
            eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), any(), any(), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, "approval " + APPROVAL + " was not found for this tenant"));

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"approve\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_class").value("not_found"))
        .andExpect(jsonPath("$.approvalId").doesNotExist())
        .andExpect(jsonPath("$.runId").doesNotExist())
        .andExpect(jsonPath("$.stepKey").doesNotExist())
        .andExpect(jsonPath("$.decisionActor").doesNotExist())
        .andExpect(jsonPath("$.decisionComment").doesNotExist());

    // the service received the configured tenant: the endpoint never passes anything from the
    // request
    verify(approvalService)
        .approve(eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), any(), any(), any());
  }

  /**
   * The same masking applies to the reject path: a cross-tenant approval cannot be rejected either.
   * The service raises a {@code 404} (the tenant-scoped lookup misses), so the caller gets the
   * identical not-found problem as for a missing approval, without the decision being visible.
   */
  @Test
  void reject_cross_tenant_approval_is_not_found_identical_to_missing() throws Exception {
    when(approvalService.reject(
            eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), any(), any(), any()))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.NOT_FOUND, "approval " + APPROVAL + " was not found for this tenant"));

    mockMvc
        .perform(
            post("/api/v1/runs/{runId}/approvals/{approvalId}", RUN, APPROVAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"reject\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error_class").value("not_found"))
        .andExpect(jsonPath("$.approvalId").doesNotExist())
        .andExpect(jsonPath("$.runId").doesNotExist())
        .andExpect(jsonPath("$.stepKey").doesNotExist())
        .andExpect(jsonPath("$.decisionActor").doesNotExist())
        .andExpect(jsonPath("$.decisionComment").doesNotExist());

    verify(approvalService)
        .reject(eq(TENANT), eq("local"), eq(RUN), eq(APPROVAL), any(), any(), any());
  }

  private static ApprovalDecisionDto decided() {
    return new ApprovalDecisionDto(
        APPROVAL,
        RUN,
        "send-report",
        ApprovalStatus.APPROVED,
        "local",
        OffsetDateTime.parse("2026-11-14T12:00:00Z"),
        "ok");
  }

  private static ApprovalDecisionDto rejected() {
    return new ApprovalDecisionDto(
        APPROVAL,
        RUN,
        "send-report",
        ApprovalStatus.REJECTED,
        "local",
        OffsetDateTime.parse("2026-11-14T12:00:00Z"),
        null);
  }
}
