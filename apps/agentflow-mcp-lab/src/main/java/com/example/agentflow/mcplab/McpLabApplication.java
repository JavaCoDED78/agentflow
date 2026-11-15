package com.example.agentflow.mcplab;

import com.example.agentflow.mcplab.ticket.LabTicketLookupService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The isolated MCP demo server (S11-08, ADR-019).
 *
 * <p>A standalone Spring Boot process exposing one safe, read-only demo tool ({@code
 * ticket_lookup}) over the Streamable-HTTP MCP transport. It shares no code and no credentials with
 * the {@code agentflow-app} monolith: no JDBC, no Flyway, no Kafka, no provider keys — the fixture
 * answers from an in-memory dataset so the service runs anywhere, with nothing to leak.
 */
@SpringBootApplication
public class McpLabApplication {

  public static void main(String[] args) {
    SpringApplication.run(McpLabApplication.class, args);
  }

  /**
   * The lab's MCP tool surface: the demo ticket lookup. The method-level {@link
   * org.springframework.ai.tool.annotation.Tool @Tool} annotation on the service declares name,
   * description and schema; Spring AI converts it into the MCP tool spec the Streamable-HTTP server
   * advertises to clients.
   */
  @Bean
  ToolCallbackProvider labTools(LabTicketLookupService ticketLookupService) {
    return MethodToolCallbackProvider.builder().toolObjects(ticketLookupService).build();
  }
}
