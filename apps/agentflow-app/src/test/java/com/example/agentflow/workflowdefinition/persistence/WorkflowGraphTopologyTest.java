package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the workflow-graph validation against the committed fixtures via the parser's public
 * {@link WorkflowGraphParser#parse(String)} entry point, which runs the structural gate (S05-05)
 * and the DAG gate (S05-06). Each {@code topology/invalid-*.json} example the validator owns is
 * rejected, and every {@code valid/*.json} example is accepted. Cyclic graphs (S05-06) are rejected
 * by the validator as a cycle — asserted separately below, since a cycle is a graph-level property
 * rather than a per-field JSON path — while the other topology fixtures each name a JSON path.
 *
 * <p>The fixtures directory is handed to the test as {@code workflow.graph.fixtures} by the {@code
 * test} task in {@code build.gradle.kts} (the same pattern the OpenAPI contract gate uses); the
 * test is skipped when run without it, e.g. from an IDE without the {@code -D} flag.
 */
class WorkflowGraphTopologyTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final WorkflowGraphParser parser = new WorkflowGraphParser(mapper);

  @Test
  void topology_fixtures_are_rejected_and_valid_fixtures_are_accepted() throws Exception {
    String fixturesRoot = System.getProperty("workflow.graph.fixtures");
    Assumptions.assumeTrue(
        fixturesRoot != null,
        "workflow-graph fixtures: run via ./gradlew check (sets -Dworkflow.graph.fixtures)");
    Path root = Path.of(fixturesRoot);
    assertThat(root).as("the workflow-graph fixtures root must exist").exists().isDirectory();

    // Every valid example parses (and therefore passes the structural gate) without error.
    List<Path> valid = validFixturePaths(root);
    assertThat(valid).as("there must be valid fixtures to exercise").isNotEmpty();
    for (Path example : valid) {
      parser.parse(Files.readString(example));
    }

    // Every S05-05 topology fixture (dangling / duplicate / self-referencing edge, duplicate node
    // id) is rejected by the validator, and the error names a JSON path (nodes[i] or edges[j]).
    for (Path fixture : topologyFixturePaths(root)) {
      String text = Files.readString(fixture);
      assertThatThrownBy(() -> parser.parse(text))
          .isInstanceOf(InvalidGraphException.class)
          .as("the fixture %s must be rejected with a JSON path", fixture.getFileName())
          .hasMessageMatching(".*nodes\\[\\d+\\]|.*edges\\[\\d+\\].*");
    }

    // Cyclic graphs (S05-06) are rejected by the validator as a cycle — a graph-level property, so
    // no single field's JSON path is named. The committed invalid-cyclic.json is asserted here.
    Path cyclic = root.resolve("topology").resolve("invalid-cyclic.json");
    if (Files.exists(cyclic)) {
      String text = Files.readString(cyclic);
      assertThatThrownBy(() -> parser.parse(text))
          .isInstanceOf(InvalidGraphException.class)
          .as("the cyclic fixture must be rejected as a cycle")
          .hasMessageMatching(".*cycle.*");
    }
  }

  private static List<Path> validFixturePaths(Path root) throws Exception {
    Path dir = root.resolve("valid");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    }
  }

  private static List<Path> topologyFixturePaths(Path root) throws Exception {
    Path dir = root.resolve("topology");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().equals("invalid-cyclic.json"))
          .sorted()
          .toList();
    }
  }
}
