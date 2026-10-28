package com.example.agentflow.workflowdefinition.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.workflowdefinition.domain.GraphValidationIssue;
import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import com.example.agentflow.workflowdefinition.domain.WorkflowEdge;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies the {@link WorkflowGraphParser} input gate (S05-04): a valid body parses into a typed
 * graph, while an unknown/missing {@code schemaVersion}, a wrong-typed node or edge field is
 * rejected with a controlled {@link InvalidGraphException} (never a raw Jackson exception) before a
 * domain object is produced. Edges are parsed into {@link WorkflowEdge}s and handed to the domain
 * {@link com.example.agentflow.workflowdefinition.domain.WorkflowGraphValidator} for the structural
 * gate (S05-05). DB-free: a fresh {@link ObjectMapper} (Jackson 3.x) backs the parser.
 */
class WorkflowGraphParserTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final WorkflowGraphParser parser = new WorkflowGraphParser(mapper);

  @Test
  void parse_valid_graph_returns_typed_model() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [
            {"id": "retrieve-1", "type": "RETRIEVE_CONTEXT", "config": {"query": "q", "top_k": 5}},
            {"id": "plan-1", "type": "LLM_PLAN", "config": {"prompt": "plan"}},
            {"id": "finalize-1", "type": "FINALIZE"}
          ]
        }
        """;

    WorkflowGraph graph = parser.parse(json);

    assertThat(graph.nodes()).hasSize(3);
    var n0 = graph.nodes().get(0);
    var n1 = graph.nodes().get(1);
    var n2 = graph.nodes().get(2);
    assertThat(n0.id()).isEqualTo("retrieve-1");
    assertThat(n0.type()).isEqualTo("RETRIEVE_CONTEXT");
    assertThat(n0.config()).containsEntry("query", "q").containsEntry("top_k", 5);
    assertThat(n1.id()).isEqualTo("plan-1");
    assertThat(n1.type()).isEqualTo("LLM_PLAN");
    assertThat(n2.id()).isEqualTo("finalize-1");
    assertThat(n2.config()).isEmpty();
  }

  @Test
  void parse_rejects_unknown_schema_version() {
    String json =
        """
        {
          "schemaVersion": "2.0.0",
          "nodes": [{"id": "finalize-1", "type": "FINALIZE"}],
          "edges": []
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("2.0.0")
        .hasMessageContaining("1.0.0");
  }

  @Test
  void parse_rejects_missing_schema_version() {
    String json =
        """
        {
          "nodes": [{"id": "finalize-1", "type": "FINALIZE"}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void parse_rejects_non_string_schema_version() {
    String json =
        """
        {
          "schemaVersion": 1.0,
          "nodes": [{"id": "finalize-1", "type": "FINALIZE"}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("schemaVersion")
        .hasMessageContaining("string");
  }

  @Test
  void parse_rejects_unknown_node_type() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": "script-1", "type": "SCRIPT", "config": {"code": "return 1;"}}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("SCRIPT");
  }

  @Test
  void parse_rejects_non_string_node_id() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": 123, "type": "FINALIZE"}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("id")
        .hasMessageContaining("string");
  }

  @Test
  void parse_rejects_missing_node_id() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"type": "FINALIZE"}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("id");
  }

  @Test
  void parse_rejects_non_string_node_type() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": "n1", "type": ["FINALIZE"]}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("type")
        .hasMessageContaining("string");
  }

  @Test
  void parse_rejects_non_object_node() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": ["n1"]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("object");
  }

  @Test
  void parse_rejects_config_not_an_object() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": "n1", "type": "FINALIZE", "config": "nope"}]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("config")
        .hasMessageContaining("object");
  }

  @Test
  void parse_rejects_nodes_not_an_array() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": {"id": "n1", "type": "FINALIZE"}
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("nodes")
        .hasMessageContaining("array");
  }

  @Test
  void parse_rejects_non_json_object() {
    assertThatThrownBy(() -> parser.parse("this is not json"))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("not valid JSON");
  }

  @Test
  void parse_rejects_json_array_as_root() {
    String json = "[{\"id\": \"n1\", \"type\": \"FINALIZE\"}]";

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("JSON object");
  }

  @Test
  void parse_valid_graph_parses_edges() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [
            { "id": "plan-1", "type": "LLM_PLAN" },
            { "id": "finalize-1", "type": "FINALIZE" }
          ],
          "edges": [{ "from": "plan-1", "to": "finalize-1" }]
        }
        """;

    WorkflowGraph graph = parser.parse(json);

    assertThat(graph.edges()).containsExactly(new WorkflowEdge("plan-1", "finalize-1"));
  }

  @Test
  void parse_missing_edges_defaults_to_empty() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{ "id": "finalize-1", "type": "FINALIZE" }]
        }
        """;

    assertThat(parser.parse(json).edges()).isEmpty();
  }

  @Test
  void parse_rejects_non_object_edge() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{ "id": "finalize-1", "type": "FINALIZE" }],
          "edges": ["plan-1"]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("edge")
        .hasMessageContaining("object");
  }

  @Test
  void parse_rejects_edge_missing_from() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [
            { "id": "plan-1", "type": "LLM_PLAN" },
            { "id": "finalize-1", "type": "FINALIZE" }
          ],
          "edges": [{ "to": "finalize-1" }]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("index 0")
        .hasMessageContaining("edge")
        .hasMessageContaining("from");
  }

  @Test
  void parse_rejects_non_string_edge_from() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [
            { "id": "plan-1", "type": "LLM_PLAN" },
            { "id": "finalize-1", "type": "FINALIZE" }
          ],
          "edges": [{ "from": 1, "to": "finalize-1" }]
        }
        """;

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("edge")
        .hasMessageContaining("from")
        .hasMessageContaining("string");
  }

  @Test
  void collect_issues_reports_input_field_problems_with_json_paths() {
    // id 123 is a non-string field: the parser input gate reports it before the domain gate runs.
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": 123, "type": "FINALIZE"}]
        }
        """;

    List<GraphValidationIssue> issues = parser.collectIssues(json);

    assertThat(issues).extracting(GraphValidationIssue::path).containsExactly("nodes[0].id");
    assertThat(issues)
        .extracting(GraphValidationIssue::message)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message).contains("index 0").contains("id").contains("string");
            });
  }

  @Test
  void collect_issues_reports_domain_problems_when_input_is_well_typed() {
    // SCRIPT is a well-typed string but an unknown node type (caught by the domain gate), and a
    // blank edge 'from' is a domain edge rule: both are collected with their JSON paths.
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [
            {"id": "ok-1", "type": "LLM_PLAN"},
            {"id": "bad-2", "type": "SCRIPT"}
          ],
          "edges": [{ "from": "", "to": "ok-1" }]
        }
        """;

    List<GraphValidationIssue> issues = parser.collectIssues(json);

    assertThat(issues)
        .extracting(GraphValidationIssue::path)
        .containsExactlyInAnyOrder("nodes[1]", "edges[0] field 'from'");
    assertThat(issues)
        .extracting(GraphValidationIssue::message)
        .anyMatch(m -> m.contains("SCRIPT"));
  }

  @Test
  void collect_issues_is_empty_for_a_valid_graph() {
    String json =
        """
        {
          "schemaVersion": "1.0.0",
          "nodes": [{"id": "finalize-1", "type": "FINALIZE"}],
          "edges": []
        }
        """;

    assertThat(parser.collectIssues(json)).isEmpty();
  }
}
