package com.example.agentflow.ai.provider;

import com.example.agentflow.ai.application.ChatModelPort;
import com.example.agentflow.ai.application.EmbeddingPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in wiring of the deterministic {@link FakeModelProvider} behind the neutral {@link
 * ChatModelPort} (S09-11), so a test or CI run can boot the real application context with {@code
 * agentflow.ai.provider=fake} and exercise the Planner end to end without any API key or network.
 *
 * <p>The whole configuration is <em>opt-in</em> and <em>test-only</em>: the beans exist only when
 * the application is explicitly told to use the fake provider, {@code agentflow.ai.provider=fake},
 * and the class itself lives in the test source set (the S04-10 pattern — the permissive security
 * chain and this fake are compiled into no production jar), so a misconfigured profile can never
 * point a real deployment at it. The default, local and kubernetes profiles never set the property,
 * so the core app boots exactly as before — with no {@link ChatModelPort} bean, exactly the S09-02
 * contract.
 *
 * <p>The behaviour is selected through {@code agentflow.ai.provider.fake.mode} (default {@code
 * valid-plan}); see {@link FakeModelProvider.Fixture#fromConfig} for the accepted names. An unknown
 * mode fails the context at bean creation, not mid-run.
 *
 * <p>S10-12 extends the same opt-in gate with the embedding seam: when the fake provider is
 * selected, a {@link FakeEmbeddingProvider} also stands behind the neutral {@link EmbeddingPort}
 * (mode via {@code agentflow.ai.provider.fake.embedding-mode}, default {@code deterministic}), so a
 * CI context exercises the embedding generation service (batching, ordering, validation) without a
 * real model — the S10-12 acceptance. The ingestion-side {@code EmbeddingGenerationService} bean
 * appears automatically, because its wiring only waits for an {@link EmbeddingPort}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "agentflow.ai",
    name = "provider",
    havingValue = "fake",
    matchIfMissing = false)
public class FakeModelConfiguration {

  /**
   * The fake provider port (S09-11): a deterministic {@link FakeModelProvider} standing behind the
   * neutral {@link ChatModelPort}, selected by {@code agentflow.ai.provider.fake.mode}.
   *
   * @param mode the fixture mode (see {@link FakeModelProvider.Fixture#fromConfig}); defaults to
   *     {@code valid-plan}
   * @return the fake provider implementing {@link ChatModelPort}
   */
  @Bean
  public ChatModelPort chatModelPort(
      @Value("${agentflow.ai.provider.fake.mode:valid-plan}") String mode) {
    return new FakeModelProvider(FakeModelProvider.Fixture.fromConfig(mode));
  }

  /**
   * The fake embedding port (S10-12): a deterministic {@link FakeEmbeddingProvider} standing behind
   * the neutral {@link EmbeddingPort}, selected by {@code
   * agentflow.ai.provider.fake.embedding-mode}.
   *
   * @param mode the fixture mode (see {@link FakeEmbeddingProvider.Fixture#fromConfig}); defaults
   *     to {@code deterministic}
   * @return the fake provider implementing {@link EmbeddingPort}
   */
  @Bean
  public EmbeddingPort embeddingPort(
      @Value("${agentflow.ai.provider.fake.embedding-mode:deterministic}") String mode) {
    return new FakeEmbeddingProvider(FakeEmbeddingProvider.Fixture.fromConfig(mode));
  }
}
