package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.workflowexecution.domain.StepExecutionContext;
import com.example.agentflow.workflowexecution.domain.StepHandler;
import com.example.agentflow.workflowexecution.domain.StepResult;
import com.example.agentflow.workflowexecution.domain.WorkflowStepState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DB-free tests for the MVP {@link StepHandler} (S07-08). The handler is deterministic by design:
 * its output is derived from the node's {@code config} with no external side effect, so the step it
 * executes is repeatable and safe to checkpoint. The {@code config} is untrusted content (invariant
 * 7): a {@code result} value is echoed, and a node without one gets a stable default.
 */
class DeterministicStepHandlerTest {

  private final StepHandler handler = new DeterministicStepHandler();

  @Test
  void supports_only_the_noop_node_type() {
    assertThat(handler.supports(DeterministicStepHandler.NODE_TYPE)).isTrue();
    assertThat(handler.supports("TOOL_CALL")).isFalse();
    assertThat(handler.supports("LLM_PLAN")).isFalse();
    assertThat(handler.supports(null)).isFalse();
  }

  @Test
  void echoes_the_configured_result() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("result", "computed-42");
    StepExecutionContext context = context("step-1", config);

    StepResult result = handler.handle(context);

    assertThat(result.outcome()).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(result.output()).isEqualTo("computed-42");
  }

  @Test
  void uses_a_stable_default_result_when_config_is_absent() {
    StepExecutionContext context = context("step-2", null);

    StepResult result = handler.handle(context);

    assertThat(result.outcome()).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(result.output()).isEqualTo("done:step-2");
  }

  @Test
  void falls_back_to_the_default_result_when_config_result_is_blank() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("result", "");

    StepResult result = handler.handle(context("step-3", config));

    assertThat(result.outcome()).isEqualTo(WorkflowStepState.SUCCEEDED);
    assertThat(result.output()).isEqualTo("done:step-3");
  }

  private static StepExecutionContext context(String stepKey, Map<String, Object> config) {
    return new StepExecutionContext(
        UUID.randomUUID(),
        UUID.randomUUID(),
        stepKey,
        DeterministicStepHandler.NODE_TYPE,
        config,
        new LinkedHashMap<>());
  }
}
