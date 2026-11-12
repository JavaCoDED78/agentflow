package com.example.agentflow.ingestion.application.config;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.domain.RedeliveryPolicy;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.DocumentPayloadStore;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.messaging.application.OutboxService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Indexing-pipeline wiring (S10-13).
 *
 * <p>Follows the {@link DocumentEmbeddingConfiguration} precedent: the {@link IngestionService}
 * bean is built here from the {@code agentflow.ingestion.indexing.*} configuration group rather
 * than being a Spring {@code @Component}, so the redelivery policy stays configuration, never a
 * magic default, and an unknown policy name fails the wiring at startup (via {@link
 * RedeliveryPolicy#fromConfig}). The {@link DocumentPayloadStore} is resolved through an {@link
 * ObjectProvider}: the object-storage client is opt-in ({@code ADR-014}), so the service is
 * constructed without it when no Garage client is wired and {@code index(...)} fails loudly at call
 * time — the application boots without object storage exactly as before (S01-02, the {@code
 * PlannerService} idiom).
 *
 * <p>This class lives in the {@code ingestion} module (not the global {@code config} package) by
 * the same design as {@link DocumentExtractionConfiguration}: homing it in {@code config} would
 * make {@code config} depend on {@code ingestion.application} and the {@code knowledgebase}
 * application boundary, risking a compile-time cycle across the module graph.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionConfiguration {

  /**
   * The indexing use case the ingestion worker drives on every {@code ingestion-requested} event.
   *
   * @param properties the validated agent-flow configuration, including the indexing policy
   * @param documentQueryService the tenant-scoped document read path
   * @param documentTransitionService the single writer of {@code documents.status}
   * @param chunkIndexingService the idempotent chunk write path
   * @param payloadStore the object-storage read port, or {@code null} when no Garage client is
   *     wired (opt-in)
   * @param documentParser the extraction port
   * @param chunker the deterministic chunker
   * @param embeddingGenerationService the batching embed step (S10-12)
   * @param outboxService the transactional-outbox appender for the {@code document.indexed} event
   * @return the indexing service bound from {@code agentflow.ingestion.indexing.*}
   */
  @Bean
  public IngestionService ingestionService(
      AgentFlowProperties properties,
      DocumentQueryService documentQueryService,
      DocumentTransitionService documentTransitionService,
      ChunkIndexingService chunkIndexingService,
      ObjectProvider<DocumentPayloadStore> payloadStore,
      DocumentParser documentParser,
      Chunker chunker,
      EmbeddingGenerationService embeddingGenerationService,
      OutboxService outboxService) {
    RedeliveryPolicy redeliveryPolicy =
        RedeliveryPolicy.fromConfig(properties.getIngestion().getIndexing().getRedeliveryPolicy());
    return new IngestionService(
        documentQueryService,
        documentTransitionService,
        chunkIndexingService,
        payloadStore.getIfAvailable(),
        documentParser,
        chunker,
        embeddingGenerationService,
        outboxService,
        redeliveryPolicy);
  }
}
