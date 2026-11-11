package com.example.agentflow.knowledgebase.application.config;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.knowledgebase.application.DocumentUploadValidator;
import com.example.agentflow.knowledgebase.domain.DocumentUploadLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document-upload validation wiring (S10-03).
 *
 * <p>Follows the {@link com.example.agentflow.config.ToolExecutionConfiguration} precedent: the
 * validator bean is built here from the {@code agentflow.upload.*} configuration group rather than
 * being a Spring {@code @Component}, so the upload limits stay configuration, never magic numbers,
 * and a misconfigured limit fails boot (via the {@link DocumentUploadLimits} constructor) before
 * any upload is processed. The validator is pure — no {@code DataSource}/Kafka dependency — so it
 * is safe in the DB-less skeleton and every profile, including {@code api}. The {@code
 * knowledgebase} module owns the validator's logic; this class only performs the value binding from
 * {@link AgentFlowProperties.Upload}.
 *
 * <p>This class lives in the {@code knowledgebase} module (not the global {@code config} package)
 * by design: keeping it in {@code config} would make {@code config} depend on {@code
 * knowledgebase.application}, and together with the {@code knowledgebase → messaging} outbox edge
 * ({@code S10-06}) and the {@code messaging → config} ({@link AgentFlowProperties}) dependency that
 * already exists, that would form a compile-time cycle across the module graph ({@code config →
 * knowledgebase → messaging → config}). Homing it in {@code knowledgebase} removes that upward edge
 * and keeps the module graph acyclic.
 */
@Configuration
public class DocumentUploadConfiguration {

  /**
   * The document-upload validator ({@code S10-03}), bound from {@code agentflow.upload.*}.
   *
   * @param properties the validated agent-flow configuration, including the upload limits
   * @return the immutable upload accept/bound validator the upload API drives
   */
  @Bean
  public DocumentUploadValidator documentUploadValidator(AgentFlowProperties properties) {
    AgentFlowProperties.Upload upload = properties.getUpload();
    return new DocumentUploadValidator(
        new DocumentUploadLimits(
            upload.getMaxBytes(),
            upload.getMaxFileNameLength(),
            upload.getMaxMediaDurationSeconds()));
  }
}
