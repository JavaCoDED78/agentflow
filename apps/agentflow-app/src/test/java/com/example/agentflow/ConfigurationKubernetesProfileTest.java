package com.example.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@code application-kubernetes.yml} loads under the {@code kubernetes} profile. */
@SpringBootTest
@ActiveProfiles("kubernetes")
class ConfigurationKubernetesProfileTest {

  @Test
  void kubernetes_context_loads() {}
}
