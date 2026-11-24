package com.example.agentflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The S13-19 cardinality / privacy check: every telemetry signal the application emits is checked
 * against the machine-checked catalogue ({@link TelemetryCatalogue}) so a forbidden label, a
 * forbidden span attribute, an unbounded value or a stray log key can never ship silently.
 *
 * <p>The check has four parts, all DB-free and deterministic:
 *
 * <ol>
 *   <li><b>Catalogue walk</b> — the catalogue itself is well-formed: names are unique and
 *       lowercase, label keys are bounded and never forbidden, every label value set is closed and
 *       small (≤ 32), and every allowed attribute name is disjoint from the forbidden set.
 *   <li><b>Metric scan</b> — every {@code Counter}/{@code Timer}/{@code Gauge} built in the source
 *       tree is registered in the catalogue (a new meter without a catalogue entry fails CI), and
 *       every catalogue entry is actually produced in the source tree (a stale catalogue fails CI).
 *       Every label key a builder carries is in the catalogue's allowed set for that metric.
 *   <li><b>Trace attribute + span-name scan</b> — every {@code AttributeKey.stringKey(...)} and
 *       every span name created in the source tree is in the closed vocabulary (a span attribute
 *       outside the allowed set, a span name that is not a code-defined operation label, or an
 *       unresolvable name fails CI).
 *   <li><b>Log whitelist</b> — the encoder's whitelisted MDC keys are exactly the five pinned keys,
 *       none content-carrying (the encoder is the one place the log contract lives).
 * </ol>
 *
 * <p>The source scan deliberately resolves only lexical facts (the metric name, the label keys, the
 * closed attribute names and span names); what a tag <em>value</em> is at runtime is enforced by
 * the closed catalogue sets plus review, and what secrets reach stdout is enforced by the canary
 * and masker tests — those are separate guards, not re-implementations of this one.
 */
class TelemetryPrivacyTest {

