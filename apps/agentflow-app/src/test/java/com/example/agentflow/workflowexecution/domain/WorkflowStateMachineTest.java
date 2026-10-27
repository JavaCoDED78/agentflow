package com.example.agentflow.workflowexecution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure run and step state machines (S05-10).
 *
 * <p>They verify that {@link WorkflowRunState} and {@link WorkflowStepState} model exactly the
 * transition tables documented in {@code run-state-machine.md} (S05-01) and {@code
 * step-state-machine.md} (S05-02): every allowed edge passes {@code canTransitionTo}, every other
 * edge is forbidden (the enum matches the table for <em>all</em> ordered pairs, not a sample), the
 * terminal states are absorbing, {@code null} states are rejected, and {@code assertAllowed} throws
 * {@link InvalidStateException} on a forbidden transition while passing on an allowed one.
 */
class WorkflowStateMachineTest {

  // ---------------------------------------------------------------------------
  // Run state machine
  // ---------------------------------------------------------------------------

  @Test
  void run_state_machine_matches_the_transition_table() {
    Set<String> allowed =
        allowedPairs(
            "QUEUED -> RUNNING",
            "QUEUED -> CANCEL_REQUESTED",
            "RUNNING -> WAITING_FOR_APPROVAL",
            "RUNNING -> RETRY_WAIT",
            "RUNNING -> SUCCEEDED",
            "RUNNING -> FAILED",
            "RUNNING -> CANCEL_REQUESTED",
            "WAITING_FOR_APPROVAL -> RUNNING",
            "WAITING_FOR_APPROVAL -> FAILED",
            "WAITING_FOR_APPROVAL -> CANCEL_REQUESTED",
            "RETRY_WAIT -> RUNNING",
            "RETRY_WAIT -> CANCEL_REQUESTED",
            "CANCEL_REQUESTED -> CANCELLED");

    int checked = 0;
    for (WorkflowRunState from : WorkflowRunState.values()) {
      for (WorkflowRunState to : WorkflowRunState.values()) {
        boolean expected = allowed.contains(from.name() + " -> " + to.name());
        assertThat(from.canTransitionTo(to))
            .as("run transition %s -> %s", from, to)
            .isEqualTo(expected);
        checked++;
      }
    }
    assertThat(checked)
        .as("every ordered run-state pair is checked against the table")
        .isEqualTo(WorkflowRunState.values().length * WorkflowRunState.values().length);
  }

