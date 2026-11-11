package com.example.agentflow.ingestion.application.config;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.application.parser.TikaDocumentParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document-extraction wiring (S10-07).
 *
 * <p>Follows the {@code knowledgebase.application.config.DocumentUploadConfiguration} precedent:
 * the {@link DocumentParser} bean is built here from the {@code agentflow.ingestion.*}
 * configuration group rather than being a Spring {@code @Component}, so the extraction limits stay
 * configuration, never magic numbers, and a misconfigured limit fails construction (via the {@link
 * TikaDocumentParser} constructor) at context startup. The parser is pure &mdash; no {@code
 * DataSource} / Kafka dependency &mdash; so it is safe in the DB-less skeleton and every profile,
 * including the {@code api} role that never starts the ingestion worker.
 *
 * <p>This class lives in the {@code ingestion} module (not the global {@code config} package) by
 * design: homing it in {@code config} would make {@code config} depend on {@code
 * ingestion.application}, which together with the existing {@code messaging -> config} ({@link
 * AgentFlowProperties}) edge could form a compile-time cycle across the module graph. Homing it in
 * {@code ingestion} keeps the module graph acyclic.
 */
@Configuration
public class DocumentExtractionConfiguration {

  /**
   * The document-extraction parser, bound from {@code agentflow.ingestion.*}.
   *
   * @param properties the validated agent-flow configuration, including the extraction limits
   * @return the Tika/PDFBox-backed parser the ingestion worker drives
   */
  @Bean
  public DocumentParser documentParser(AgentFlowProperties properties) {
    AgentFlowProperties.Ingestion ingestion = properties.getIngestion();
    return new TikaDocumentParser(ingestion.getMaxPages(), ingestion.getMaxChars());
  }
}
