package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import com.example.agentflow.workflowdefinition.persistence.WorkflowGraphCodec;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersion;
import java.util.UUID;

/**
 * Public API of a {@code workflow_definition_versions} row (S05-12).
 *
 * <p>The immutable view of a version: the version id, the parent definition id, the pinned version
 * number, the serialized graph ({@code graphJson}), the checksum pinned on creation, and the {@link
 * WorkflowVersionStatus}. It is a distinct type from the persistence entity — the graph is exposed
 * as JSON text here, exactly as it was stored, never as a mutable domain object.
 *
 * @param id the version id
 * @param workflowId the parent definition id this version belongs to
 * @param version the pinned, unique-per-workflow version number
 * @param graphJson the serialized graph, exactly as stored in {@code graph_json}
 * @param checksum the checksum pinned on creation (and re-checked on activation)
 * @param status the lifecycle status ({@link WorkflowVersionStatus#DRAFT} or {@link
 *     WorkflowVersionStatus#ACTIVATED}); an activated version is immutable
 */
public record WorkflowVersionDto(
    UUID id, UUID workflowId, Integer version, String graphJson, String checksum, String status) {

  /**
   * Maps a persisted {@link WorkflowVersion} to its public view. The graph is serialized back to
   * JSON through the {@link WorkflowGraphCodec} so the DTO carries {@code graphJson} exactly as it
   * was stored.
   *
   * @param version the persisted version
   * @param codec the graph codec used to serialize the graph for the DTO
   * @return the public view of the version
   */
  public static WorkflowVersionDto from(WorkflowVersion version, WorkflowGraphCodec codec) {
    return new WorkflowVersionDto(
        version.id(),
        version.workflowId(),
        version.version(),
        codec.serialize(version.graph()),
        version.checksum(),
        version.status());
  }
}