  @Test
  void run_terminal_states_allow_no_transition() {
    for (WorkflowRunState terminal : runTerminalStates()) {
      assertThat(terminal.isTerminal()).as("%s is terminal", terminal).isTrue();
      for (WorkflowRunState next : WorkflowRunState.values()) {
        assertThat(terminal.canTransitionTo(next))
            .as("terminal run state %s must not transition to %s", terminal, next)
            .isFalse();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Step state machine
  // ---------------------------------------------------------------------------

  @Test
  void step_state_machine_matches_the_transition_table() {
    Set<String> allowed =
        allowedPairs(
            "PENDING -> READY",
            "PENDING -> SKIPPED",
            "PENDING -> CANCELLED",
            "READY -> RUNNING",
            "READY -> SKIPPED",
            "READY -> CANCELLED",
            "RUNNING -> SUCCEEDED",
            "RUNNING -> RETRY_WAIT",
            "RUNNING -> WAITING_FOR_APPROVAL",
            "RUNNING -> FAILED",
            "RUNNING -> CANCELLED",
            "WAITING_FOR_APPROVAL -> RUNNING",
            "WAITING_FOR_APPROVAL -> FAILED",
            "WAITING_FOR_APPROVAL -> CANCELLED",
            "RETRY_WAIT -> RUNNING");

    int checked = 0;
    for (WorkflowStepState from : WorkflowStepState.values()) {
      for (WorkflowStepState to : WorkflowStepState.values()) {
        boolean expected = allowed.contains(from.name() + " -> " + to.name());
        assertThat(from.canTransitionTo(to))
            .as("step transition %s -> %s", from, to)
            .isEqualTo(expected);
        checked++;
      }
    }
    assertThat(checked)
        .as("every ordered step-state pair is checked against the table")
        .isEqualTo(WorkflowStepState.values().length * WorkflowStepState.values().length);
  }

  @Test
  void step_terminal_states_allow_no_transition() {
    for (WorkflowStepState terminal : stepTerminalStates()) {
      assertThat(terminal.isTerminal()).as("%s is terminal", terminal).isTrue();
      for (WorkflowStepState next : WorkflowStepState.values()) {
        assertThat(terminal.canTransitionTo(next))
            .as("terminal step state %s must not transition to %s", terminal, next)
            .isFalse();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // assertAllowed: the throwing entry point (forbidden -> domain error)
  // ---------------------------------------------------------------------------

  @Test
  void assertAllowed_throws_a_domain_error_on_forbidden_transition() {
    assertThatThrownBy(
            () ->
                WorkflowRunState.assertAllowed(
                    WorkflowRunState.SUCCEEDED, WorkflowRunState.RUNNING))
        .isInstanceOf(InvalidStateException.class)
        .hasMessageContaining("SUCCEEDED")
        .hasMessageContaining("RUNNING");
    assertThatThrownBy(
            () ->
                WorkflowStepState.assertAllowed(
                    WorkflowStepState.SUCCEEDED, WorkflowStepState.RUNNING))
        .isInstanceOf(InvalidStateException.class)
        .hasMessageContaining("SUCCEEDED")
        .hasMessageContaining("RUNNING");
  }

  @Test
  void assertAllowed_rejects_null_states() {
    assertThatThrownBy(() -> WorkflowRunState.assertAllowed(null, WorkflowRunState.RUNNING))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> WorkflowRunState.assertAllowed(WorkflowRunState.RUNNING, null))
        .isInstanceOf(InvalidStateException.class);
    assertThatThrownBy(() -> WorkflowStepState.assertAllowed(null, WorkflowStepState.READY))
        .isInstanceOf(InvalidStateException.class);
  }

  @Test
  void assertAllowed_passes_on_an_allowed_transition() {
    WorkflowRunState.assertAllowed(WorkflowRunState.RUNNING, WorkflowRunState.SUCCEEDED);
    WorkflowStepState.assertAllowed(WorkflowStepState.READY, WorkflowStepState.RUNNING);
  }

  // ---------------------------------------------------------------------------
  // The enums hold exactly the documented state sets (no drift from the docs)
  // ---------------------------------------------------------------------------

  @Test
  void run_state_holds_exactly_the_documented_states() {
    assertThat(stateNames(WorkflowRunState.values()))
        .containsExactlyInAnyOrder(
            "QUEUED",
            "RUNNING",
            "WAITING_FOR_APPROVAL",
            "RETRY_WAIT",
            "SUCCEEDED",
            "FAILED",
            "CANCEL_REQUESTED",
            "CANCELLED");
  }

  @Test
  void step_state_holds_exactly_the_documented_states() {
    assertThat(stateNames(WorkflowStepState.values()))
        .containsExactlyInAnyOrder(
            "PENDING",
            "READY",
            "RUNNING",
            "WAITING_FOR_APPROVAL",
            "RETRY_WAIT",
            "SUCCEEDED",
            "FAILED",
            "SKIPPED",
            "CANCELLED");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Set<String> allowedPairs(String... edges) {
    Set<String> set = new LinkedHashSet<>();
    for (String edge : edges) {
      set.add(edge.trim());
    }
    return set;
  }

  private static Set<WorkflowRunState> runTerminalStates() {
    Set<WorkflowRunState> terminal = new LinkedHashSet<>();
    for (WorkflowRunState state : WorkflowRunState.values()) {
      if (state.isTerminal()) {
        terminal.add(state);
      }
    }
    return terminal;
  }

  private static Set<WorkflowStepState> stepTerminalStates() {
    Set<WorkflowStepState> terminal = new LinkedHashSet<>();
    for (WorkflowStepState state : WorkflowStepState.values()) {
      if (state.isTerminal()) {
        terminal.add(state);
      }
    }
    return terminal;
  }

  private static Set<String> stateNames(Object[] states) {
    Set<String> names = new LinkedHashSet<>();
    for (Object state : states) {
      names.add(state.toString());
    }
    return names;
  }
}
