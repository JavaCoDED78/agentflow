package com.example.agentflow.workflowexecution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.domain.Checkpoint;
import com.example.agentflow.workflowexecution.persistence.CheckpointSnapshot;
import com.example.agentflow.workflowexecution.persistence.WorkflowStepRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link CheckpointAdapter} (S07-07): redact-before-persist, the inline size bound,
 * and the reuse decision (schema-version match + an inline output). The repository is a mock so the
 * redaction and size-decision logic is asserted without a database; the persistence + tenant scope
 * is covered by {@link WorkflowStepRunRepositoryTest} and the integration suite.
 */
class CheckpointAdapterTest {

  private final WorkflowStepRunRepository repository =
      Mockito.mock(WorkflowStepRunRepository.class);
  private final AgentFlowProperties properties = Mockito.mock(AgentFlowProperties.class);

  @Test
  void save_persists_the_redacted_output_and_schema_version() {
    when(properties.getCheckpoint()).thenReturn(new AgentFlowProperties.Checkpoint());
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);
    UUID tenant = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    adapter.saveCheckpoint(tenant, runId, "calculator", 2, "v3", "42");

    verify(repository).saveCheckpoint(tenant, runId, "calculator", 2, Checkpoint.of("v3", "42"));
  }

  @Test
  void save_redacts_before_it_persists() {
    when(properties.getCheckpoint()).thenReturn(new AgentFlowProperties.Checkpoint());
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    adapter.saveCheckpoint(
        UUID.randomUUID(), UUID.randomUUID(), "tool", 1, "v1", "Authorization: Bearer ***");

    // The adapter redacts before persisting (invariant 8): the Bearer token becomes [REDACTED]
    // while the scheme label is kept, so the row carries the redacted form of the raw output.
    String expectedOutput = CheckpointRedactor.redact("Authorization: Bearer ***");
    verify(repository)
        .saveCheckpoint(
            Mockito.any(),
            Mockito.any(),
            Mockito.any(),
            Mockito.anyInt(),
            Mockito.eq(Checkpoint.of("v1", expectedOutput)));
  }

  @Test
  void save_is_a_no_op_for_null_output() {
    when(properties.getCheckpoint()).thenReturn(new AgentFlowProperties.Checkpoint());
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    adapter.saveCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "tool", 1, "v1", null);

    verify(repository, never())
        .saveCheckpoint(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
  }

  @Test
  void save_is_a_no_op_for_empty_output() {
    when(properties.getCheckpoint()).thenReturn(new AgentFlowProperties.Checkpoint());
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    adapter.saveCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "tool", 1, "v1", "");

    verify(repository, never())
        .saveCheckpoint(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
  }

  @Test
  void save_is_a_no_op_when_redacted_output_exceeds_the_inline_bound() {
    // max inline = 4 characters; a 6-character redacted output does not fit and is externalized
    // (S15-06), so it is not persisted inline and is treated as non-reusable.
    AgentFlowProperties.Checkpoint checkpoint = Mockito.mock(AgentFlowProperties.Checkpoint.class);
    when(properties.getCheckpoint()).thenReturn(checkpoint);
    when(checkpoint.getMaxInlineBytes()).thenReturn(4);
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    adapter.saveCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "tool", 1, "v1", "abcdef");

    verify(repository, never())
        .saveCheckpoint(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.any());
  }

  @Test
  void can_reuse_hits_only_when_the_schema_version_matches_an_inline_output() {
    when(repository.findCheckpoint(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Optional.of(new CheckpointSnapshot("v3", "42")));
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    assertThat(adapter.canReuseCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "calculator", "v3"))
        .isTrue();
  }

  @Test
  void can_reuse_misses_on_a_schema_version_mismatch() {
    // The step's output contract changed (v4 vs stored v3): reuse must re-execute, not return the
    // stale value (invariant 13 — the schema version is the "is the output still valid?" key).
    when(repository.findCheckpoint(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Optional.of(new CheckpointSnapshot("v3", "42")));
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    assertThat(adapter.canReuseCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "calculator", "v4"))
        .isFalse();
  }

  @Test
  void can_reuse_misses_when_no_checkpoint_exists() {
    when(repository.findCheckpoint(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Optional.empty());
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    assertThat(adapter.canReuseCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "calculator", "v3"))
        .isFalse();
  }

  @Test
  void can_reuse_misses_when_the_stored_output_is_not_inline() {
    // A checkpoint with only a schema version (output externalized to object storage) proves the
    // step ran but carries no inline value to reuse: recovery re-executes.
    when(repository.findCheckpoint(Mockito.any(), Mockito.any(), Mockito.any()))
        .thenReturn(Optional.of(new CheckpointSnapshot("v3", null)));
    CheckpointAdapter adapter = new CheckpointAdapter(repository, properties);

    assertThat(adapter.canReuseCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "calculator", "v3"))
        .isFalse();
  }
}
