package com.example.agentflow.observability;

import java.util.regex.Pattern;
import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * Redacts secret-like values from log field values before they are serialized to JSON, so JWTs /
 * Bearer tokens, API keys, authorization headers, passwords and AWS access keys never reach stdout,
 * Loki, disk or the Actuator (S01-19).
 *
 * <p>The value of a sensitive prompt / document field — the model's system / user prompt, the
 * document body, or the injected messages — is untrusted content (S09-13), so the whole value is
 * masked too: unlike a single-token secret it can span many words, so it is masked to end of value
 * rather than only up to the first whitespace.
 *
 * <p>Registered in {@code logback-spring.xml} via the encoder's {@code <valueMasker>} element. The
 * encoder calls {@link #mask} for every field value, so a secret that is embedded in a longer
 * message (for example {@code "token Bearer abc.def.ghi"}) is masked too, not only when the whole
 * value is a secret.
 *
 * <p>The covered patterns are a minimal starting set; {@code S13-09} refines masking once
 * correlation and tracing are wired (S13).
 */
public class SecretRedactingValueMasker implements ValueMasker {

  private static final String MASK = "***";

  // "Bearer abc.def.ghi" and JWTs (three base64url segments joined by dots).
  private static final Pattern BEARER_TOKEN =
      Pattern.compile("((?i)Bearer\\s+)([A-Za-z0-9\\-._~+/]+={0,2})");

  // AWS access key id, for example AKIAIOSFODNN7EXAMPLE.
  private static final Pattern AWS_ACCESS_KEY = Pattern.compile("AKIA[0-9A-Z]{16}");

  // key=value / key:secret pairs for sensitive keys (api key, bearer, token, password, ...).
  private static final Pattern KEY_SECRET =
      Pattern.compile(
          "(?i)\\b(api[_-]?key|apikey|bearer|secret|password|passwd|client[_-]?secret|auth[_-]?token|access[_-]?token|token)(\\s*[=:]\\s*)[^\\s',;}]+");

  // The value of a prompt / document field is untrusted content (a full prompt, a document body,
  // the injected USER messages): none of it may reach the log, so the whole value is masked, not
  // just up to the first whitespace.
  private static final Pattern PROMPT_CONTENT =
      Pattern.compile(
          "(?is)\\b(document|document[_-]?body|prompt|system[_-]?prompt|user[_-]?content|messages)(\\s*[=:]\\s*)(.*)$");

  @Override
  public Object mask(TokenStreamContext context, Object value) {
    if (value == null || !(value instanceof String text)) {
      return value;
    }
    String masked = BEARER_TOKEN.matcher(text).replaceAll("$1" + MASK);
    masked = AWS_ACCESS_KEY.matcher(masked).replaceAll("AKIA" + MASK);
    masked = KEY_SECRET.matcher(masked).replaceAll("$1$2" + MASK);
    masked = PROMPT_CONTENT.matcher(masked).replaceAll("$1$2" + MASK);
    return masked;
  }
}
