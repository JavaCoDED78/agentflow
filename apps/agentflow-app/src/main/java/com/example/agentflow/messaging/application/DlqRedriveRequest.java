package com.example.agentflow.messaging.application;

/**
 * The controlled-redrive request body (S12-12): the operator-supplied {@code reason} for
 * re-publishing the failed envelope.
 *
 * <p>The record carries no validation of its own: the {@link DlqRedriveService} treats {@code
 * reason} as mandatory and reports a {@code 400} when it is blank or absent, consistent with the
 * manual {@code 400} the {@code messaging} inspection service performs (never a bean-validation or
 * compact-constructor check that would surface as a {@code 500} during request-body
 * deserialization). The reason is recorded, redacted, in the audit trail as the explanation for the
 * re-delivery.
 */
public record DlqRedriveRequest(String reason) {}
