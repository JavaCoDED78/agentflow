package com.example.agentflow.workflowexecution.domain;

import com.example.agentflow.common.Fingerprint;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The payload binding of a human-approval request (S11-06): the lowercase-hex SHA-256 digest of the
 * exact gated payload — the step key plus the node's full {@code config} (which carries the tool /
 * arguments / version whenever the workflow declares them), never a display summary alone.
 *
 * <p>The digest is computed over a <b>canonical JSON</b> serialization: map keys are sorted
 * lexicographically at every depth and string escaping is fixed, so the same logical config
 * fingerprints identically regardless of insertion order, platform, or serialization library
 * version. The previous ad-hoc {@code key=value} concatenation hashed {@code Map.toString()} for
 * nested values, whose iteration order is unspecified — two argument maps with the same content
 * could digest differently. A fingerprint algorithm must never drift, so the canonicalizer is
 * hand-rolled over the plain JSON value types only (null, Boolean, String, Number, Map, List) and
 * rejects anything else as a contract failure.
 *
 * <p>It is a digest, never the payload (invariant 8): the arguments themselves stay inside the
 * pinned, immutable workflow graph; only the hash rides the {@code approvals} row.
 */
public final class ApprovalPayloadFingerprint {

  private ApprovalPayloadFingerprint() {}

  /**
   * Computes the payload fingerprint one approval request binds to.
   *
   * @param stepKey the graph node / step the request gates (matches {@code workflow_step_runs.
   *     step_key} and the node's id)
   * @param config the node's config — the exact gated payload: the tool, its arguments and its
   *     version whenever the workflow declares them
   * @return the 64-character lowercase-hex SHA-256 of the canonical JSON {@code {"step": …,
   *     "config": …}}
   * @throws IllegalArgumentException when {@code stepKey} is null or blank, {@code config} is null,
   *     or a config value is not a plain JSON value
   */
  public static String compute(String stepKey, Map<String, Object> config) {
    if (stepKey == null || stepKey.isBlank()) {
      throw new IllegalArgumentException("stepKey must not be null or blank");
    }
    if (config == null) {
      throw new IllegalArgumentException("config must not be null");
    }
    StringBuilder canonical = new StringBuilder();
    canonical.append("{\"step\":");
    writeString(canonical, stepKey);
    canonical.append(",\"config\":");
    writeValue(canonical, config);
    canonical.append('}');
    return Fingerprint.sha256Hex(canonical.toString());
  }

  /** Writes one JSON value canonically; only the plain JSON value types are accepted. */
  private static void writeValue(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("null");
      case Boolean b -> out.append(b.booleanValue());
      case String s -> writeString(out, s);
      case Double d -> writeFloatingPoint(out, d, d);
      case Float f -> writeFloatingPoint(out, f.doubleValue(), f);
      case java.math.BigDecimal decimal -> out.append(decimal.stripTrailingZeros().toPlainString());
      case Integer i -> out.append(i);
      case Long l -> out.append(l);
      case Short s -> out.append(s);
      case Byte b -> out.append(b);
      case java.math.BigInteger i -> out.append(i);
      case Number n -> out.append(n);
      case Map<?, ?> map -> writeMap(out, map);
      case List<?> list -> writeList(out, list);
      default ->
          throw new IllegalArgumentException(
              "config values must be plain JSON values (null, boolean, string, number, object,"
                  + " array) but found: "
                  + value.getClass().getName());
    }
  }

  private static void writeFloatingPoint(StringBuilder out, double value, Object original) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException(
          "non-finite numbers are not valid JSON config values: " + original);
    }
    out.append(original);
  }

  /** Writes a map with its keys sorted lexicographically — the canonical form at this depth. */
  private static void writeMap(StringBuilder out, Map<?, ?> map) {
    List<String> keys = new ArrayList<>(map.size());
    for (Object key : map.keySet()) {
      if (!(key instanceof String stringKey)) {
        throw new IllegalArgumentException(
            "config map keys must be strings but found: "
                + (key == null ? "null" : key.getClass().getName()));
      }
      keys.add(stringKey);
    }
    Collections.sort(keys);
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      writeString(out, keys.get(i));
      out.append(':');
      writeValue(out, map.get(keys.get(i)));
    }
    out.append('}');
  }

  private static void writeList(StringBuilder out, List<?> list) {
    out.append('[');
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      writeValue(out, list.get(i));
    }
    out.append(']');
  }

  /** Writes a JSON string with the fixed RFC 8259 escaping (control chars as {@code \\u00XX}). */
  private static void writeString(StringBuilder out, String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
