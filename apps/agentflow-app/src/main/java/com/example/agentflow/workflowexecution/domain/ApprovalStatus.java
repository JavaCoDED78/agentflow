package com.example.agentflow.workflowexecution.domain;

/**
 * The bounded approval lifecycle vocabulary (S11-02 glossary §9), the free-standing Java
 * implementation of the {@code approvals_status_check} bound in storage ({@code
 * V3_3_22__create_approvals.sql}).
 *
 * <p>A request is created {@link #PENDING} at a {@code HUMAN_APPROVAL} pause (S11-03); a decision
 * (S11-04 approve, S11-05 reject) or an expiry (S11-05) moves it to a terminal status. The enum
 * mirrors the storage CHECK exactly — {@code valueOf(status)} is safe on every row the table
 * accepts — and the {@code APPROVAL} transition vocabulary stays closed: a status outside this set
 * is a schema change, not an input.
 *
 * <p>This type is pure: no Spring, no persistence, no I/O, so it lives in the domain layer and is
 * unit-testable without a database.
 */
public enum ApprovalStatus {

  /**
   * The request awaits a human decision; at most one PENDING per (run, step) (the partial unique
   * index).
   */
  PENDING,

  /**
   * A human approved the gated side effect (S11-04); the decision is bound to the payload
   * fingerprint.
   */
  APPROVED,

  /** A human rejected the gated side effect (S11-05). */
  REJECTED,

  /** The request outlived its {@code expiry} before a decision (S11-05 owns the lifecycle). */
  EXPIRED,

  /** The request was cancelled without a decision (S11-05). */
  CANCELLED
}
