package com.example.agentflow.ingestion.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.DocumentParser;
import com.example.agentflow.ingestion.application.parser.BoundedDocumentParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wiring tests for {@link DocumentExtractionConfiguration} (S10-07, the wall-clock bound S14-10),
 * proving the bean shape: the {@link DocumentParser} bean is the {@link BoundedDocumentParser}
 * wrapper around the real Tika/PDFBox engine, built from {@code agentflow.ingestion.*}. A
 * misconfigured bound fails the wiring at startup — the same posture as the {@code max-pages} /
 * {@code max-chars} bounds. DB-free, no Tika, no network.
 */
class DocumentExtractionConfigurationTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AgentFlowProperties.class)
  @Import(DocumentExtractionConfiguration.class)
  static class Wiring {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues(
              "agentflow.app-name=wiring-test",
              "agentflow.security.issuer-uri=https://issuer.example.test",
              "agentflow.security.audience=agentflow-api");

  @Test
  void the_parser_bean_is_the_bounded_wrapper_around_the_tika_engine() {
    runner
        .withUserConfiguration(Wiring.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(DocumentParser.class);
              DocumentParser parser = context.getBean(DocumentParser.class);
              assertThat(parser).isInstanceOf(BoundedDocumentParser.class);
              // Note: the context is closed by the runner; the wrapper's daemon executor shuts down
              // via the @PreDestroy hook on context close.
            });
  }

  @Test
  void a_configured_wall_clock_bound_is_accepted() {
    runner
        .withPropertyValues("agentflow.ingestion.max-extraction-seconds=7")
        .withUserConfiguration(Wiring.class)
        .run(context -> assertThat(context).hasSingleBean(DocumentParser.class));
  }

  @Test
  void a_non_positive_wall_clock_bound_fails_the_wiring() {
    runner
        .withPropertyValues("agentflow.ingestion.max-extraction-seconds=0")
        .withUserConfiguration(Wiring.class)
        .run(context -> assertThat(context).hasFailed());
  }
}
