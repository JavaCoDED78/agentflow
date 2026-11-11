package com.example.agentflow.ingestion.application.config;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.ingestion.application.chunk.Chunker;
import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.Strategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Deterministic-chunking wiring (S10-09).
 *
 * <p>Follows the {@link DocumentExtractionConfiguration} precedent: the {@link Chunker} bean is
 * built here from the {@code agentflow.ingestion.chunking.*} configuration group rather than being
 * a Spring {@code @Component}, so the chunk size, overlap and strategy stay configuration, never
 * magic numbers, and a misconfigured or nonsensical combination fails construction (via the {@link
 * ChunkingConfig} constructor) at context startup.
 *
 * <p>This class lives in the {@code ingestion} module (not the global {@code config} package) by
 * design: {@code DocumentExtractionConfiguration} already makes {@code config} depend on {@code
 * ingestion.application.config}, and importing {@code ingestion.domain} (the {@code Strategy} enum)
 * from the global {@code config} package would add the reverse edge and a module-graph cycle.
 * Homing the wiring in {@code ingestion} keeps the graph acyclic while still mapping the config
 * strategy string to the domain enum.
 */
@Configuration
public class DocumentChunkingConfiguration {

  /**
   * The single active chunking strategy name, as configured under {@code
   * agentflow.ingestion.chunking.strategy}.
   */
  private static final String ACTIVE_STRATEGY = "character-boundary";

  /**
   * The deterministic chunker the ingestion worker drives on extracted text.
   *
   * @param properties the validated agent-flow configuration, including the chunking parameters
   * @return the chunker bound from {@code agentflow.ingestion.chunking.*}
   */
  @Bean
  public Chunker chunker(AgentFlowProperties properties) {
    AgentFlowProperties.Chunking chunking = properties.getChunking();
    Strategy strategy = parseStrategy(chunking.getStrategy());
    return new Chunker(
        new ChunkingConfig(strategy, chunking.getMaxChunkChars(), chunking.getOverlapChars()));
  }

  /**
   * Map the configured strategy name to the domain {@link Strategy}, failing fast on anything
   * unexpected.
   *
   * @param name the configured strategy name
   * @return the matching strategy
   * @throws IllegalArgumentException when the name is not one of the known strategies
   */
  private static Strategy parseStrategy(String name) {
    if (ACTIVE_STRATEGY.equals(name)) {
      return Strategy.CHARACTER_BOUNDARY;
    }
    throw new IllegalArgumentException(
        "Unknown chunking strategy '" + name + "', expected '" + ACTIVE_STRATEGY + "'");
  }
}
