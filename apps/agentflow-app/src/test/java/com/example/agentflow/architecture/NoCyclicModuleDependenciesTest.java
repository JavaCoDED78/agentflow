package com.example.agentflow.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * S02-09 architecture gate: the compile-time dependencies of the twelve modules form an acyclic
 * graph.
 *
 * <p>The gate imports the application packages with ArchUnit, derives the module-&gt;module edges
 * from the real bytecode dependencies, and feeds them to {@link PackageDependencyGraph}. An
 * introduced cycle anywhere in the module graph makes {@link #findCycles()} return a non-empty list
 * and fails {@code ./gradlew check}. The {@link
 * PackageDependencyGraphTest#createdCycleIsDetected()} test proves the detector itself reports a
 * created cycle.
 *
 * <p>This is a plain JUnit test (not an {@code @ArchTest}) because it has to aggregate the class
 * graph into a package graph before asserting; the imported {@code classes} are asserted non-empty
 * so a failed import cannot pass the gate with zero analysed packages.
 */
class NoCyclicModuleDependenciesTest {

  private static final String ROOT = "com.example.agentflow";

  @Test
  void moduleDependenciesAreAcyclic() {
    JavaClasses classes = new ClassFileImporter().importPackages(ROOT);

    List<String> analysedPackages = new ArrayList<>();
    Map<String, Set<String>> edges = new LinkedHashMap<>();
    for (JavaClass self : classes) {
      String from = self.getPackageName();
      analysedPackages.add(from);
      if (!from.startsWith(ROOT)) {
        continue;
      }
      for (Dependency dependency : self.getDirectDependenciesFromSelf()) {
        String target = dependency.getTargetClass().getPackageName();
        if (target.startsWith(ROOT) && !target.equals(from)) {
          edges.computeIfAbsent(from, key -> new HashSet<>()).add(target);
        }
      }
    }

    // Guard against a failed import passing the gate silently: if nothing was analysed there are
    // no edges and therefore no cycles, so the gate would pass without checking anything.
    assertThat(analysedPackages).isNotEmpty();

    List<List<String>> cycles = new PackageDependencyGraph(edges).findCycles();
    assertThat(cycles).isEmpty();
  }
}
