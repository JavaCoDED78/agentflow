package com.example.agentflow.mcplab;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A protocol-level smoke test of the S11-08 acceptance ("the service starts independently"): boots
 * the lab on a random port and performs the MCP {@code initialize} handshake against the
 * Streamable-HTTP endpoint (ADR-019 transport) over real HTTP with the JDK client — no mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpLabEndpointTest {

  @LocalServerPort int port;

  @Test
  void the_streamable_http_endpoint_answers_the_initialize_handshake() throws Exception {
    String initialize =
        """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
            "protocolVersion":"2025-11-25","capabilities":{},\
            "clientInfo":{"name":"smoke-test","version":"1.0"}}}
            """;
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            // The Streamable-HTTP transport requires the client to accept both plain
            // JSON responses and SSE streams on the same endpoint.
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(initialize))
            .build();

    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    // The handshake answer carries this server's identity — proof the MCP server,
    // not some fallback controller, answered on the ADR-019 endpoint.
    assertThat(response.body()).contains("agentflow-mcp-lab");
  }
}
