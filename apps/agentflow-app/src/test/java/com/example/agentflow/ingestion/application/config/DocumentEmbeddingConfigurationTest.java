package com.example.agentflow.ingestion.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.embed.EmbeddingGenerationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wiring tests for {@link DocumentEmbeddingConfiguration} (S10-12), proving the opt-in bean shape:
 * the {@link EmbeddingGenerationService} is bound from {@code agentflow.ingestion.embedding.*},
 * wires the {@link EmbeddingPort} seam when one exists, and — the S01-02 contract — a context with
 * no embedding provider still boots: the service then holds no port and fails loudly at {@link
 * EmbeddingGenerationService#embed} call time (the {@code PlannerService} / {@code ChatModelPort}
 * idiom). DB-free, no provider, no network.
 */
class DocumentEmbeddingConfigurationTest {

  /** A minimal port double; the wiring test never calls it. */
  private static final class StubPort implements EmbeddingPort {
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      List<float[]> vectors = request.texts().stream().map(text -> new float[4]).toList();
      return new EmbeddingResponse(request.model(), vectors, null);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(DocumentEmbeddingConfiguration.class)
  static class Wiring {

    @Bean
    EmbeddingPort embeddingPort() {
      return new StubPort();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(DocumentEmbeddingConfiguration.class)
  static class NoProviderWiring {}

  private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
      new org.springframework.boot.test.context.runner.ApplicationContextRunner()
          .withPropertyValues(
              "agentflow.app-name=wiring-test",
              "agentflow.security.issuer-uri=https://issuer.example.test",
              "agentflow.security.audience=agentflow-api");

  @Test
  void the_service_appears_when_an_embedding_port_exists_and_uses_the_configured_batch_size() {
    runner
        .withPropertyValues("agentflow.ingestion.embedding.batch-size=7")
        .withUserConfiguration(Wiring.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(EmbeddingGenerationService.class);
              assertThat(context.getBean(EmbeddingGenerationService.class).batchSize())
                  .isEqualTo(7);
            });
  }

  @Test
  void the_service_uses_the_default_batch_size_when_none_is_configured() {
    runner
        .withUserConfiguration(Wiring.class)
        .run(
            context ->
                assertThat(context.getBean(EmbeddingGenerationService.class).batchSize())
                    .isEqualTo(32));
  }

  @Test
  void the_context_boots_without_a_provider_and_the_service_fails_loudly_at_call_time() {
    runner
        .withUserConfiguration(NoProviderWiring.class)
        .run(
            context -> {
              // The port is opt-in (ADR-014): no EmbeddingPort bean, but the app still boots and
              // the service exists — embedding fails loudly at call time, not at boot.
              assertThat(context).doesNotHaveBean(EmbeddingPort.class);
              assertThat(context).hasSingleBean(EmbeddingGenerationService.class);
              EmbeddingGenerationService service =
                  context.getBean(EmbeddingGenerationService.class);
              assertThatThrownBy(() -> service.embed("m", List.of("x")))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no EmbeddingPort is configured");
            });
  }
}
