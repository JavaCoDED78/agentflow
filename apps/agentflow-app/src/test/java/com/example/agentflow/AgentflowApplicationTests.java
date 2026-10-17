package com.example.agentflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the Spring context of the skeleton boots with the default profile. Uses the mock servlet
 * web environment (no real port is bound).
 *
 * <p>The test class and its method are public: JUnit 6.0 follows standard Java visibility semantics
 * during discovery, and the Jupiter engine lives in a different package, so package-private test
 * members would not be discovered.
 */
@SpringBootTest
public class AgentflowApplicationTests {

  @Test
  public void contextLoads() {}
}
