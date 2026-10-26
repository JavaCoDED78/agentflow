package com.example.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** {@code application-test.yml} loads under the {@code test} profile (no hardcoded localhost). */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "agentflow.app-name=agentflow-app")
class ConfigurationTestProfileTest {

  @Test
  void test_context_loads() {}
}
