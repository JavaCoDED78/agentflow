package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Coverage of {@link UsageRecordDto}: its canonical constructor invariants and the {@link
 * #fromChatResponse(UUID, UUID, String, int, String, ChatResponse)} factory that records model
 * usage uniformly (S12-02). Unknown values — a refusal's absent tokens, an unknown cost — are
 * carried through as {@code null}, never zeroed.
 */
class UsageRecordDtoTest {

  private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

  @Test
  void canonical_constructor_accepts_valid_model_record() {
    UsageRecordDto record =
        new UsageRecordDto(
            UUID.randomUUID(),
            TENANT,
            RUN,
            "step-1",
            2,
            UsageType.MODEL,
            Measurement.ESTIMATED,
            "FAKE",
            "gpt-fake",
            null,
            null,
            12L,
            6L,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(record.usageType()).isEqualTo(UsageType.MODEL);
    assertThat(record.measurement()).isEqualTo(Measurement.ESTIMATED);
    assertThat(record.provider()).isEqualTo("FAKE");
    assertThat(record.model()).isEqualTo("gpt-fake");
    assertThat(record.attempt()).isEqualTo(2);
    assertThat(record.inputTokens()).isEqualTo(12L);
    assertThat(record.outputTokens()).isEqualTo(6L);
  }

  @Test
  void canonical_constructor_rejects_null_id() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    null,
                    TENANT,
                    RUN,
                    null,
                    1,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("id");
  }

  @Test
  void canonical_constructor_rejects_null_tenant() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    null,
                    RUN,
                    null,
                    1,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("tenant");
  }

  @Test
  void canonical_constructor_rejects_null_run() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    null,
                    null,
                    1,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("run");
  }

  @Test
  void canonical_constructor_rejects_non_positive_attempt() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    0,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("attempt");
  }

  @Test
  void canonical_constructor_rejects_null_usage_type() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    1,
                    null,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("usage type");
  }

  @Test
  void canonical_constructor_rejects_null_measurement() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    1,
                    UsageType.MODEL,
                    null,
                    "FAKE",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("measurement");
  }

  @Test
  void canonical_constructor_rejects_blank_provider() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    1,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "   ",
                    "gpt-fake",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("provider");
  }

  @Test
  void canonical_constructor_rejects_model_without_model_name() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    1,
                    UsageType.MODEL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    "   ",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("model");
  }

  @Test
  void canonical_constructor_rejects_tool_without_tool_name() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new UsageRecordDto(
                    UUID.randomUUID(),
                    TENANT,
                    RUN,
                    null,
                    1,
                    UsageType.TOOL,
                    Measurement.ESTIMATED,
                    "FAKE",
                    null,
                    "   ",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

    assertThat(ex).hasMessageContaining("tool");
  }

  @Test
  void from_chat_response_maps_model_tokens() {
    ChatResponse response = new ChatResponse("gpt-fake", "hello", "stop", new ChatUsage(12, 6, 18));

    UsageRecordDto record =
        UsageRecordDto.fromChatResponse(TENANT, RUN, "step-1", 1, "FAKE", response);

    assertThat(record.id()).isNotNull();
    assertThat(record.usageType()).isEqualTo(UsageType.MODEL);
    assertThat(record.measurement()).isEqualTo(Measurement.ESTIMATED);
    assertThat(record.provider()).isEqualTo("FAKE");
    assertThat(record.model()).isEqualTo("gpt-fake");
    assertThat(record.stepRunId()).isEqualTo("step-1");
    assertThat(record.inputTokens()).isEqualTo(12L);
    assertThat(record.outputTokens()).isEqualTo(6L);
    assertThat(record.estimatedCost()).isNull();
  }

  @Test
  void from_chat_response_unknown_tokens_when_provider_reports_none() {
    // A provider refusal: usage is null, so the token counts are unknown and recorded as null,
    // never coerced to 0.
    ChatResponse response = new ChatResponse("gpt-fake", null, "stop", null);

    UsageRecordDto record = UsageRecordDto.fromChatResponse(TENANT, RUN, null, 1, "FAKE", response);

    assertThat(record.usageType()).isEqualTo(UsageType.MODEL);
    assertThat(record.inputTokens()).isNull();
    assertThat(record.outputTokens()).isNull();
  }

  @Test
  void from_chat_response_requires_non_null_response() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> UsageRecordDto.fromChatResponse(TENANT, RUN, null, 1, "FAKE", null));

    assertThat(ex).hasMessageContaining("response");
  }
}