  private static final Pattern BUILDER =
      Pattern.compile(
          "(Counter|Timer|Gauge)\\.builder\\(\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

  private static final Pattern CONSTANT =
      Pattern.compile("\\bString\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]+)\"");

  private static final Pattern TAG = Pattern.compile("\\.tag\\(\\s*\"([^\"]+)\"");

  private static final Pattern ATTRIBUTE =
      Pattern.compile("AttributeKey\\.stringKey\\(\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

  private static final Pattern SPAN_NAME = Pattern.compile("spanBuilder\\(\\s*\"([^\"]+)\"\\s*\\)");

  private static final Pattern MESSAGING_SPAN =
      Pattern.compile("MessagingTracing\\.startSpan\\(([^;]*?);");

  /** The working directory of the test task is the module directory; walk up to be robust. */
  private static Path sourceRoot() {
    Path start = new java.io.File(".").getAbsoluteFile().toPath();
    for (int hop = 0; hop < 8; hop++) {
      Path candidate = start.resolve("src/main/java");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      Path parent = start.getParent();
      if (parent == null) {
        break;
      }
      start = parent;
    }
    fail(
        "unable to locate src/main/java from the working directory "
            + new java.io.File(".").getAbsolutePath()
            + " — the source scan cannot run (the catalogue is still checked)");
    return null; // unreachable
  }

  private static List<Path> sourceFiles() {
    Path root = sourceRoot();
    try (Stream<Path> stream = Files.walk(root)) {
      return stream
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Per-file constant table: {@code IDENTIFIER -> literal} for {@code String IDENT = "..."}. */
  private static Map<String, String> constantsOf(String source) {
    Map<String, String> constants = new HashMap<>();
    Matcher matcher = CONSTANT.matcher(source);
    while (matcher.find()) {
      constants.put(matcher.group(1), matcher.group(2));
    }
    return constants;
  }

  /**
   * Resolve an identifier to its string literal via the file's constant table. Callers pass only
   * identifier expressions (the caller distinguishes literals from identifiers by which regex group
   * matched); a blank or unresolvable identifier fails loudly.
   */
  private static String resolveConstant(
      String identifier, Map<String, String> constants, String fileName) {
    if (identifier == null) {
      return null;
    }
    String literal = constants.get(identifier);
    if (literal == null) {
      fail(
          "unresolvable constant <"
              + identifier
              + "> in "
              + fileName
              + " — the check cannot verify it");
    }
    return literal;
  }

  /** What the scan found: every builder metric, its tag keys and every span/attribute in code. */
  private record Scanned(
      Map<String, List<String>> metricTags, // metric name -> distinct tag keys, in encounter order
      Set<String> attributeNames,
      Set<String> spanNames) {}

  private static Scanned scan() {
    Map<String, List<String>> metricTags = new HashMap<>();
    Set<String> attributeNames = new LinkedHashSet<>();
    Set<String> spanNames = new LinkedHashSet<>();

    for (Path file : sourceFiles()) {
      String source;
      try {
        source = Files.readString(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      Map<String, String> constants = constantsOf(source);
      String fileName = file.getFileName().toString();

      // Metrics: every Counter/Timer/Gauge builder call and its tag keys.
      Matcher builder = BUILDER.matcher(source);
      while (builder.find()) {
        String name =
            builder.group(2) != null
                ? builder.group(2)
                : resolveConstant(builder.group(3), constants, fileName);
        assertThat(name).as("metric name in " + fileName + " must not be blank").isNotBlank();
        // The chain runs from the builder call to its .register( — a bounded window that
        // captures the tag keys without reaching into unrelated code.
        int registerAt = source.indexOf(".register(", builder.end());
        int windowEnd =
            registerAt < 0 ? Math.min(source.length(), builder.end() + 600) : registerAt;
        String chain = source.substring(builder.end(), windowEnd);
        List<String> tagKeys = new ArrayList<>();
        Matcher tag = TAG.matcher(chain);
        while (tag.find()) {
          if (!tagKeys.contains(tag.group(1))) {
            tagKeys.add(tag.group(1));
          }
        }
        metricTags.computeIfAbsent(name, ignored -> new ArrayList<>());
        for (String key : tagKeys) {
          if (!metricTags.get(name).contains(key)) {
            metricTags.get(name).add(key);
          }
        }
      }

      // Span attributes: AttributeKey.stringKey(...) — literal or constant-resolved.
      Matcher attribute = ATTRIBUTE.matcher(source);
      while (attribute.find()) {
        String name =
            attribute.group(1) != null
                ? attribute.group(1)
                : resolveConstant(attribute.group(2), constants, fileName);
        assertThat(name).as("attribute name in " + fileName + " must not be blank").isNotBlank();
        attributeNames.add(name);
      }

      // Span names: the literal span name, plus the messaging boundary's startSpan name.
      Matcher span = SPAN_NAME.matcher(source);
      while (span.find()) {
        spanNames.add(span.group(1));
      }
      Matcher messagingSpan = MESSAGING_SPAN.matcher(source);
      while (messagingSpan.find()) {
        String call = messagingSpan.group(1);
        Matcher literal = Pattern.compile("\"([^\"]+)\"").matcher(call);
        if (!literal.find()) {
          fail(
              "cannot locate the span name argument in "
                  + fileName
                  + " — make it an explicit literal");
        }
        spanNames.add(literal.group(1));
      }
    }

    return new Scanned(metricTags, attributeNames, spanNames);
  }

  // --- 1. The catalogue is well-formed ------------------------------------------------

  @Test
  void metric_catalogue_is_well_formed() {
    List<TelemetryCatalogue.Metric> metrics = TelemetryCatalogue.METRICS;
    Set<String> names = new LinkedHashSet<>();
    for (TelemetryCatalogue.Metric metric : metrics) {
      assertThat(metric.name())
          .as("metric name must be lowercase and agentflow-prefixed: " + metric.name())
          .matches("^agentflow\\.[a-z0-9_-]+(\\.[a-z0-9_-]+)+$");
      assertThat(metric.unit()).as("metric " + metric.name() + " needs a unit token").isNotBlank();
      assertThat(names.add(metric.name())).as("duplicate metric name " + metric.name()).isTrue();
      Set<String> keys = new LinkedHashSet<>();
      for (TelemetryCatalogue.Label label : metric.labels()) {
        assertThat(TelemetryCatalogue.FORBIDDEN_LABEL_NAMES)
            .as("label key " + label.key() + " is forbidden (" + metric.name() + ")")
            .doesNotContain(label.key());
        assertThat(label.key())
            .as("label key must be a lowercase word: " + label.key())
            .matches("^[a-z][a-z0-9]*$");
        assertThat(keys.add(label.key()))
            .as("duplicate label key " + label.key() + " on " + metric.name())
            .isTrue();
        assertThat(label.allowedValues().size())
            .as("label <" + label.key() + "> on " + metric.name() + " must be closed and small")
            .isLessThanOrEqualTo(TelemetryCatalogue.MAX_LABEL_VALUES);
        Set<String> seen = new LinkedHashSet<>();
        for (String value : label.allowedValues()) {
          assertThat(seen.add(value))
              .as("duplicate label value " + value + " on " + label.key())
              .isTrue();
          assertThat(value)
              .as("label value must be lowercase and shape-constrained: " + value)
              .matches("^[a-z0-9][a-z0-9._-]*$");
          assertThat(TelemetryCatalogue.FORBIDDEN_LABEL_NAMES)
              .as("label value " + value + " on " + label.key() + " is forbidden")
              .doesNotContain(value);
        }
      }
    }
  }

  @Test
  void trace_attribute_vocabulary_is_closed_and_disjoint_from_forbidden() {
    Set<String> forbidden = TelemetryCatalogue.FORBIDDEN_ATTRIBUTE_NAMES;
    Set<String> allowed = TelemetryCatalogue.ALLOWED_ATTRIBUTE_NAMES;
    assertThat(allowed.size()).as("the allowed set must not be empty").isGreaterThan(0);
    assertThat(forbidden)
        .as("no allowed attribute name may be in the forbidden set")
        .doesNotContainAnyElementsOf(allowed);
    for (String attribute : allowed) {
      assertThat(attribute)
          .as("attribute name shape: " + attribute)
          .matches("^[a-z0-9][a-z0-9._-]+$");
    }
    assertThat(TelemetryCatalogue.ALLOWED_SPAN_NAMES)
        .as("span names must be closed code-defined class#method labels, never free text")
        .allMatch(name -> name.matches("^[A-Za-z][A-Za-z0-9_]*#[a-z][A-Za-z0-9_]*$"));
  }

  @Test
  void forbidden_sets_are_non_empty_and_never_whitelisted() {
    assertThat(TelemetryCatalogue.FORBIDDEN_LABEL_NAMES).isNotEmpty();
    // Note the deliberate overlap with the log whitelist: traceId/spanId/correlationId are
    // forbidden as metric LABELS (high cardinality) but are the exact pinned shape of a log line
    // and an allowed span attribute — the carriers differ, so the two sets must not be conflated.
    assertThat(TelemetryCatalogue.FORBIDDEN_ATTRIBUTE_NAMES)
        .as("no pinned log key may be a content/identifier attribute name")
        .doesNotContainAnyElementsOf(TelemetryCatalogue.ALLOWED_LOG_MDC_KEYS);
  }

  // --- 2. The source tree is checked against the catalogue -------------------------

  @Test
  void every_metric_in_code_is_in_the_catalogue() {
    Scanned scanned = scan();
    Set<String> catalogueNames =
        TelemetryCatalogue.METRICS.stream()
            .map(TelemetryCatalogue.Metric::name)
            .collect(Collectors.toSet());
    Set<String> scannedNames = scanned.metricTags().keySet();

    assertThat(scannedNames)
        .as(
            "every Micrometer meter in src/main/java must have a catalogue entry (missing: "
                + scannedNames.stream()
                    .filter(n -> !catalogueNames.contains(n))
                    .collect(Collectors.toSet())
                + ")")
        .isSubsetOf(catalogueNames);
    assertThat(catalogueNames)
        .as(
            "every catalogue entry must be produced in the source tree (stale: "
                + catalogueNames.stream()
                    .filter(n -> !scannedNames.contains(n))
                    .collect(Collectors.toSet())
                + ")")
        .isSubsetOf(scannedNames);
  }

  @Test
  void metric_labels_in_code_match_the_catalogue() {
    Scanned scanned = scan();
    for (Map.Entry<String, List<String>> entry : scanned.metricTags().entrySet()) {
      TelemetryCatalogue.Metric metric = TelemetryCatalogue.metric(entry.getKey());
      assertThat(metric).as("catalogue entry for " + entry.getKey()).isNotNull();
      Set<String> allowedKeys =
          metric.labels().stream().map(TelemetryCatalogue.Label::key).collect(Collectors.toSet());
      assertThat(entry.getValue())
          .as("label keys on " + entry.getKey() + " must be bounded keys the catalogue allows")
          .isSubsetOf(allowedKeys);
      if (allowedKeys.isEmpty()) {
        assertThat(entry.getValue())
            .as("metric " + entry.getKey() + " is documented label-free and must stay label-free")
            .isEmpty();
      }
    }
  }

  @Test
  void span_attributes_and_names_in_code_are_closed_vocabulary() {
    Scanned scanned = scan();

    assertThat(scanned.attributeNames())
        .as("every attribute in the source tree must be a catalogue-allowed name")
        .isNotEmpty();
    for (String attribute : scanned.attributeNames()) {
      assertThat(attribute)
          .as("attribute <" + attribute + "> must not be forbidden (prompt/document/id content)")
          .doesNotContain("prompt")
          .doesNotContain("document")
          .doesNotContain("payload")
          .doesNotContain("tenant")
          .doesNotContain("run")
          .doesNotContain("user")
          .doesNotContain("token");
      assertThat(TelemetryCatalogue.ALLOWED_ATTRIBUTE_NAMES)
          .as("attribute <" + attribute + "> must be in the allowed trace vocabulary")
          .contains(attribute);
      assertThat(TelemetryCatalogue.FORBIDDEN_ATTRIBUTE_NAMES)
          .as("attribute <" + attribute + "> must not be forbidden")
          .doesNotContain(attribute);
    }

    for (String spanName : scanned.spanNames()) {
      assertThat(spanName)
          .as("span name must be a code-defined class#method label, never free text")
          .matches("^[A-Za-z][A-Za-z0-9_]*#[a-z][A-Za-z0-9_]*$");
      assertThat(TelemetryCatalogue.ALLOWED_SPAN_NAMES)
          .as("span name <" + spanName + "> must be in the closed span-name vocabulary")
          .contains(spanName);
    }
  }

  // --- 3. The log whitelist ----------------------------------------------------------

  @Test
  void log_whitelist_is_exactly_the_pinned_keys() {
    assertThat(StructuredJsonLogstashEncoder.WHITELISTED_MDC_KEYS)
        .as("the encoder must carry exactly the five pinned log keys (nothing more, nothing less)")
        .containsExactlyInAnyOrderElementsOf(TelemetryCatalogue.ALLOWED_LOG_MDC_KEYS);
    for (String key : StructuredJsonLogstashEncoder.WHITELISTED_MDC_KEYS) {
      assertThat(TelemetryCatalogue.FORBIDDEN_ATTRIBUTE_NAMES)
          .as("log key <" + key + "> must not be a content/identifier name")
          .doesNotContain(key);
    }
  }
}
