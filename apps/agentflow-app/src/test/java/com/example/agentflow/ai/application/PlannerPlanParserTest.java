package com.example.agentflow.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.domain.plan.PlannerPlan;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * DB-free unit tests for {@link PlannerPlanParser} (S09-06): the model's completion text becomes a
 * validated {@link PlannerPlan} through the same Jackson 3 ({@code tools.jackson}) round-trip the
 * {@code workflowdefinition} codec uses, and anything that is not a valid plan is one clear signal,
 * {@link PlannerPlanParseException}.
 */
class PlannerPlanParserTest {

  private final PlannerPlanParser parser = new PlannerPlanParser(new ObjectMapper());

  @Test
  void parses_a_valid_plan() {
    String json =
        "{\"steps\":["
            + "{\"id\":\"s1\",\"type\":\"TOOL_CALL\",\"toolId\":\"calc\",\"dependsOn\":[],\"arguments\":{\"x\":1}}, "
            + "{\"id\":\"s2\",\"type\":\"FINALIZE\",\"dependsOn\":[\"s1\"],\"arguments\":{}}"
            + "]}";

    PlannerPlan plan = parser.parse(json);

    assertThat(plan.steps()).hasSize(2);
    assertThat(plan.steps().get(0).toolId()).isEqualTo("calc");
    assertThat(plan.steps().get(0).arguments()).containsEntry("x", 1);
    assertThat(plan.steps().get(1).dependsOn()).containsExactly("s1");
  }

  @Test
  void omits_empty_list_for_missing_dependson_and_arguments() {
    // The Planner (and the schema) omit the optional components: the deserializer defaults them to
    // empty, unmodifiable containers rather than failing the read (S09-05 / S09-06).
    String json =
        "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\"},{\"id\":\"s2\",\"type\":\"TOOL_CALL\"}]}";

    PlannerPlan plan = parser.parse(json);

    assertThat(plan.steps().get(0).dependsOn()).isEmpty();
    assertThat(plan.steps().get(0).arguments()).isEmpty();
    assertThat(plan.steps().get(1).toolId()).isNull();
    assertThat(plan.steps().get(1).dependsOn()).isEmpty();
  }

  @Test
  void null_steps_defaults_to_empty_plan() {
    PlannerPlan plan = parser.parse("{\"steps\":null}");

    assertThat(plan.steps()).isEmpty();
  }

  @Test
  void malformed_json_throws_a_planner_plan_parse_exception() {
    assertThatThrownBy(() -> parser.parse("{not valid json"))
        .isInstanceOf(PlannerPlanParseException.class)
        .hasMessageContaining("not valid plan JSON");
  }

  @Test
  void a_plan_with_a_duplicated_step_id_throws_a_planner_plan_parse_exception() {
    String json =
        "{\"steps\":[{\"id\":\"dup\",\"type\":\"TOOL_CALL\"},{\"id\":\"dup\",\"type\":\"FINALIZE\"}]}";

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(PlannerPlanParseException.class)
        .hasMessageContaining("duplicate step id")
        // The structural violation classifies as SCHEMA_MISMATCH, not MALFORMED_JSON: Jackson 3
        // wraps the record canonical-constructor failure in a JacksonException, and the parser
        // unwraps the cause chain to keep the documented distinction.
        .extracting(e -> ((PlannerPlanParseException) e).classification())
        .isEqualTo(PlannerFailure.SCHEMA_MISMATCH);
  }

  @Test
  void a_dangling_dependency_classifies_as_schema_mismatch_too() {
    String json = "{\"steps\":[{\"id\":\"s1\",\"type\":\"FINALIZE\",\"dependsOn\":[\"ghost\"]}]}";

    assertThatThrownBy(() -> parser.parse(json))
        .isInstanceOf(PlannerPlanParseException.class)
        .hasMessageContaining("unknown step")
        .extracting(e -> ((PlannerPlanParseException) e).classification())
        .isEqualTo(PlannerFailure.SCHEMA_MISMATCH);
  }

  @Test
  void a_null_payload_is_a_contract_error_not_a_parse_failure() {
    assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
