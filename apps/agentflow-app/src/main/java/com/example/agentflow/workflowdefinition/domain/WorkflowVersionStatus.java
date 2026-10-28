package com.example.agentflow.workflowdefinition.domain;

/**
 * The lifecycle status of a single workflow definition version (glossary §10).
 *
 * <p>A version starts life as a {@link #DRAFT} while its graph is edited. On activation the version
 * is {@link #ACTIVATED}, its {@code version} number and {@code checksum} are pinned, and it becomes
 * immutable: it can never change, be re-activated, or be deleted. The status is enforced by the
 * {@link com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository} (a
 * conditional update that only transitions {@code DRAFT → ACTIVATED}) and never mutated through any
 * other path.
 */
public final class WorkflowVersionStatus {

  /** The version exists but its graph has not yet been activated. */
  public static final String DRAFT = "DRAFT";

  /** The version is activated: its graph, version number and checksum are immutable. */
  public static final String ACTIVATED = "ACTIVATED";

  private WorkflowVersionStatus() {}

  /**
   * @param status a {@link WorkflowVersionStatus} value
   * @return {@code true} if the version is {@link #ACTIVATED} (locked), {@code false} otherwise
   */
  public static boolean isActivated(String status) {
    return ACTIVATED.equals(status);
  }
}
