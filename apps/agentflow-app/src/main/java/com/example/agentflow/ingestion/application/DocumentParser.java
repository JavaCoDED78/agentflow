package com.example.agentflow.ingestion.application;

import com.example.agentflow.ingestion.domain.ExtractionResult;

/**
 * Outbound port for document text/metadata extraction (S10-07).
 *
 * <p>The ingestion worker depends on this port, never on a concrete parser, so the extraction
 * engine (Tika / PDFBox today) can be replaced without touching the worker or the domain. A payload
 * reaching a parser has already passed upload validation ({@code
 * knowledgebase.domain.DocumentContentType}), so the parser only has to read it, not re-check it.
 *
 * <p>The contract is total and side-effect free: {@link #parse(String, String, byte[])} returns an
 * {@link ExtractionResult} for <b>every</b> input (success, empty or a classified failure) and
 * never throws for a parseable-but-bad document. Callers read {@link ExtractionResult#status()} and
 * {@link ExtractionResult#error()} rather than catching faults.
 */
public interface DocumentParser {

  /**
   * Extract text and metadata from a validated payload.
   *
   * @param originalName the client filename (used only as a content-type detection hint, never
   *     trusted for authorisation)
   * @param contentType the canonical content type (for example {@code application/pdf}); may be
   *     {@code null} or blank, in which case the type is detected from the payload's leading bytes
   * @param payload the raw document bytes (never {@code null}; an empty array yields {@link
   *     ExtractionResult#empty()})
   * @return the extraction result; the caller owns neither the text nor the metadata beyond the
   *     returned value
   */
  ExtractionResult parse(String originalName, String contentType, byte[] payload);
}
