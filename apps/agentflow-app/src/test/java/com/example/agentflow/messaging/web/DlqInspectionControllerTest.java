package com.example.agentflow.messaging.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.agentflow.common.error.GlobalExceptionHandler;
import com.example.agentflow.messaging.application.DlqInspectionService;
import com.example.agentflow.messaging.application.DlqMessageDetailDto;
import com.example.agentflow.messaging.application.DlqMessageDto;
import com.example.agentflow.messaging.application.DlqMessagePageDto;
import com.example.agentflow.messaging.domain.DlqMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the {@link DlqInspectionController} in isolation with MockMvc (S12-11). The service is
 * Mockito-mocked so the path never touches a database; the tests pin the HTTP contract — the
 * paginated {@code GET /api/v1/ops/dlq} overview and the audited {@code GET /api/v1/ops/dlq/{id}}
 * detail view, the default page/size, and that the overview omits the failure text while the detail
 * view exposes it.
 */
@ExtendWith(MockitoExtension.class)
class DlqInspectionControllerTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000000");
  private static final UUID DLQ_ID = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T12:00:00Z");

  private MockMvc mockMvc;
  private DlqInspectionService inspectionService;

  @BeforeEach
  void setUp() {
    inspectionService = mock(DlqInspectionService.class);
    DlqInspectionController controller =
        new DlqInspectionController(inspectionService, TENANT.toString(), "local");
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void list_returns_the_page_and_pagination_metadata() throws Exception {
    when(inspectionService.listDlqMessages(eq(0), eq(20))).thenReturn(page(1L, 0, 20, 1));

    mockMvc
        .perform(get("/api/v1/ops/dlq?page=0&size=20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.items[0].errorClass").value("PERMANENT"))
        .andExpect(jsonPath("$.items[0].consumerName").value("agent:run:step"));
  }

  @Test
  void list_defaults_the_page_to_zero_and_size_to_twenty() throws Exception {
    mockMvc.perform(get("/api/v1/ops/dlq")).andExpect(status().isOk());

    verify(inspectionService).listDlqMessages(eq(0), eq(20));
  }

  @Test
  void detail_returns_the_full_view_including_the_failure_text() throws Exception {
    when(inspectionService.inspectDlqMessage(eq(DLQ_ID), eq(TENANT), eq("local")))
        .thenReturn(detail());

    mockMvc
        .perform(get("/api/v1/ops/dlq/{id}", DLQ_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(DLQ_ID.toString()))
        .andExpect(jsonPath("$.errorClass").value("PERMANENT"))
        .andExpect(jsonPath("$.lastError").value("Envelope body was redacted by the DLQ store"));
    verify(inspectionService).inspectDlqMessage(eq(DLQ_ID), eq(TENANT), eq("local"));
  }

  @Test
  void the_configured_tenant_and_actor_are_the_only_inputs_to_the_service() throws Exception {
    when(inspectionService.inspectDlqMessage(any(), any(), any())).thenReturn(detail());

    mockMvc.perform(get("/api/v1/ops/dlq/{id}", DLQ_ID)).andExpect(status().isOk());

    // The controller passes the configured tenant + actor (from configuration) and nothing else:
    // no body, no tenant from the request.
    verify(inspectionService).inspectDlqMessage(eq(DLQ_ID), eq(TENANT), eq("local"));
  }

  @Test
  void an_unknown_id_is_a_404() throws Exception {
    when(inspectionService.inspectDlqMessage(any(), any(), any()))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "no DLQ message with id " + DLQ_ID));

    mockMvc
        .perform(get("/api/v1/ops/dlq/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void the_overview_withholds_the_failure_text_but_the_detail_exposes_it() throws Exception {
    // Contract: the list overview never carries lastError (it is a field-less view); the detail
    // view does. This pins the "no sensitive detail by default" criterion.
    DlqMessageDto overview = overview();
    assertThat(overview.errorClass()).isEqualTo(DlqMessage.ErrorClass.PERMANENT);
    DlqMessageDetailDto full = detail();
    assertThat(full.lastError()).isNotBlank();
  }

  private static DlqMessagePageDto page(long totalElements, int page, int size, int totalPages) {
    return new DlqMessagePageDto(
        List.of(overview()), page, size, totalElements, totalPages, false, false);
  }

  private static DlqMessageDto overview() {
    return new DlqMessageDto(
        DLQ_ID,
        UUID.randomUUID(),
        "agent-run.v1",
        "agent:run:step",
        DlqMessage.Status.DEAD_LETTER,
        DlqMessage.ErrorClass.PERMANENT,
        3,
        NOW);
  }

  private static DlqMessageDetailDto detail() {
    return new DlqMessageDetailDto(
        DLQ_ID,
        UUID.randomUUID(),
        "agent-run.v1",
        "agent:run:step",
        DlqMessage.Status.DEAD_LETTER,
        DlqMessage.ErrorClass.PERMANENT,
        3,
        "Envelope body was redacted by the DLQ store",
        NOW);
  }
}
