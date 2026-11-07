package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the step input mapping engine ({@link StepInputMapper}, S08-12): a node's {@code input}
 * wiring resolves against the completed predecessors' persisted output documents — literals pass
 * through, {@code {node, output}} references resolve to the declared output's value, and every
 * unresolvable wiring is rejected with a stable {@link InputMappingException#reason()} before any
 * handler or tool call. Pure unit tests: the mapper reads already-parsed JSON trees, so no mapper
 * or database is involved.
 */
class StepInputMapperTest {

  private final StepInputMapper mapper = new StepInputMapper();

  private static WorkflowNode node(String id) {
    return new WorkflowNode(id, "NOOP", Map.of());
  }

  private static WorkflowNode nodeWithWiring(
      String id, Map<String, Object> input, Map<String, String> output) {
    return new WorkflowNode(id, "NOOP", Map.of(), input, output);
  }

  @Test
  void a_node_without_wiring_resolves_to_empty_inputs() {
    Map<String, Object> resolved = mapper.resolve(node("a"), Map.of("a", node("a")), Map.of());

    assertThat(resolved).isEmpty();
  }

  @Test
  void literal_strings_pass_through_unchanged() {
    Map<String, Object> wiring = new LinkedHashMap<>();
    wiring.put("mode", "strict");
    wiring.put("limit", "10");
    WorkflowNode node = nodeWithWiring("b", wiring, Map.of());
    Map<String, Object> resolved = mapper.resolve(node, Map.of("b", node), Map.of());

    assertThat(resolved).containsExactly(Map.entry("mode", "strict"), Map.entry("limit", "10"));
  }

  @Test
  void a_reference_resolves_to_the_declared_upstream_output_value() {
    WorkflowNode upstream =
        nodeWithWiring("a", Map.of(), Map.of("summary", "the produced summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());
    Map<String, Map<String, Object>> upstreamOutputs =
        Map.of("a", Map.<String, Object>of("summary", "the produced summary"));

    Map<String, Object> resolved =
        mapper.resolve(downstream, Map.of("a", upstream, "b", downstream), upstreamOutputs);

    assertThat(resolved).containsExactly(Map.entry("doc", "the produced summary"));
  }

  @Test
  void a_reference_resolves_structured_json_values_without_navigation() {
    // The resolved value is the declared output's whole JSON value — a map or a list is handed
    // over as-is; the wiring deliberately cannot navigate into arbitrary fields of the upstream
    // result (the "no access to arbitrary entity fields" rule). The persisted document carries the
    // declared output name as its key.
    Map<String, Object> produced =
        Map.of("documents", List.of(Map.of("id", "d-1"), Map.of("id", "d-2")));
    WorkflowNode upstream = nodeWithWiring("search", Map.of(), Map.of("documents", "the hits"));
    WorkflowNode downstream =
        nodeWithWiring(
            "summarize", Map.of("docs", Map.of("node", "search", "output", "documents")), Map.of());

    Map<String, Object> resolved =
        mapper.resolve(
            downstream,
            Map.of("search", upstream, "summarize", downstream),
            Map.of("search", produced));

    assertThat(resolved)
        .containsExactly(Map.entry("docs", List.of(Map.of("id", "d-1"), Map.of("id", "d-2"))));
  }

  @Test
  void a_reference_to_an_unknown_node_is_rejected() {
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "ghost", "output", "summary")), Map.of());

    assertThatThrownBy(() -> mapper.resolve(downstream, Map.of("b", downstream), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("unknown-node");
  }

  @Test
  void a_reference_to_an_output_the_node_does_not_declare_is_rejected() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "secret")), Map.of());

    assertThatThrownBy(
            () ->
                mapper.resolve(
                    downstream,
                    Map.of("a", upstream, "b", downstream),
                    Map.of("a", Map.<String, Object>of("secret", "x"))))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("unknown-output");
  }

  @Test
  void a_reference_to_a_step_without_a_persisted_output_is_rejected() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    assertThatThrownBy(
            () -> mapper.resolve(downstream, Map.of("a", upstream, "b", downstream), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("missing-output");
  }

  @Test
  void a_reference_to_a_name_absent_from_the_output_document_is_rejected() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    assertThatThrownBy(
            () ->
                mapper.resolve(
                    downstream,
                    Map.of("a", upstream, "b", downstream),
                    Map.of("a", Map.<String, Object>of("other", "value"))))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("missing-output");
  }

  @Test
  void a_null_output_value_is_rejected() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());
    Map<String, Object> documentWithNull = new LinkedHashMap<>();
    documentWithNull.put("summary", null);

    assertThatThrownBy(
            () ->
                mapper.resolve(
                    downstream,
                    Map.of("a", upstream, "b", downstream),
                    Map.of("a", documentWithNull)))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("null-output");
  }

  @Test
  void a_non_string_non_object_wiring_value_is_rejected_as_an_invalid_literal() {
    WorkflowNode downstream = nodeWithWiring("b", Map.of("count", 42), Map.of());

    assertThatThrownBy(() -> mapper.resolve(downstream, Map.of("b", downstream), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("invalid-literal");
  }

  @Test
  void a_reference_with_a_missing_or_extra_field_is_rejected_as_an_invalid_reference() {
    WorkflowNode noOutput = nodeWithWiring("b", Map.of("doc", Map.of("node", "a")), Map.of());
    assertThatThrownBy(() -> mapper.resolve(noOutput, Map.of("b", noOutput), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("invalid-reference");

    WorkflowNode extraField =
        nodeWithWiring(
            "c", Map.of("doc", Map.of("node", "a", "output", "x", "path", "$.hits")), Map.of());
    assertThatThrownBy(() -> mapper.resolve(extraField, Map.of("c", extraField), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("invalid-reference");
  }

  @Test
  void the_resolved_map_is_unmodifiable() {
    WorkflowNode node = nodeWithWiring("b", Map.of("mode", "strict"), Map.of());

    Map<String, Object> resolved = mapper.resolve(node, Map.of("b", node), Map.of());

    assertThatThrownBy(() -> resolved.put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void null_arguments_are_rejected() {
    assertThatThrownBy(() -> mapper.resolve(null, Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mapper.resolve(node("a"), null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mapper.resolve(node("a"), Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
