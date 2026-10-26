package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the typed {@link AgentFlowProperties} (S01-17): container-ready defaults and Bean
 * Validation of the mandatory/range constraints, validated through the real Hibernate Validator.
 */
class AgentFlowPropertiesTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void defaults_are_container_ready() {
    AgentFlowProperties properties = new AgentFlowProperties();

    assertThat(properties.getHttp().getPort()).isEqualTo(8080);
    assertThat(properties.getHttp().getHost()).isEqualTo("0.0.0.0");
  }

  @Test
  void rejects_missing_app_name() {
    // appName has no Java default, so it stays null and @NotBlank fails validation.
    AgentFlowProperties properties = new AgentFlowProperties();

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  void rejects_out_of_range_port() throws ReflectiveOperationException {
    AgentFlowProperties properties = new AgentFlowProperties();
    var portField = AgentFlowProperties.Http.class.getDeclaredField("port");
    portField.setAccessible(true);
    portField.set(properties.getHttp(), 70000);

    assertThat(validator.validate(properties)).isNotEmpty();
  }

  @Test
  void graph_max_bytes_defaults_to_one_mebibyte() {
    AgentFlowProperties properties = new AgentFlowProperties();

    assertThat(properties.getGraph().getMaxBytes()).isEqualTo(1_000_000);
  }

  @Test
  void rejects_non_positive_graph_max_bytes() throws ReflectiveOperationException {
    AgentFlowProperties properties = new AgentFlowProperties();
    var maxBytesField = AgentFlowProperties.Graph.class.getDeclaredField("maxBytes");
    maxBytesField.setAccessible(true);
    maxBytesField.set(properties.getGraph(), 0);

    assertThat(validator.validate(properties)).isNotEmpty();
  }
}
