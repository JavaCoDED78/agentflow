package com.example.agentflow.common;

import java.util.regex.Pattern;

/**
 * Centralised secret redaction (S12-09).
 *
 * <p>A single, stateless utility that strips bearer tokens, API keys, provider / authorization
 * headers, AWS access keys, raw document text and full prompts from any text before it is written
 * to a durable or observable surface. It is the one source of truth for <em>what</em> a secret
 * looks like, so the structured-log path (the {@code logback-spring.xml}-registered {@code
 * ValueMasker}), the append-only audit trail ({@code audit.metadata_redacted}) and the dead-letter
 * description ({@code messaging.dlq_messages.description}) all redact identically and none of them
 * can drift apart (invariant 8: secrets never reach logs, audit metadata or the DLQ).
 *
 * <p>The covered shapes match the ones the S01-19 log masker already applied, kept byte-for-byte so
 * the regression guard ({@code CanaryLeakScanTest}) sees identical behaviour:
 *
 * <ul>
 *   <li>{@code Bearer abc.def.ghi} and JWTs (three base64url segments) &mdash; the token is masked,
 *       the {@code Bearer} keyword kept;
 *   <li>AWS access-key id, for example {@code AKIA} followed by sixteen upper-case alphanumerics
 *       &mdash; only the secret half is masked ({@code AKIA***});
 *   <li>{@code key=<secret>} / {@code key: <secret>} pairs — and the JSON-object form {@code
 *       "api_key": "…"} (the optional closing quote) — for sensitive keys ({@code api_key}, {@code
 *       bearer}, {@code secret}, {@code password}, {@code client_secret}, {@code auth_token},
 *       {@code access_token}, {@code token} &mdash; only the value is masked;
 *   <li>the value of a prompt / document field ({@code document}, {@code document_body}, {@code
 *       prompt}, {@code system_prompt}, {@code user_content}, {@code messages}) &mdash; untrusted
 *       content (invariant 7 / S09-13), so the whole value is masked to end of value, not only up
 *       to the first whitespace.
 * </ul>
 *
 * <p>A secret embedded in a longer message (for example {@code "token Bearer abc.def.ghi"}) is
 * masked too, not only when the whole value is a secret. {@code null} passes through untouched, so
 * a metadata field that carries nothing stays {@code null} in the audit row.
 *
 * <p>Deliberately a plain utility (the {@code Fingerprint} idiom, not a Spring bean): it holds no
 * state and no business logic, so it belongs in {@code common} as a shared primitive that every
 * module that writes an observable or durable string may call directly.
 */
public final class SecretRedactor {

  private static final String MASK = "***";

  // "Bearer abc.def.ghi" and JWTs (three base64url segments joined by dots).
  private static final Pattern BEARER_TOKEN =
      Pattern.compile("((?i)Bearer\\s+)([A-Za-z0-9\\-._~+/]+={0,2})");

  // AWS access key id, for example «redacted:AKIA…».
  private static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");

  // key=value / key:secret pairs for sensitive keys (api key, bearer, token, password, ...).
  // The optional closing quote matches the JSON-object form {"api_key": "…"} alongside the bare
  // api_key=… form, so a secret in a JSON log line / metadata value is masked too.
  private static final Pattern KEY_SECRET =
      Pattern.compile(
          "(?i)\\b(api[_-]?key|apikey|bearer|secret|password|passwd|client[_-]?secret|auth[_-]?token|access[_-]?token|token)(\"?)(\\s*[=:]\\s*)[^\\s',;}]+");

  // The value of a prompt / document field is untrusted content (a full prompt, a document body,
  // the injected USER messages): none of it may reach a log, audit row or DLQ description, so the
  // whole value is masked, not only up to the first whitespace.
  private static final Pattern PROMPT_CONTENT =
      Pattern.compile(
          "(?is)\\b(document|document[_-]?body|prompt|system[_-]?prompt|user[_-]?content|messages)(\\s*[=:]\\s*)(.*)$");

  private SecretRedactor() {}

  /**
   * Mask every secret-like value in {@code text}.
   *
   * @param text the raw text about to reach an observable or durable surface; may be {@code null}
   * @return the text with bearer tokens, API keys, provider headers, AWS keys, document bodies and
   *     full prompts masked to {@code ***}; {@code null} when the input is {@code null}
   */
  public static String redact(String text) {
    if (text == null) {
      return null;
    }
    String masked = BEARER_TOKEN.matcher(text).replaceAll("$1" + MASK);
    masked = AWS_ACCESS_KEY.matcher(masked).replaceAll("AKIA" + MASK);
    masked = KEY_SECRET.matcher(masked).replaceAll("$1$2$3" + MASK);
    masked = PROMPT_CONTENT.matcher(masked).replaceAll("$1$2" + MASK);
    return masked;
  }

  /**
   * @param text the text to inspect; may be {@code null}
   * @return {@code true} when {@code text} carries any shape {@link #redact(String)} masks — a
   *     bearer token / JWT, an AWS access key, a sensitive {@code key=value} pair, or a prompt /
   *     document field value
   */
  public static boolean containsSecret(String text) {
    if (text == null) {
      return false;
    }
    return BEARER_TOKEN.matcher(text).find()
        || AWS_ACCESS_KEY.matcher(text).find()
        || KEY_SECRET.matcher(text).find()
        || PROMPT_CONTENT.matcher(text).find();
  }
}
