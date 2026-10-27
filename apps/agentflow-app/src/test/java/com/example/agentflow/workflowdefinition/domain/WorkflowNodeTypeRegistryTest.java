package com.example.agentflow.workflowdefinition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the closed {@link WorkflowNodeTypeRegistry} (S05-07): the allowed node set is a fixed,
 * anchored vocabulary with no public way to extend it, so a client or the untrusted planner cannot
 * dynamically register its own executable type. Any {@code type} outside the set (including the
 * dangerous {@code SCRIPT} payload that ADR-005 forbids) is rejected by {@link
 * #assertKnown(String)}.
 */
class WorkflowNodeTypeRegistryTest {

  private static final List<String> GLOSSARY_TYPES =
      List.of(
          "RETRIEVE_CONTEXT",
          "LLM_PLAN",
          "TOOL_CALL",
          "HUMAN_APPROVAL",
          "LLM_EVALUATE",
          "FINALIZE");

  @Test
  void contains_all_glossary_types() {
    for (String type : GLOSSARY_TYPES) {
      assertThat(WorkflowNodeTypeRegistry.contains(type))
          .as("the glossary node type %s must be allowed", type)
          .isTrue();
    }
  }

  @Test
  void assertKnown_accepts_every_glossary_type() {
    for (String type : GLOSSARY_TYPES) {
      assertThatNoException()
          .as("assertKnown must not throw for the allowed node type %s", type)
          .isThrownBy(() -> WorkflowNodeTypeRegistry.assertKnown(type));
    }
  }

  @Test
  void contains_rejects_unknown_types() {
    for (String type : List.of("SCRIPT", "CUSTOM_STEP", "LLM", "finalize", "")) {
      assertThat(WorkflowNodeTypeRegistry.contains(type))
          .as("the unknown node type '%s' must not be allowed", type)
          .isFalse();
    }
  }

  @Test
  void assertKnown_rejects_null_and_blank() {
    assertThatThrownBy(() -> WorkflowNodeTypeRegistry.assertKnown(null))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("unknown workflow node type");
    assertThatThrownBy(() -> WorkflowNodeTypeRegistry.assertKnown("  "))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("unknown workflow node type");
  }

  @Test
  void assertKnown_rejects_the_forbidden_executable_code_type() {
    // ADR-005 forbids a SCRIPT/JAVASCRIPT node that runs user-supplied code; the closed registry
    // rejects it, so it can never be registered as an executable type (S05-07, invariant 7).
    assertThatThrownBy(() -> WorkflowNodeTypeRegistry.assertKnown("SCRIPT"))
        .isInstanceOf(InvalidGraphException.class)
        .hasMessageContaining("SCRIPT");
  }

  @Test
  void known_types_match_the_glossary_verbatim() {
    // Anchored verbatim to the domain glossary "Workflow DSL / graph" (S05-07).
    assertThat(WorkflowNodeTypeRegistry.knownTypes())
        .containsExactlyInAnyOrder(
            "RETRIEVE_CONTEXT",
            "LLM_PLAN",
            "TOOL_CALL",
            "HUMAN_APPROVAL",
            "LLM_EVALUATE",
            "FINALIZE");
  }

  @Test
  void known_types_are_unmodifiable_so_registry_cannot_be_extended() {
    // The registry is closed: its public view cannot be mutated at runtime, so no caller can add a
    // new executable type through the accessor.
    assertThatThrownBy(() -> WorkflowNodeTypeRegistry.knownTypes().add("HACKED"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
