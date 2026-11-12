package com.example.agentflow.knowledgebase.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.ai.application.EmbeddingPort;
import com.example.agentflow.ai.application.EmbeddingRequest;
import com.example.agentflow.ai.application.EmbeddingResponse;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.knowledgebase.application.RetrievalService;
import com.example.agentflow.knowledgebase.application.SearchRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wiring tests for {@link RetrievalConfiguration} (S10-14), proving the opt-in bean shape: the
 * {@link RetrievalService} is bound from {@code agentflow.retrieval.*}, and — the S01-02 contract —
 * a context with no database and no embedding provider still boots: the service then holds no
 * repository and no port and fails loudly at {@link RetrievalService#search} call time (the {@code
 * PlannerService} / {@code ChatModelPort} / {@code ADR-014} idiom). DB-free, no provider, no
 * network.
 */
class RetrievalConfigurationTest {

  private static final UUID TENANT = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID KNOWLEDGE_BASE =
      UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  /** A minimal port double; the wiring test never drives a real search through it. */
  private static final class StubPort implements EmbeddingPort {
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
      float[] vector = new float[768];
      vector[0] = 1f;
      return new EmbeddingResponse(request.model(), List.of(vector), null);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(RetrievalConfiguration.class)
  static class Wiring {

    @Bean
    EmbeddingPort embeddingPort() {
      return new StubPort();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(RetrievalConfiguration.class)
  static class NoProviderWiring {}

  private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
      new org.springframework.boot.test.context.runner.ApplicationContextRunner()
          .withPropertyValues(
              "agentflow.app-name=wiring-test",
              "agentflow.security.issuer-uri=https://issuer.example.test",
              "agentflow.security.audience=agentflow-api");

  @Test
  void the_service_appears_and_uses_the_configured_retrieval_bounds() {
    runner
        .withPropertyValues(
            "agentflow.retrieval.default-top-k=7",
            "agentflow.retrieval.max-top-k=21",
            "agentflow.retrieval.threshold=0.25")
        .withUserConfiguration(Wiring.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(RetrievalService.class);
              RetrievalService service = context.getBean(RetrievalService.class);
              assertThat(service.defaultTopK()).isEqualTo(7);
              assertThat(service.maxTopK()).isEqualTo(21);
              assertThat(service.threshold()).isEqualTo(0.25);
            });
  }

  @Test
  void the_service_uses_the_default_bounds_when_none_are_configured() {
    runner
        .withUserConfiguration(Wiring.class)
        .run(
            context -> {
              RetrievalService service = context.getBean(RetrievalService.class);
              assertThat(service.defaultTopK()).isEqualTo(10);
              assertThat(service.maxTopK()).isEqualTo(50);
              assertThat(service.threshold()).isEqualTo(0.5);
            });
  }

  @Test
  void the_context_boots_without_a_database_or_provider_and_search_fails_loudly_at_call_time() {
    runner
        .withUserConfiguration(NoProviderWiring.class)
        .run(
            context -> {
              // ADR-014 / S01-02: no repositories, no EmbeddingPort bean, but the app still boots
              // and the service exists — retrieval fails loudly at call time, not at boot.
              assertThat(context).doesNotHaveBean(EmbeddingPort.class);
              assertThat(context).hasSingleBean(RetrievalService.class);
              RetrievalService service = context.getBean(RetrievalService.class);
              assertThatThrownBy(
                      () -> service.search(TENANT, KNOWLEDGE_BASE, new SearchRequest("q", null)))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no database connection");
            });
  }
}
