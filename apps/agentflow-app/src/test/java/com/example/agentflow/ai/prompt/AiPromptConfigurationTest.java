package com.example.agentflow.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.ai.application.PromptVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Proves the S09-04 Spring wiring: the {@code ai.prompt} declarations bind from configuration, the
 * {@link PromptCatalogue} bean loads and versions the declared template, and a blank {@code
 * version} fails startup through the real {@code @Validated} binding path (a {@code
 * ConfigurationPropertiesBindException}), like every other required property (S01-17). DB-free: the
 * catalogue has no {@code DataSource} / Kafka dependency (S01-02), so an {@link
 * ApplicationContextRunner} exercises the configuration-to-bean path without Testcontainers.
 */
class AiPromptConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(TestConfig.class, PromptConfiguration.class);

  @Test
  void prompt_catalogue_binds_the_configured_prompt() {
    runner
        .withPropertyValues("ai.prompt.prompts[0].name=planner", "ai.prompt.prompts[0].version=1")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              PromptCatalogue catalogue = context.getBean(PromptCatalogue.class);
              assertThat(catalogue.names()).containsExactly("planner");
              PromptVersion version = catalogue.versionOf("planner");
              assertThat(version.name()).isEqualTo("planner");
              assertThat(version.version()).isEqualTo("1");
              assertThat(version.hash()).hasSize(64);
            });
  }

  @Test
  void a_blank_prompt_version_fails_startup_through_the_validated_binding() {
    runner
        .withPropertyValues("ai.prompt.prompts[0].name=planner", "ai.prompt.prompts[0].version=")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class));
  }

  @Test
  void an_unknown_prompt_template_fails_startup() {
    // The declaration names a prompt whose resource/ai/<name>.txt is not on the classpath: the
    // catalogue throws when it is constructed, so the app fails to boot (fail fast) rather than
    // later failing a runtime call against an undeclared template.
    runner
        .withPropertyValues("ai.prompt.prompts[0].name=nope", "ai.prompt.prompts[0].version=1")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasMessageContaining("prompt template not found on classpath"));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(AiPromptProperties.class)
  static class TestConfig {}
}
