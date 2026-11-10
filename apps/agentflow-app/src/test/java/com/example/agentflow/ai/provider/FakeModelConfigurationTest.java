package com.example.agentflow.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.ChatModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S09-11 configuration test for {@link FakeModelConfiguration}.
 *
 * <p>Proves the opt-in contract of the deterministic fake provider: the {@code ChatModelPort} bean
 * exists only when {@code agentflow.ai.provider=fake} is set, the fixture is selected through
 * {@code agentflow.ai.provider.fake.mode}, and an unknown mode fails the context at bean creation
 * rather than surfacing mid-run. {@link ApplicationContextRunner} starts a bare context, so the
 * real {@code @ConditionalOnProperty} gate and {@code @Value} binding are exercised without booting
 * the whole application.
 */
class FakeModelConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(FakeModelConfiguration.class);

  @Test
  void the_fake_port_is_wired_only_when_the_provider_is_fake() {
    runner
        .withPropertyValues("agentflow.ai.provider=fake")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasBean("chatModelPort");
              assertThat(context.getType("chatModelPort")).isEqualTo(FakeModelProvider.class);
              assertThat(context.getBean(FakeModelProvider.class).fixture())
                  .isEqualTo(FakeModelProvider.Fixture.VALID_PLAN);
            });
  }

  @Test
  void the_configured_fixture_mode_is_honored() {
    runner
        .withPropertyValues("agentflow.ai.provider=fake", "agentflow.ai.provider.fake.mode=refusal")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(FakeModelProvider.class).fixture())
                  .isEqualTo(FakeModelProvider.Fixture.REFUSAL);
            });
  }

  @Test
  void an_unknown_fixture_mode_fails_the_context_at_bean_creation() {
    runner
        .withPropertyValues("agentflow.ai.provider=fake", "agentflow.ai.provider.fake.mode=chaos")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void no_port_is_created_without_the_fake_provider_property() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ChatModelPort.class);
        });
  }
}
