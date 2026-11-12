package com.example.agentflow.ingestion.application.config;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding-generation wiring (S10-12).
 *
 * <p>Follows the {@link DocumentChunkingConfiguration} precedent: the {@link
 * EmbeddingGenerationService} bean is built here from the {@code agentflow.ingestion.embedding.*}
 * configuration group rather than being a Spring {@code @Component}, so the batch size stays
 * configuration, never a magic number, and a misconfigured value fails validation at context
 * startup.
 *
 * <p>The service's provider seam is the {@code ai} module's {@link EmbeddingPort}, and the port
 * itself is opt-in — a provider must be configured ({@code ADR-014}), so a default boot has no
 * {@link EmbeddingPort} bean, exactly like {@code ChatModelPort}. The bean method therefore takes
 * an {@link ObjectProvider} and passes {@code null} when no port exists: the app boots without a
 * provider or an AI key, and {@link EmbeddingGenerationService#embed} fails loudly at call time —
 * the same opt-in shape {@code PlannerService} documents for {@code ChatModelPort}.
 */
@Configuration(proxyBeanMethods = false)
public class DocumentEmbeddingConfiguration {

  /**
   * The embedding generation service the ingestion worker runs on a chunk set.
   *
   * @param properties the validated agent-flow configuration, including the embedding batching
   *     parameters
   * @param embeddingPortProvider the provider of the {@link EmbeddingPort} seam; empty when no
   *     embedding provider is configured, in which case the service holds no port and fails loudly
   *     at {@link EmbeddingGenerationService#embed} call time
   * @return the service bound from {@code agentflow.ingestion.embedding.*}
   */
  @Bean
  public EmbeddingGenerationService embeddingGenerationService(
      AgentFlowProperties properties, ObjectProvider<EmbeddingPort> embeddingPortProvider) {
    return new EmbeddingGenerationService(
        embeddingPortProvider.getIfAvailable(),
        properties.getIngestion().getEmbedding().getBatchSize());
  }
}
