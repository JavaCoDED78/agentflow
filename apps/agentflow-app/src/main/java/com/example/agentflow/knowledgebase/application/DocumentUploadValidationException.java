package com.example.agentflow.knowledgebase.application;

/**
 * Thrown when an upload fails one of the {@code S10-03} accept/bound checks (oversized, unsupported
 * content type, bad filename, tampered magic bytes, undecodable/over-long media). A thrown instance
 * is the contract: the validator rejects before any object is written and before any metadata row
 * is committed, so no dangling object or {@code documents} row is ever left behind. The HTTP layer
 * translates it to a {@code 400} via {@code common.ErrorClass} / {@code ProblemDetails}.
 */
public class DocumentUploadValidationException extends RuntimeException {

  /**
   * @param message a human-readable reason (English) for the rejection
   */
  public DocumentUploadValidationException(String message) {
    super(message);
  }
}
