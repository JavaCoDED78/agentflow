package com.example.agentflow.knowledgebase.application.config;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.knowledgebase.application.RetrievalService;
import com.example.agentflow.knowledgebase.persistence.DocumentChunkRepository;
import com.example.agentflow.knowledgebase.persistence.KnowledgeBaseRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Retrieval wiring (S10-14).
 *
 * <p>Follows the {@link DocumentUploadConfiguration} / {@code DocumentEmbeddingConfiguration}
 * precedent: the {@link RetrievalService} bean is built here from the {@code agentflow.retrieval.*}
 * configuration group rather than being a Spring {@code @Component}, so the top-k bounds and the
 * relevance threshold stay configuration, never magic numbers, and a misconfigured value fails
 * validation at context startup.
 *
 * <p>Both persistence seams are optional so the S01-02 contract holds: the repositories are
 * registered only when a {@code DataSource} exists and the embedding port is opt-in ({@code
 * ADR-014}), so a default boot has neither — the bean method passes {@code null} through and {@link
 * RetrievalService#search} fails loudly at call time, exactly like {@code
 * EmbeddingGenerationService#embed} for {@code EmbeddingPort}.
 */
@Configuration(proxyBeanMethods = false)
public class RetrievalConfiguration {

  /**
   * The tenant/ACL-filtered vector retrieval service.
   *
   * @param properties the validated agent-flow configuration, including the {@code
   *     agentflow.retrieval.*} group
   * @param chunkRepositoryProvider the provider of the {@code document_chunks} read path; empty
   *     when no database is configured
   * @param knowledgeBaseRepositoryProvider the provider of the {@code knowledge_bases} read path;
   *     empty under the same condition
   * @param embeddingPortProvider the provider of the {@link EmbeddingPort} seam; empty when no
   *     embedding provider is configured
   * @return the service bound from {@code agentflow.retrieval.*}
   */
  @Bean
  public RetrievalService retrievalService(
      AgentFlowProperties properties,
      ObjectProvider<DocumentChunkRepository> chunkRepositoryProvider,
      ObjectProvider<KnowledgeBaseRepository> knowledgeBaseRepositoryProvider,
      ObjectProvider<EmbeddingPort> embeddingPortProvider) {
    AgentFlowProperties.Retrieval retrieval = properties.getRetrieval();
    return new RetrievalService(
        chunkRepositoryProvider.getIfAvailable(),
        knowledgeBaseRepositoryProvider.getIfAvailable(),
        embeddingPortProvider.getIfAvailable(),
        retrieval.getDefaultTopK(),
        retrieval.getMaxTopK(),
        retrieval.getThreshold());
  }
}
