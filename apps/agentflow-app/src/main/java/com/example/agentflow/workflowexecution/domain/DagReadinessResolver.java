package com.example.agentflow.workflowexecution.domain;

import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The DAG readiness resolver (S08-11): the pure gate that decides, per step, whether it may run
 * now, can never run, or must wait, from the graph edges and the <em>already-persisted</em> step
 * states — never from in-flight worker memory (invariant 7: the graph is data, the readiness
 * decision is a pure function over persisted state).
 *
 * <p>The propagation contract (the plan task's "define propagation for failure/skip/cancel"), for a
 * {@link WorkflowStepState#PENDING} step:
 *
 * <ul>
 *   <li>a predecessor {@link WorkflowStepState#FAILED} or {@link WorkflowStepState#SKIPPED} &rarr;
 *       the step is <b>skipped</b> (a decided outcome cascades: a failed or skipped upstream can
 *       never be retried into existence, so the dependent step can never run);
 *   <li>a predecessor {@link WorkflowStepState#CANCELLED} &rarr; the step is <b>cancelled</b>
 *       (cancel propagates downstream: a step whose upstream was drained by the S07-12 cancellation
 *       can never observe the predecessor output it needs);
 *   <li>every predecessor {@link WorkflowStepState#SUCCEEDED} (or no predecessors) &rarr; the step
 *       is <b>ready</b>;
 *   <li>otherwise — a predecessor still {@code RUNNING}, {@code READY}, {@code RETRY_WAIT} or
 *       {@code WAITING_FOR_APPROVAL}, or not yet materialized — the step <b>waits</b> for the next
 *       pass.
 * </ul>
 *
 * <p>A step in any state other than {@code PENDING} is never reconsidered: terminal states are
 * absorbing and in-flight states belong to their own execution (invariant 4).
 *
 * <p><b>Determinism.</b> The {@link Resolution#ready()} list is ordered topologically — a step
 * always appears after every graph predecessor it has — with ties broken by the node's declaration
 * order in {@link WorkflowGraph#nodes()}. The resolution never iterates the input state map to
 * build its output order, so the result is independent of the map's implementation or iteration
 * order (the acceptance: the order follows the graph edges and does not depend on the incidental
 * ordering of collections). Ready steps are executed <em>sequentially</em> in this order; a
 * parallel executor is a later, measured change.
 *
 * <p>The class is pure: no Spring, no persistence, no I/O, no time and no randomness (invariant
 * 10). The {@code workflowdefinition} types it reads are the immutable graph value objects the
 * module's public API already hands across the {@code workflowexecution} boundary. A graph with a
 * cycle is handled defensively (the activation validator rejects one before persistence, but the
 * resolver never trusts its input): the cycle's nodes are never emitted into the topological order,
 * so they simply never become ready and the run parks instead of executing out of order.
 */
public final class DagReadinessResolver {

  /**
   * The per-pass resolution: which pending steps may run now ({@link #ready()}, in deterministic
   * topological order), which can never run ({@link #skipped()}, failure cascade), and which are
   * cancelled by propagation ({@link #cancelled()}). A pending step in none of the three lists
   * waits for the next pass. All lists are unmodifiable.
   */
  public record Resolution(List<String> ready, List<String> skipped, List<String> cancelled) {

    /**
     * @param ready the steps whose predecessors all succeeded, in deterministic topological order
     * @param skipped the steps cascade-skipped by a failed or skipped predecessor
     * @param cancelled the steps cancelled by a cancelled predecessor
     */
    public Resolution {
      ready = List.copyOf(ready);
      skipped = List.copyOf(skipped);
      cancelled = List.copyOf(cancelled);
    }
  }

  /**
   * Resolve the readiness of every pending step of the graph against the persisted step states.
   *
   * @param graph the pinned workflow graph (never {@code null})
   * @param states the persisted step state by step key; a step absent from the map is treated as
   *     not yet materialized ({@code PENDING} semantics) — the worker materializes every graph node
   *     before resolving, so an absent row is a not-yet-started step, never a succeeded one
   * @return the {@link Resolution}; never {@code null}
   * @throws IllegalArgumentException when {@code graph} or {@code states} is {@code null} (the
   *     domain-guard convention)
   */
  public Resolution resolve(WorkflowGraph graph, Map<String, WorkflowStepState> states) {
    if (graph == null) {
      throw new IllegalArgumentException("workflow graph must not be null");
    }
    if (states == null) {
      throw new IllegalArgumentException("step states must not be null");
    }
    List<String> topoOrder = topologicalOrder(graph);
    Set<String> known = new LinkedHashSet<>(topoOrder);
    List<String> ready = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    List<String> cancelled = new ArrayList<>();
    for (String stepKey : topoOrder) {
      if (!WorkflowStepState.PENDING.equals(states.get(stepKey))) {
        // Only PENDING steps are decided here: terminal states are absorbing and in-flight states
        // belong to their own execution (invariant 4).
        continue;
      }
      Set<String> predecessors = predecessorsOf(stepKey, graph, known);
      boolean failureCascade = false;
      boolean cancelPropagation = false;
      boolean allSucceeded = true;
      for (String predecessor : predecessors) {
        WorkflowStepState predecessorState = states.get(predecessor);
        if (WorkflowStepState.FAILED.equals(predecessorState)
            || WorkflowStepState.SKIPPED.equals(predecessorState)) {
          failureCascade = true;
          break;
        }
        if (WorkflowStepState.CANCELLED.equals(predecessorState)) {
          cancelPropagation = true;
          // Keep scanning: a failed/skipped predecessor wins over a cancelled one (a decided
          // failure outcome takes precedence over the cancel drain for the classification).
          continue;
        }
        if (!WorkflowStepState.SUCCEEDED.equals(predecessorState)) {
          allSucceeded = false;
        }
      }
      // The classification order is deliberate: a failure cascade is a decided outcome and wins
      // over cancel propagation; both win over readiness.
      if (failureCascade) {
        skipped.add(stepKey);
      } else if (cancelPropagation) {
        cancelled.add(stepKey);
      } else if (predecessors.isEmpty() || allSucceeded) {
        ready.add(stepKey);
      }
      // else: the step waits for the next pass.
    }
    return new Resolution(ready, skipped, cancelled);
  }

  /**
   * Kahn's topological sort over the graph's declared nodes, with the free-choice tie broken by
   * declaration order (the first not-yet-emitted node whose indegree reached zero is emitted
   * first). Only edges whose endpoints are declared nodes are considered — the activation validator
   * rejects dangling edges before persistence, and an unknown endpoint is ignored here rather than
   * trusted. Self-references are ignored. Nodes on a cycle are never emitted: they simply never
   * become ready, so a defensively rejected cyclic graph parks instead of executing out of order.
   *
   * @return every acyclic node's id, topologically ordered by declaration-order tie-break
   */
  private static List<String> topologicalOrder(WorkflowGraph graph) {
    List<String> nodeIds = new ArrayList<>();
    Set<String> known = new LinkedHashSet<>();
    for (WorkflowNode node : graph.nodes()) {
      nodeIds.add(node.id());
      known.add(node.id());
    }
    Map<String, Integer> indegree = new HashMap<>();
    Map<String, List<String>> successors = new HashMap<>();
    for (String id : nodeIds) {
      indegree.put(id, 0);
      successors.put(id, new ArrayList<>());
    }
    for (WorkflowEdge edge : graph.edges()) {
      if (!known.contains(edge.from()) || !known.contains(edge.to())) {
        continue;
      }
      if (edge.from().equals(edge.to())) {
        continue;
      }
      successors.get(edge.from()).add(edge.to());
      indegree.merge(edge.to(), 1, Integer::sum);
    }
    boolean[] emitted = new boolean[nodeIds.size()];
    List<String> order = new ArrayList<>(nodeIds.size());
    while (order.size() < nodeIds.size()) {
      int pick = -1;
      for (int i = 0; i < nodeIds.size(); i++) {
        if (!emitted[i] && indegree.get(nodeIds.get(i)) == 0) {
          pick = i;
          break;
        }
      }
      if (pick == -1) {
        // The remaining nodes form a cycle (the activation validator prevents this; the resolver
        // never trusts its input). They are never emitted and therefore never become ready.
        break;
      }
      emitted[pick] = true;
      String id = nodeIds.get(pick);
      order.add(id);
      for (String next : successors.get(id)) {
        indegree.merge(next, -1, Integer::sum);
      }
    }
    return order;
  }

  /**
   * @return the distinct declared predecessor node ids of {@code stepKey}, from the edges whose
   *     {@code to} is the step
   */
  private static Set<String> predecessorsOf(
      String stepKey, WorkflowGraph graph, Set<String> known) {
    Set<String> predecessors = new LinkedHashSet<>();
    for (WorkflowEdge edge : graph.edges()) {
      if (edge.to().equals(stepKey) && known.contains(edge.from())) {
        predecessors.add(edge.from());
      }
    }
    return predecessors;
  }
}
