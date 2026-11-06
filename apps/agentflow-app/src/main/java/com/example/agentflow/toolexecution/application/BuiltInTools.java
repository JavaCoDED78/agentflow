package com.example.agentflow.toolexecution.application;

import com.example.agentflow.agentcatalog.domain.ToolRiskClass;
import com.example.agentflow.toolexecution.domain.JsonSchema;
import com.example.agentflow.toolexecution.domain.RetryPolicy;
import com.example.agentflow.toolexecution.domain.ToolDescriptor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The built-in tool descriptors the {@link ToolRegistry} seeds at startup (S08-02).
 *
 * <p>These are the registry's seed descriptors — the <em>metadata</em> (schema, risk class, scopes,
 * timeout, retry policy) of the tools the platform ships, not their executors. The executors are
 * added later, keyed by this same {@code name} (calculator: {@code S08-05}, report formatter:
 * {@code S08-06}, the execution adapter registry: {@code S08-08}), so this factory owns the
 * descriptor and the later tasks register their execution capability by name <em>without</em>
 * re-registering the descriptor (which would be a duplicate and break startup).
 *
 * <p>Every descriptor here is a read-only tool with no side-effect scope ({@link
 * ToolDescriptor#requiredScopes()} empty) and a strictly positive timeout, so a valid, validated
 * {@link ToolDescriptor} is produced. A description is prose the Planner reads to pick a tool; it
 * is not authorization (an untrusted planner, {@code ADR-006}).
 */
public final class BuiltInTools {

  private BuiltInTools() {}

  /**
   * @return the immutable list of built-in descriptors the registry seeds at startup, in a stable
   *     order (the order is only for diagnostics and tests)
   */
  public static List<ToolDescriptor> builtinDescriptors() {
    return List.of(calculator(), reportFormatter());
  }

  /**
   * @return the calculator descriptor: a read-only, side-effect-free expression evaluator
   */
  public static ToolDescriptor calculator() {
    return ToolDescriptor.of(
        "tool-calculator-1",
        "calculator",
        "1",
        "Evaluate a safe arithmetic expression like '2 + 3 * 4'. Read-only: it changes no state and returns a number.",
        objectSchema(),
        objectSchema(),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        Duration.ofSeconds(5),
        RetryPolicy.ofDefaults(),
        false);
  }

  /**
   * @return the report formatter descriptor: a read-only, side-effect-free Markdown/JSON formatter
   */
  public static ToolDescriptor reportFormatter() {
    return ToolDescriptor.of(
        "tool-report-formatter-1",
        "report",
        "1",
        "Format structured facts and citations into a Markdown or JSON report. Read-only: it changes no state.",
        objectSchema(),
        objectSchema(),
        Set.of(),
        ToolRiskClass.READ_ONLY,
        Duration.ofSeconds(10),
        RetryPolicy.ofDefaults(),
        false);
  }

  private static JsonSchema objectSchema() {
    return JsonSchema.of(
        Map.of("$schema", "https://json-schema.org/draft/2020-12/schema", "type", "object"));
  }
}
