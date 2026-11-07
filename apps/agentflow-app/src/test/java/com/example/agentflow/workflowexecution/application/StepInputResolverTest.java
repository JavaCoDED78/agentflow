package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import com.example.agentflow.workflowexecution.domain.InputMappingException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the application edge of the step input mapping ({@link StepInputResolver}, S08-12): the
 * resolver parses only the upstream output documents the wiring actually references and delegates
 * the resolution itself to the pure {@code StepInputMapper}. DB-free.
 */
class StepInputResolverTest {

  private final StepInputResolver resolver = new StepInputResolver();

  private static WorkflowNode nodeWithWiring(
      String id, Map<String, Object> input, Map<String, String> output) {
    return new WorkflowNode(id, "NOOP", Map.of(), input, output);
  }

  @Test
  void parses_the_referenced_document_and_resolves_the_declared_output() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    Map<String, Object> resolved =
        resolver.resolve(
            downstream,
            Map.of("a", upstream, "b", downstream),
            Map.of("a", "{\"summary\":\"the summary\"}"));

    assertThat(resolved).containsExactly(Map.entry("doc", "the summary"));
  }

  @Test
  void a_node_without_wiring_never_parses_anything() {
    WorkflowNode node = nodeWithWiring("a", Map.of(), Map.of());

    Map<String, Object> resolved =
        resolver.resolve(node, Map.of("a", node), Map.of("a", "not json"));

    assertThat(resolved).isEmpty();
  }

  @Test
  void a_document_the_wiring_does_not_reference_is_never_parsed() {
    // An unreferenced upstream document may hold legacy non-JSON text: the resolver must not
    // pay to parse it, so a broken unreferenced document cannot break a wired step.
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    Map<String, Object> resolved =
        resolver.resolve(
            downstream,
            Map.of("a", upstream, "b", downstream),
            Map.of("a", "{\"summary\":\"the summary\"}", "stale", "not json at all"));

    assertThat(resolved).containsExactly(Map.entry("doc", "the summary"));
  }

  @Test
  void a_referenced_document_that_is_not_wellformed_json_is_rejected() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    assertThatThrownBy(
            () ->
                resolver.resolve(
                    downstream,
                    Map.of("a", upstream, "b", downstream),
                    Map.of("a", "{\"summary\": ")))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("invalid-upstream-output");
  }

  @Test
  void a_referenced_document_the_persistence_does_not_have_is_delegated_to_the_mapper() {
    WorkflowNode upstream = nodeWithWiring("a", Map.of(), Map.of("summary", "the summary"));
    WorkflowNode downstream =
        nodeWithWiring("b", Map.of("doc", Map.of("node", "a", "output", "summary")), Map.of());

    assertThatThrownBy(
            () -> resolver.resolve(downstream, Map.of("a", upstream, "b", downstream), Map.of()))
        .isInstanceOf(InputMappingException.class)
        .extracting(e -> ((InputMappingException) e).reason())
        .isEqualTo("missing-output");
  }

  @Test
  void null_arguments_are_rejected() {
    WorkflowNode node = nodeWithWiring("a", Map.of(), Map.of());
    assertThatThrownBy(() -> resolver.resolve(null, Map.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolve(node, null, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolve(node, Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
