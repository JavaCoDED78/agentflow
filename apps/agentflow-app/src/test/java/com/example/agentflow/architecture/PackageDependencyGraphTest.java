package com.example.agentflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PackageDependencyGraph}. The {@link #createdCycleIsDetected()} test is the
 * S02-09 acceptance evidence: a specifically created cycle must be reported by the detector.
 */
class PackageDependencyGraphTest {

  @Test
  void acyclic_chain_has_no_cycle() {
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    edges.put("A", Set.of("B"));
    edges.put("B", Set.of("C"));

    assertThat(new PackageDependencyGraph(edges).findCycles()).isEmpty();
  }

  @Test
  void created_cycle_is_detected() {
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    edges.put("A", Set.of("B"));
    edges.put("B", Set.of("C"));
    edges.put("C", Set.of("A"));

    List<List<String>> cycles = new PackageDependencyGraph(edges).findCycles();

    assertThat(cycles).hasSize(1);
    assertThat(cycles.get(0)).containsExactly("A", "B", "C");
    assertThat(new PackageDependencyGraph(edges).hasCycle()).isTrue();
  }

  @Test
  void self_loop_is_not_treated_as_cycle() {
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    edges.put("A", Set.of("A"));

    assertThat(new PackageDependencyGraph(edges).findCycles()).isEmpty();
  }

  @Test
  void declared_module_graph_is_acyclic() {
    // Mirrors docs/architecture/module-map.md arrows 1-16. Arrow 17 is runtime-only (the
    // StepHandler SPI) and is therefore not a compile edge and not part of this graph; the SPI is
    // what keeps the graph acyclic (S02-09).
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    edges.put("agentcatalog", Set.of("identity"));
    edges.put("workflowdefinition", Set.of("agentcatalog"));
    edges.put("toolexecution", Set.of("agentcatalog", "audit", "workflowexecution"));
    edges.put("knowledgebase", Set.of("ai", "workflowexecution"));
    edges.put("ingestion", Set.of("knowledgebase", "messaging"));
    edges.put("ai", Set.of("toolexecution", "workflowexecution"));
    edges.put("usage", Set.of("ai", "workflowexecution"));
    edges.put("audit", Set.of("identity"));
    edges.put("workflowexecution", Set.of("workflowdefinition", "messaging"));

    assertThat(new PackageDependencyGraph(edges).findCycles()).isEmpty();
  }
}
