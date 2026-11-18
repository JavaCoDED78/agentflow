package com.example.agentflow.observability;

import net.logstash.logback.mask.ValueMasker;
import tools.jackson.core.TokenStreamContext;

/**
 * Redacts secret-like values from log field values before they are serialized to JSON, so JWTs /
 * Bearer tokens, API keys, authorization headers, passwords and AWS access keys never reach stdout,
 * Loki, disk or the Actuator (S01-19).
 *
 * <p>The redaction <em>rules</em> live in the single, shared {@link
 * com.example.agentflow.common.SecretRedactor} utility (S12-09): this class only adapts that
 * utility to the logback {@code ValueMasker} contract the {@code logback-spring.xml} encoder calls
 * for every field value, so a secret embedded in a longer message (for example {@code "token Bearer
 * abc.def.ghi"}) is masked too, not only when the whole value is a secret. Keeping the patterns in
 * one place means the log path, the audit trail and the DLQ description all redact identically and
 * can never drift apart.
 */
public class SecretRedactingValueMasker implements ValueMasker {

  @Override
  public Object mask(TokenStreamContext context, Object value) {
    if (value == null || !(value instanceof String text)) {
      return value;
    }
    return com.example.agentflow.common.SecretRedactor.redact(text);
  }
}
