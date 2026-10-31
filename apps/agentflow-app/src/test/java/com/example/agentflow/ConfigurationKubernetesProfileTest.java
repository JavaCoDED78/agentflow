package com.example.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code application-kubernetes.yml} loads under the {@code kubernetes} profile. The Kafka producer
 * is enabled and its {@code bootstrap-servers} defaults to the in-cluster service DNS ({@code
 * kafka:9092}), which does not resolve on a dev box or in CI, so the test overrides it with {@code
 * localhost:9092} to verify the bean builds. {@code KafkaProducer} validates that the bootstrap
 * urls resolve but does not connect at construction, so this succeeds without a running broker.
 */
@SpringBootTest(properties = "agentflow.kafka.producer.bootstrap-servers=localhost:9092")
@ActiveProfiles("kubernetes")
class ConfigurationKubernetesProfileTest {

  @Test
  void kubernetes_context_loads() {}
}
