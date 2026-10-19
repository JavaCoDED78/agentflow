package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.config.AgentFlowProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code application-local.yml} loads and the typed properties bind under the {@code local}
 * profile.
 */
@SpringBootTest
@ActiveProfiles("local")
class ConfigurationLocalProfileTest {

  @Autowired private AgentFlowProperties properties;

  @Test
  void localContextLoads() {
    assertThat(properties.getAppName()).isEqualTo("agentflow-app");
  }
}
