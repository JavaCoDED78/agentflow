package com.example.agentflow.audit.domain;

/**
 * The outcome of an audited action (glossary §10): whether the audited operation completed.
 *
 * <p>Recorded at the moment the action is performed. A later reconciliation (a worker crash after a
 * side effect, an ambiguous external outcome) re-checks and can mark a row {@link #FAILED} — audit
 * is append-only, so a re-check appends a corrected record rather than rewriting one.
 */
public enum AuditOutcome {
  SUCCEEDED,
  FAILED
}
