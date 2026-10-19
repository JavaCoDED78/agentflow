package com.example.agentflow.logging;

import java.util.regex.Pattern;
import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * Redacts secret-like values from log field values before they are serialized to JSON, so JWTs /
 * Bearer tokens, API keys, passwords and AWS access keys never reach stdout, Loki, disk or the
 * Actuator (S01-19).
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

  @Override
  public Object mask(TokenStreamContext context, Object value) {
    if (value == null || !(value instanceof String text)) {
      return value;
    }
    String masked = BEARER_TOKEN.matcher(text).replaceAll("$1" + MASK);
    masked = AWS_ACCESS_KEY.matcher(masked).replaceAll("AKIA" + MASK);
    masked = KEY_SECRET.matcher(masked).replaceAll("$1$2" + MASK);
    return masked;
  }
}
