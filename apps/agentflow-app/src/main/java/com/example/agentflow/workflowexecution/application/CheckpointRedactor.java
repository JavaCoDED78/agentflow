package com.example.agentflow.workflowexecution.application;

import java.util.regex.Pattern;

/**
 * Redacts sensitive values out of a step output before it is persisted in a checkpoint (S07-07,
 * invariant 8 — "secrets never land in a row, column, log, metric, or the database").
 *
 * <p>A step output is <b>untrusted content</b> produced by a tool or a model (invariant 7), so it
 * may carry a password, an API key, an SSN, a credit-card number, an authorization header, or a
 * secret header. {@link #redact(String)} replaces each of those with {@code [REDACTED]} before the
 * result is stored inline in {@code workflow_step_runs.output_inline}. The rules are deliberate
 * heuristics, not a general secret scanner, and they are the ones the S07-07 tests exercise:
 *
 * <ul>
 *   <li>Bearer / {@code Authorization} headers and {@code X-*} secret headers → {@code [REDACTED]};
 *   <li>string values under secret-ish keys ({@code password}, {@code token}, {@code api_key},
 *       {@code access_token}, {@code client_secret}, …) → {@code [REDACTED]};
 *   <li>FSSN ({@code xxx-xx-xxxx}) → {@code [REDACTED]};
 *   <li>credit-card numbers (13–19 digits) → {@code [REDACTED]};
 *   <li>emails → the local part preserved, the domain replaced with {@code …} (partial email for
 *       attribution, {@code user@…}).
 * </ul>
 *
 * <p>The transformation is idempotent: re-redacting {@code [REDACTED]} leaves it unchanged, so a
 * checkpoint that has already been redacted can be re-processed without double-masking.
 */
public final class CheckpointRedactor {

  /** The replacement token for a matched secret. */
  public static final String REDACTED = "[REDACTED]";

  private static final Pattern AUTHORIZATION =
      Pattern.compile("(?i)(Authorization:\\s*(?:Bearer|Basic)\\s+)[^\\s,]+");

  private static final Pattern SECRET_HEADERS =
      Pattern.compile(
          "(?i)((?:^|[\\s,;])X-[A-Za-z0-9-]*(?:api[_-]?key|apikey|secret|token|auth)[A-Za-z0-9-]*:\\s*)[^\\s,;]+");

  private static final Pattern SECRET_JSON_VALUE =
      Pattern.compile(
          "(?i)([\"'](?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|pwd|secret|token)[\"']\\s*[:=]\\s*[\"'])([^\"']+)([\"'])");

  private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  private static final Pattern CREDIT_CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

  private static final Pattern EMAIL =
      Pattern.compile("\\b([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");

  private CheckpointRedactor() {}

  /**
   * Redacts sensitive values from {@code text}.
   *
   * @param text the raw step output (untrusted content); may be {@code null}
   * @return the redacted text, or {@code null} if {@code text} is {@code null}
   */
  public static String redact(String text) {
    if (text == null) {
      return null;
    }

    String result = EMAIL.matcher(text).replaceAll("$1@…");
    result = AUTHORIZATION.matcher(result).replaceAll("$1" + REDACTED);
    result = SECRET_HEADERS.matcher(result).replaceAll("$1" + REDACTED);
    result = SECRET_JSON_VALUE.matcher(result).replaceAll("$1" + REDACTED + "$3");
    result = SSN.matcher(result).replaceAll(REDACTED);
    result = CREDIT_CARD.matcher(result).replaceAll(REDACTED);
    return result;
  }
}
