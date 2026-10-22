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
 *
 * <p>The {@code local} profile re-enables the DataSource auto-configuration, but the DataSource
 * itself is wired from {@code spring.datasource.*} supplied by the environment (git-ignored {@code
 * .env}), which unit tests do not have. This context-load test only verifies typed-property
 * binding, so the DataSource auto-configuration is excluded here; a real database is exercised by
 * the integration suites (Testcontainers) in later sprints.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ActiveProfiles("local")
class ConfigurationLocalProfileTest {

  @Autowired private AgentFlowProperties properties;

  @Test
  void localContextLoads() {
    assertThat(properties.getAppName()).isEqualTo("agentflow-app");
  }
}
