package com.example.agentflow.architecture;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Minimal directed-graph cycle detector used by the S02-09 architecture gate.
 *
 * <p>The module graph of the modular monolith is a directed graph whose nodes are the module
 * packages ({@code com.example.agentflow.<module>}) and whose edges are the compile-time
 * dependencies between them. {@link #findCycles()} enumerates every elementary cycle in that graph.
 * {@link NoCyclicModuleDependenciesTest} feeds it the graph of the compiled application and asserts
 * the result is empty; {@link PackageDependencyGraphTest} feeds it a specifically created cycle and
 * asserts it is reported.
 *
 * <p>Self-loops (an edge whose target equals its source) are ignored: a module may legitimately use
 * its own packages, so a self-edge is never a cycle. Each elementary cycle is reported once, rooted
 * at its lexicographically smallest node, so the same cycle is never emitted twice.
 */
public final class PackageDependencyGraph {

  private final Map<String, Set<String>> adjacency;

  /**
   * Builds the graph from an adjacency map ({@code node -&gt; set of target nodes}). Self-loops are
   * dropped. Every node that appears as a target is registered as a key even if it has no outgoing
   * edges, so {@link #successors(String)} is never {@code null}.
   *
   * @param edges adjacency map, module {@code name -&gt; modules it compiles against}
   */
  public PackageDependencyGraph(Map<String, Set<String>> edges) {
    this.adjacency = new LinkedHashMap<>();
    edges.forEach(this::putEdge);
  }

  private void putEdge(String from, Set<String> targets) {
    Set<String> filtered = new HashSet<>();
    for (String target : targets) {
      if (!target.equals(from)) {
        filtered.add(target);
      }
    }
    adjacency.put(from, filtered);
    for (String target : filtered) {
      adjacency.computeIfAbsent(target, key -> new HashSet<>());
    }
  }

  /** Returns every node in the graph (targets included, even leaf nodes). */
  public Set<String> nodes() {
    return new TreeSet<>(adjacency.keySet());
  }

  /**
   * @return {@code true} if the graph contains at least one cycle.
   */
  public boolean hasCycle() {
    return !findCycles().isEmpty();
  }

  /**
   * Enumerates every elementary cycle. Each cycle is a list of nodes in traversal order ({@code A,
   * B, C} means {@code A -&gt; B -&gt; C -&gt; A}); the start node is repeated implicitly at the
   * end. Cycles are returned with the smallest node first and in a stable order.
   *
   * @return the list of elementary cycles (empty when the graph is acyclic)
   */
  public List<List<String>> findCycles() {
    List<List<String>> cycles = new ArrayList<>();
    for (String start : nodes()) {
      List<String> path = new ArrayList<>();
      Set<String> onPath = new HashSet<>();
      path.add(start);
      onPath.add(start);
      findCyclesFrom(start, start, path, onPath, cycles);
    }
    return cycles;
  }

  /**
   * DFS rooted at {@code start}. Only nodes that sort greater than {@code start} are followed, so a
   * cycle is reported exactly once, when its smallest node is the root.
   */
  private void findCyclesFrom(
      String start, String current, List<String> path, Set<String> onPath, List<List<String>> out) {
    for (String next : sortedSuccessors(current)) {
      if (next.equals(start)) {
        out.add(new ArrayList<>(path));
      } else if (next.compareTo(start) > 0 && !onPath.contains(next)) {
        onPath.add(next);
        path.add(next);
        findCyclesFrom(start, next, path, onPath, out);
        path.remove(path.size() - 1);
        onPath.remove(next);
      }
    }
  }

  private List<String> sortedSuccessors(String node) {
    return new ArrayList<>(new TreeSet<>(adjacency.getOrDefault(node, Set.of())));
  }
}
