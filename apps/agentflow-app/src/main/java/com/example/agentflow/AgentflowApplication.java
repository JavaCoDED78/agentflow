package com.example.agentflow;

import com.example.agentflow.ai.prompt.AiPromptProperties;
import com.example.agentflow.config.AgentFlowProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the AgentFlow Spring Boot artifact (S01-07).
 *
 * <p>This is a minimal skeleton that only starts the application context on a clean machine. Kafka
 * consumers and AI calls are intentionally absent until the relevant sprints (see
 * DEVELOPMENT_PLAN.md, Sprint 1). Typed configuration is registered in S01-17.
 */
@SpringBootApplication
@EnableConfigurationProperties({AgentFlowProperties.class, AiPromptProperties.class})
@EnableScheduling
public class AgentflowApplication {

  public static void main(String[] args) {
    SpringApplication.run(AgentflowApplication.class, args);
  }
}
