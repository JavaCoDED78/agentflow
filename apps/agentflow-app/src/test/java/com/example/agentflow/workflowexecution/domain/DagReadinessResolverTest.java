package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the {@link DagReadinessResolver} (S08-11): the readiness gate, the
 * failure/skip/cancel propagation, and the determinism of the ready order (topological, with the
 * declaration-order tie-break — never dependent on the incidental ordering of the input map).
 */
class DagReadinessResolverTest {

  private final DagReadinessResolver resolver = new DagReadinessResolver();

  @Test
  void a_step_without_predecessors_is_ready_immediately() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a")), List.of());

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, pending("a"));

    assertThat(resolution.ready()).containsExactly("a");
    assertThat(resolution.skipped()).isEmpty();
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_step_is_ready_only_after_every_predecessor_has_succeeded() {
    // a -> b, a -> c, b -> d, c -> d: d needs both b and c
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a"), node("b"), node("c"), node("d")),
            List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));
    Map<String, WorkflowStepState> states = pending("a", "b", "c", "d");
    states.put("a", WorkflowStepState.SUCCEEDED);
    states.put("b", WorkflowStepState.SUCCEEDED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).containsExactly("c");
  }

  @Test
  void ready_steps_follow_the_graph_edges_regardless_of_declaration_order() {
    // a has succeeded, so b and c are ready in the same pass and d still waits on them. The
    // declaration order deliberately scrambles the topology: the ready list must contain exactly
    // the currently-executable steps with the free-choice tie broken by declaration order — c is
    // declared before b, so c comes first.
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("c"), node("a"), node("d"), node("b")),
            List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));
    Map<String, WorkflowStepState> states = pending("a", "b", "c", "d");
    states.put("a", WorkflowStepState.SUCCEEDED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).containsExactly("c", "b");
  }

  @Test
  void resolution_is_independent_of_the_state_maps_iteration_order() {
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("c"), node("a"), node("d"), node("b")),
            List.of(edge("a", "b"), edge("a", "c"), edge("b", "d"), edge("c", "d")));
    Map<String, WorkflowStepState> forward = pending("a", "b", "c", "d");
    forward.put("a", WorkflowStepState.SUCCEEDED);
    Map<String, WorkflowStepState> reverse = pending("d", "c", "b", "a");
    reverse.put("a", WorkflowStepState.SUCCEEDED);
    Map<String, WorkflowStepState> hashOrdered = new HashMap<>();
    hashOrdered.put("d", WorkflowStepState.PENDING);
    hashOrdered.put("a", WorkflowStepState.SUCCEEDED);
    hashOrdered.put("c", WorkflowStepState.PENDING);
    hashOrdered.put("b", WorkflowStepState.PENDING);

    DagReadinessResolver.Resolution forwardResolution = resolver.resolve(graph, forward);
    DagReadinessResolver.Resolution reverseResolution = resolver.resolve(graph, reverse);
    DagReadinessResolver.Resolution hashedResolution = resolver.resolve(graph, hashOrdered);

    assertThat(reverseResolution).isEqualTo(forwardResolution);
    assertThat(hashedResolution).isEqualTo(forwardResolution);
    assertThat(forwardResolution.ready()).containsExactly("c", "b");
  }

  @Test
  void a_failed_predecessor_cascades_the_skip() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("a", "b")));
    Map<String, WorkflowStepState> states = pending("a", "b");
    states.put("a", WorkflowStepState.FAILED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.skipped()).containsExactly("b");
    assertThat(resolution.ready()).isEmpty();
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_skipped_predecessor_cascades_the_skip_through_a_chain() {
    // a failed -> b skipped -> c must also skip (the cascade is transitive)
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a"), node("b"), node("c")), List.of(edge("a", "b"), edge("b", "c")));
    Map<String, WorkflowStepState> states = pending("a", "b", "c");
    states.put("a", WorkflowStepState.FAILED);
    states.put("b", WorkflowStepState.SKIPPED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.skipped()).containsExactly("c");
  }

  @Test
  void a_cancelled_predecessor_propagates_the_cancellation() {
    // S07-12 semantics: a step downstream of a cancelled predecessor can never observe the
    // predecessor output it needs, so it is cancelled, not skipped and not left pending.
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("a", "b")));
    Map<String, WorkflowStepState> states = pending("a", "b");
    states.put("a", WorkflowStepState.CANCELLED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.cancelled()).containsExactly("b");
    assertThat(resolution.ready()).isEmpty();
    assertThat(resolution.skipped()).isEmpty();
  }

  @Test
  void a_failure_cascade_wins_over_cancel_propagation() {
    // One predecessor failed, another was cancelled: the decided failure classifies the step as
    // SKIPPED (failure cascade takes precedence over cancel propagation).
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("f"), node("x"), node("s")), List.of(edge("f", "s"), edge("x", "s")));
    Map<String, WorkflowStepState> states = pending("f", "x", "s");
    states.put("f", WorkflowStepState.FAILED);
    states.put("x", WorkflowStepState.CANCELLED);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.skipped()).containsExactly("s");
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_running_predecessor_keeps_the_step_waiting() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("a", "b")));
    Map<String, WorkflowStepState> states = pending("a", "b");
    states.put("a", WorkflowStepState.RUNNING);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).isEmpty();
    assertThat(resolution.skipped()).isEmpty();
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_retry_waiting_predecessor_keeps_the_step_waiting() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("a", "b")));
    Map<String, WorkflowStepState> states = pending("a", "b");
    states.put("a", WorkflowStepState.RETRY_WAIT);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).isEmpty();
  }

  @Test
  void an_approval_paused_predecessor_keeps_the_step_waiting() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("a", "b")));
    Map<String, WorkflowStepState> states = pending("a", "b");
    states.put("a", WorkflowStepState.WAITING_FOR_APPROVAL);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).isEmpty();
  }

  @Test
  void a_missing_predecessor_row_is_not_a_succeeded_predecessor() {
    // The worker materializes every graph node before resolving; defensively, an absent row is a
    // not-yet-started predecessor, never a succeeded one — b -> a with b missing must not make a
    // ready.
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a"), node("b")), List.of(edge("b", "a")));

    DagReadinessResolver.Resolution resolution =
        resolver.resolve(graph, Map.of("a", WorkflowStepState.PENDING));

    assertThat(resolution.ready()).isEmpty();
  }

  @Test
  void a_step_in_a_non_pending_state_is_never_reconsidered() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a")), List.of());
    Map<String, WorkflowStepState> states = new LinkedHashMap<>();
    states.put("a", WorkflowStepState.RUNNING);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).isEmpty();
    assertThat(resolution.skipped()).isEmpty();
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_mixed_graph_resolves_every_pending_step_exactly_once() {
    // c is free, e is downstream of the failed a, f is downstream of the cancelled g, d still
    // waits on b; every classification appears exactly once and in topological order.
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a"), node("b"), node("c"), node("d"), node("e"), node("f"), node("g")),
            List.of(edge("a", "e"), edge("b", "d"), edge("g", "f")));
    Map<String, WorkflowStepState> states = pending("a", "b", "c", "d", "e", "f", "g");
    states.put("a", WorkflowStepState.FAILED);
    states.put("g", WorkflowStepState.CANCELLED);
    states.put("b", WorkflowStepState.RUNNING);

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, states);

    assertThat(resolution.ready()).containsExactly("c");
    assertThat(resolution.skipped()).containsExactly("e");
    assertThat(resolution.cancelled()).containsExactly("f");
  }

  @Test
  void a_cyclic_graph_never_executes_out_of_order() {
    // The activation validator rejects a cycle before persistence; the resolver never trusts its
    // input — the cycle's steps simply never become ready (the run parks instead of executing out
    // of order), while an acyclic root outside the cycle is unaffected.
    WorkflowGraph graph =
        new WorkflowGraph(
            List.of(node("a"), node("b"), node("c")),
            List.of(edge("a", "b"), edge("b", "a"), edge("c", "a")));

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, pending("a", "b", "c"));

    assertThat(resolution.ready()).containsExactly("c");
    assertThat(resolution.skipped()).isEmpty();
    assertThat(resolution.cancelled()).isEmpty();
  }

  @Test
  void a_dangling_edge_endpoint_is_ignored() {
    // A dangling edge (the activation validator rejects one) must not make a free step wait on a
    // node that does not exist.
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a")), List.of(edge("ghost", "a")));

    DagReadinessResolver.Resolution resolution = resolver.resolve(graph, pending("a"));

    assertThat(resolution.ready()).containsExactly("a");
  }

  @Test
  void null_arguments_are_rejected() {
    WorkflowGraph graph = new WorkflowGraph(List.of(node("a")), List.of());

    assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve(null, pending("a")));
    assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve(graph, null));
  }

  @Test
  void resolution_lists_are_unmodifiable() {
    DagReadinessResolver.Resolution resolution =
        resolver.resolve(new WorkflowGraph(List.of(node("a")), List.of()), pending("a"));

    org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> resolution.ready().add("x"));
  }

  private static Map<String, WorkflowStepState> pending(String... stepKeys) {
    Map<String, WorkflowStepState> states = new LinkedHashMap<>();
    for (String stepKey : stepKeys) {
      states.put(stepKey, WorkflowStepState.PENDING);
    }
    return states;
  }

  private static WorkflowNode node(String id) {
    return new WorkflowNode(id, "NOOP", new LinkedHashMap<>());
  }

  private static WorkflowEdge edge(String from, String to) {
    return new WorkflowEdge(from, to);
  }
}
