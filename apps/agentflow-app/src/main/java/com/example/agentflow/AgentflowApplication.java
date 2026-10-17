package com.example.agentflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the AgentFlow Spring Boot artifact (S01-07).
 *
 * <p>This is a minimal skeleton that only starts the application context on a clean machine. Kafka
 * consumers and AI calls are intentionally absent until the relevant sprints (see
 * DEVELOPMENT_PLAN.md, Sprint 1).
 */
@SpringBootApplication
public class AgentflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(AgentflowApplication.class, args);
  }
}
