package com.example.agentflow.usage.domain;

import com.example.agentflow.ai.application.ChatResponse;
import com.example.agentflow.ai.application.ChatUsage;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A single token / cost measurement written to {@code usage_records} (S12-01 / {@code V3_3_24}) —
 * the {@code usage} module's public record, the value {@link
 * com.example.agentflow.usage.application.UsageMeteringService} persists and the later budget /
 * settlement / quota tasks (S12-04…S12-06) read.
 *
 * <p>It is the mirror of the row: the same columns, same nullable handling. The token counts are
 * <b>boxed {@code Long}</b> (nullable) — a value the provider did not report is stored as {@code
 * null}, never coerced to {@code 0}: an unknown is recorded as unknown, never invented (S12-02,
 * invariant 8). {@code id} is assigned by the caller (or by {@link #fromChatResponse}); the row is
 * append-only, so there is no {@code updated_at}.
 *
 * <p>It lives in {@code domain} (like the other modules' shared records, e.g. {@code AuditEvent})
 * so both {@code application} and {@code persistence} depend on it without forming a package cycle,
 * and it is reachable through the module's public boundary (the root package {@code
 * com.example.agentflow.usage}).
 *
 * <p>The value is immutable and its canonical constructor validates the invariants the metering
 * relies on: the tenant and run come from the verified context (never {@code null}), the attempt is
 * 1-based, the {@code usage_type}/{@code measurement} are bound enums, and the measured identity is
 * present (a {@link UsageType#MODEL} / {@link UsageType#EMBEDDING} measurement names a {@code
 * model}, a {@link UsageType#TOOL} measurement names a {@code toolName}). It reads no time, no
 * randomness and no I/O (invariant 10).
 */
public record UsageRecordDto(

    /** The measurement id (the row id); assigned by the caller, never {@code null}. */
    UUID id,

    /** The owning tenant (from the verified context); never {@code null} (invariant 1). */
    UUID tenantId,

    /** The run the measurement belongs to (from the verified context); never {@code null}. */
    UUID runId,

    /** The step run the measurement belongs to, or {@code null} when the context carries none. */
    String stepRunId,

    /** The per-attempt number (1-based) the measurement is tied to; never below {@code 1}. */
    int attempt,

    /** What was measured (a bound enum); never {@code null}. */
    UsageType usageType,

    /** How the values were produced (a bound enum); never {@code null}. */
    Measurement measurement,

    /** The provider name (open-ended, non-blank — providers are not a bounded vocabulary). */
    String provider,

    /** The model id, present for {@link UsageType#MODEL} / {@link UsageType#EMBEDDING} usage. */
    String model,

    /** The tool name, present for {@link UsageType#TOOL} usage. */
    String toolName,

    /** The tool version the call used, present for {@link UsageType#TOOL} usage. */
    String toolVersion,

    /** Input tokens; {@code null} when the provider reported none — unknown, never invented. */
    Long inputTokens,

    /** Output tokens; {@code null} when the provider reported none — unknown, never invented. */
    Long outputTokens,

    /** The estimated cost / reservation (money, {@code NUMERIC}); {@code null} until known. */
    BigDecimal estimatedCost,

    /**
     * The provider-reported cost (money, {@code NUMERIC}); {@code null} unless {@link
     * Measurement#PROVIDER_REPORTED}.
     */
    BigDecimal providerCost,

    /** The pricing unit the cost is expressed in (e.g. {@code token}, {@code 1k-tokens}). */
    String unit,

    /** The cost currency (e.g. {@code USD}). */
    String currency,

    /**
     * The provider's own request id, for traceability / reconciliation; {@code null} when absent.
     */
    String providerRequestId,

    /** Remaining provider metadata (JSON text); {@code null} when none. */
    String providerMetadata) {

  /**
   * Canonical constructor — the metering invariants. A {@code null} tenant / run / id, a stale
   * attempt, a {@code null} enum, or a blank provider/measured-identity is a contract failure
   * ({@link IllegalArgumentException}), never an unclassified fault (so the worker classifies it as
   * a contract failure, matching {@code ToolDescriptor} / {@code ReportFormatter}).
   */
  public UsageRecordDto {
    if (id == null) {
      throw new IllegalArgumentException("usage record id must not be null");
    }
    if (tenantId == null) {
      throw new IllegalArgumentException("usage record tenant must not be null");
    }
    if (runId == null) {
      throw new IllegalArgumentException("usage record run must not be null");
    }
    if (attempt < 1) {
      throw new IllegalArgumentException(
          "usage record attempt must be at least 1 but was " + attempt);
    }
    if (usageType == null) {
      throw new IllegalArgumentException("usage record usage type must not be null");
    }
    if (measurement == null) {
      throw new IllegalArgumentException("usage record measurement must not be null");
    }
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("usage record provider must not be blank");
    }
    switch (usageType) {
      case MODEL, EMBEDDING -> {
        if (model == null || model.isBlank()) {
          throw new IllegalArgumentException(
              "usage record for " + usageType + " must name a model");
        }
      }
      case TOOL -> {
        if (toolName == null || toolName.isBlank()) {
          throw new IllegalArgumentException("usage record for TOOL must name a tool");
        }
      }
    }
  }

  /**
   * Build the {@link UsageType#MODEL} measurement of one chat-completion call, uniformly, from its
   * provider {@link ChatResponse} — the single recording path for model usage (the {@code usage ->
   * ai} edge, module-map arrow 8). Unknown values are carried through untouched: a {@code null}
   * {@link ChatResponse#usage()} (a refusal) leaves the token counts {@code null} (unknown, never
   * zeroed), and the cost / unit / currency stay {@code null} until the price catalogue is known
   * (S12-03), so a pre-catalogue record is truthful as an {@link Measurement#ESTIMATED} row with no
   * reserved value yet.
   *
   * @param tenantId the owning tenant (from the verified context); never {@code null}
   * @param runId the run the measurement belongs to; never {@code null}
   * @param stepRunId the step run the measurement belongs to, or {@code null}
   * @param attempt the per-attempt number (1-based) the measurement is tied to; at least {@code 1}
   * @param provider the provider name that answered the call, non-blank (e.g. {@code FAKE} in
   *     tests)
   * @param response the provider's response; never {@code null}, {@link ChatResponse#usage()} may
   *     be {@code null} (a refusal → unknown tokens)
   * @return the immutable model measurement, with a freshly assigned id
   */
  public static UsageRecordDto fromChatResponse(
      UUID tenantId,
      UUID runId,
      String stepRunId,
      int attempt,
      String provider,
      ChatResponse response) {
    if (response == null) {
      throw new IllegalArgumentException("response must not be null");
    }
    return new UsageRecordDto(
        UUID.randomUUID(),
        tenantId,
        runId,
        stepRunId,
        attempt,
        UsageType.MODEL,
        Measurement.ESTIMATED,
        provider,
        response.model(),
        null,
        null,
        usageInputTokens(response),
        usageOutputTokens(response),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** The response's input tokens, or {@code null} when the provider reported none (unknown). */
  private static Long usageInputTokens(ChatResponse response) {
    ChatUsage usage = response.usage();
    return usage == null ? null : usage.inputTokens();
  }

  /** The response's output tokens, or {@code null} when the provider reported none (unknown). */
  private static Long usageOutputTokens(ChatResponse response) {
    ChatUsage usage = response.usage();
    return usage == null ? null : usage.outputTokens();
  }
}
