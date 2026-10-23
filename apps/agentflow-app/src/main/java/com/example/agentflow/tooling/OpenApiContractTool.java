package com.example.agentflow.tooling;

import com.example.agentflow.AgentflowApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regenerates the checked-in OpenAPI contract (S03-16).
 *
 * <p>Boots the application on the default profile (no database is required — {@code
 * application.yml} excludes the {@code DataSource} auto-configuration), waits for the live {@code
 * /api/v1/v3/api-docs} endpoint, fetches the generated description and writes a <em>normalized</em>
 * copy to the output path (or stdout). Normalization removes the volatile {@code servers} entry
 * (its {@code url} is the ephemeral local port), so the committed baseline is byte-for-byte
 * reproducible and a diff ever shows only a real contract change. The file is never edited by hand
 * — see {@code contracts/openapi/README.md}.
 *
 * <p>Run it as the {@code ./gradlew generateOpenApiContract} task:
 *
 * <pre>{@code ./gradlew :apps:agentflow-app:generateOpenApiContract}</pre>
 *
 * <p>This is the deliberate "approve the baseline" action: when the contract changes (including an
 * intended breaking change) the developer regenerates the file, reviews the diff and, together with
 * the CI compatibility gate, opens the PR.
 */
public final class OpenApiContractTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);
  private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(30);

  private OpenApiContractTool() {}

  public static void main(String[] args) throws Exception {
    String output = expectOutputArg(args);
    try (ConfigurableApplicationContext context =
        new SpringApplication(AgentflowApplication.class).run(args)) {
      int port = resolvePort(context);
      String source = "http://localhost:" + port + "/api/v1/v3/api-docs";
      String normalized = normalize(fetchOpenApi(context, port, source), source);

      if (output == null || output.isBlank()) {
        System.out.print(normalized);
      } else {
        Path path = Path.of(output);
        Files.createDirectories(path.getParent());
        Files.write(path, normalized.getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote OpenAPI contract to " + path.toAbsolutePath());
      }
    }
  }

  private static String expectOutputArg(String[] args) {
    for (int i = 0; i < args.length - 1; i++) {
      if ("--output".equals(args[i])) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static int resolvePort(ConfigurableApplicationContext context) {
    String port =
        firstNonBlank(
            context.getEnvironment().getProperty("agentflow.http.port"),
            context.getEnvironment().getProperty("server.port"));
    return (port == null || port.isBlank()) ? 8080 : Integer.parseInt(port.trim());
  }

  private static String normalize(String json, String source) throws Exception {
    JsonNode tree = MAPPER.readTree(json);
    // The `servers[].url` is the ephemeral local port and is not meaningful for a checked-in
    // contract; dropping it keeps generation deterministic and independent of the launch port.
    if (tree instanceof ObjectNode) {
      ((ObjectNode) tree).remove("servers");
    }
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
  }

  private static String fetchOpenApi(
      ConfigurableApplicationContext context, int port, String source) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(source))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpClient client = HttpClient.newHttpClient();
    long deadline = System.currentTimeMillis() + READINESS_TIMEOUT.toMillis();
    while (true) {
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
          return response.body();
        }
        throw new IllegalStateException("api-docs returned HTTP " + response.statusCode());
      } catch (java.net.ConnectException | java.net.UnknownHostException e) {
        if (System.currentTimeMillis() > deadline) {
          throw new IllegalStateException(
              "the api-docs endpoint at "
                  + source
                  + " did not become ready in "
                  + READINESS_TIMEOUT,
              e);
        }
        Thread.sleep(POLL_INTERVAL.toMillis());
      }
    }
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
