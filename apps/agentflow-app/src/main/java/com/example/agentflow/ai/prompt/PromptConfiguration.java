package com.example.agentflow.ai.prompt;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link PromptCatalogue} (S09-04) from the {@code ai.prompt} configuration.
 *
 * <p>The catalogue is a plain value object with no {@code DataSource} / Kafka dependency, so the
 * {@link #promptCatalogue} bean is safe in the DB-less skeleton (S01-02) and in every role; the
 * planner (S09-06) consumes it through {@link com.example.agentflow.ai.application.ChatModelPort}.
 * A missing template or blank declaration fails construction at context startup.
 */
@Configuration
public class PromptConfiguration {

  /**
   * @param properties the validated {@code ai.prompt} declarations
   * @return the catalogue that loads and versions the declared prompts
   */
  @Bean
  public PromptCatalogue promptCatalogue(AiPromptProperties properties) {
    List<PromptRef> refs = properties.getPrompts();
    return refs.isEmpty() ? new PromptCatalogue(List.of()) : new PromptCatalogue(refs);
  }
}
