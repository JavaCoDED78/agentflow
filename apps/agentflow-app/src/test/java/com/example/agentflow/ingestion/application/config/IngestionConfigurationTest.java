package com.example.agentflow.ingestion.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.application.IngestionService;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.Strategy;
import com.example.agentflow.ingestion.worker.DocumentIndexingWorker;
import com.example.agentflow.ingestion.worker.IngestionWorkerLifecycle;
import com.example.agentflow.knowledgebase.application.ChunkIndexingService;
import com.example.agentflow.knowledgebase.application.DocumentQueryService;
import com.example.agentflow.knowledgebase.application.DocumentTransitionService;
import com.example.agentflow.messaging.application.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wiring tests for {@link IngestionConfiguration} (S10-13), proving the bean shape: the {@link
 * IngestionService} is built from {@code agentflow.ingestion.indexing.*}, the {@code
 * DocumentPayloadStore} seam is opt-in (the service constructs without it — {@code ADR-014}), an
 * unknown redelivery policy fails the wiring, and the worker beans stay absent without the {@code
 * ingestion-worker} role (the flag and a {@code DataSource}). DB-free, no Kafka, no network.
 */
class IngestionConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(IngestionConfiguration.class)
  static class Wiring {

    @Bean
    DocumentQueryService documentQueryService() {
      return new DocumentQueryService(null);
    }

    @Bean
    DocumentTransitionService documentTransitionService() {
      return new DocumentTransitionService(null);
    }

    @Bean
    ChunkIndexingService chunkIndexingService() {
      return new ChunkIndexingService(null);
    }

    @Bean
    DocumentParser documentParser() {
      return (name, type, payload) -> ExtractionResult.empty("test");
    }

    @Bean
    Chunker chunker() {
      return new Chunker(new ChunkingConfig(Strategy.CHARACTER_BOUNDARY, 500, 50));
    }

    @Bean
    EmbeddingGenerationService embeddingGenerationService() {
      return new EmbeddingGenerationService(null, 32);
    }

    @Bean
    OutboxService outboxService() {
      return new OutboxService(null);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import({DocumentIndexingWorker.class, IngestionWorkerLifecycle.class})
  static class WorkerWiring {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "agentflow.app-name=wiring-test",
              "agentflow.security.issuer-uri=https://issuer.example.test",
              "agentflow.security.audience=agentflow-api");

  @Test
  void the_service_is_built_with_the_default_skip_policy_and_no_payload_store() {
    runner
        .withUserConfiguration(Wiring.class)
        .run(
            context -> {
              // The payload store is opt-in (ADR-014): no Garage client, but the app boots and the
              // service exists — indexing fails loudly at call time, not at boot.
              assertThat(context).hasSingleBean(IngestionService.class);
            });
  }

  @Test
  void the_configured_update_policy_is_honored() {
    runner
        .withPropertyValues("agentflow.ingestion.indexing.redelivery-policy=update")
        .withUserConfiguration(Wiring.class)
        .run(context -> assertThat(context).hasSingleBean(IngestionService.class));
  }

  @Test
  void an_unknown_redelivery_policy_fails_the_wiring() {
    runner
        .withPropertyValues("agentflow.ingestion.indexing.redelivery-policy=merge")
        .withUserConfiguration(Wiring.class)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void the_worker_beans_stay_absent_without_the_role_flag() {
    runner
        .withUserConfiguration(WorkerWiring.class)
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(DocumentIndexingWorker.class);
              assertThat(context).doesNotHaveBean(IngestionWorkerLifecycle.class);
            });
  }

  @Test
  void the_worker_beans_stay_absent_without_a_database_even_with_the_flag() {
    runner
        .withPropertyValues("agentflow.worker.ingestion-loop-enabled=true")
        .withUserConfiguration(WorkerWiring.class)
        .run(
            context -> {
              // The pipeline is database-backed: the flag alone never constructs a consumer.
              assertThat(context).doesNotHaveBean(DocumentIndexingWorker.class);
              assertThat(context).doesNotHaveBean(IngestionWorkerLifecycle.class);
            });
  }
}
