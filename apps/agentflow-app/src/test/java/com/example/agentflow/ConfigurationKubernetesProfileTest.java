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
 *
 * <p>The kubernetes profile is a production-like profile that requires secrets from the environment
 * (fail loud, invariant 8): {@code AGENTFLOW_REDIS_PASSWORD} (read by {@code
 * RedisConnectionConfiguration} via {@code spring.redis.password}) and {@code
 * AGENTFLOW_KEYCLOAK_ISSUER_URI}. A context-load test that verifies the profile wires up correctly
 * supplies those values, mirroring how a Kubernetes Secret injects them at real deploy time.
 */
@SpringBootTest(
    properties = {
      "agentflow.kafka.producer.bootstrap-servers=localhost:9092",
      "AGENTFLOW_REDIS_PASSWORD=k8s-test-redis-password",
      "AGENTFLOW_KEYCLOAK_ISSUER_URI=https://keycloak.test/realms/agentflow"
    })
@ActiveProfiles("kubernetes")
class ConfigurationKubernetesProfileTest {

  @Test
  void kubernetes_context_loads() {}
}
