package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.config.AgentFlowProperties.Upload;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Proves the {@code S10-03} config acceptance through the real {@code @Validated} +
 * {@code @ConfigurationProperties} binding path: the {@code agentflow.upload.*} group binds with
 * its safe defaults, the custom values override them, and an invalid value fails startup. DB-free:
 * an {@link ApplicationContextRunner} exercises the configuration-to-value path (the same pattern
 * {@link StoragePropertiesTest} and {@link ToolExecutionConfigurationTest} use).
 */
class UploadPropertiesTest {

  private static final String[] REQUIRED_PROPS = {
    "agentflow.app-name=agentflow-app",
    "agentflow.security.issuer-uri=https://keycloak.test/realms/agentflow",
    "agentflow.security.audience=agentflow-api"
  };

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  ValidationAutoConfiguration.class))
          .withUserConfiguration(AgentFlowStartupTestConfig.class)
          .withPropertyValues(REQUIRED_PROPS);

  @Test
  void the_upload_group_binds_with_the_safe_defaults() {
    runner.run(
        context -> {
          assertThat(context.getStartupFailure()).isNull();
          Upload upload = context.getBean(AgentFlowProperties.class).getUpload();
          assertThat(upload.getMaxBytes()).isEqualTo(10 * 1024 * 1024);
          assertThat(upload.getMaxFileNameLength()).isEqualTo(255);
          assertThat(upload.getMaxMediaDurationSeconds()).isEqualTo(300);
        });
  }

  @Test
  void custom_upload_limits_bind_from_agentflow_upload() {
    runner
        .withPropertyValues(
            "agentflow.upload.max-bytes=2097152",
            "agentflow.upload.max-filename-length=120",
            "agentflow.upload.max-media-duration-seconds=60")
        .run(
            context -> {
              assertThat(context.getStartupFailure()).isNull();
              Upload upload = context.getBean(AgentFlowProperties.class).getUpload();
              assertThat(upload.getMaxBytes()).isEqualTo(2097152);
              assertThat(upload.getMaxFileNameLength()).isEqualTo(120);
              assertThat(upload.getMaxMediaDurationSeconds()).isEqualTo(60);
            });
  }

  @Test
  void an_invalid_upload_limit_fails_startup_through_the_validated_binding() {
    runner
        .withPropertyValues("agentflow.upload.max-bytes=-1")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ConfigurationPropertiesBindException.class)
                    .hasMessageContaining("AgentFlowProperties"));
  }
}
