package com.example.agentflow.mcplab;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.agentflow.mcplab.ticket.LabTicketLookupService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The S11-08 wiring acceptance, DB-free: the lab boots as an independent Spring Boot application,
 * advertises exactly one MCP tool, and carries no datasource — isolation from the business database
 * by absence, not by configuration.
 */
@SpringBootTest
class McpLabApplicationTest {

  @Autowired ApplicationContext context;

  @Test
  void the_lab_boots_and_exposes_the_demo_tool() {
    assertThat(context.getBean(LabTicketLookupService.class)).isNotNull();

    ToolCallbackProvider tools = context.getBean(ToolCallbackProvider.class);
    assertThat(tools.getToolCallbacks())
        .extracting(ToolCallback::getToolDefinition)
        .extracting(definition -> definition.name())
        .containsExactly("ticket_lookup");
  }

  @Test
  void the_lab_has_no_datasource_isolation_from_the_business_database() {
    // No JDBC starter, no Flyway, no PostgreSQL driver on the classpath: the
    // business database is unreachable from this process at all.
    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
  }
}
