package com.example.agentflow.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The OpenAPI contract gate (S03-16).
 *
 * <p>Boots the running application, fetches the freshly generated description from the live {@code
 * /api/v1/v3/api-docs} endpoint and compares it against the checked-in baseline ({@code
 * contracts/openapi/openapi.json}) with {@link OpenApiCompatibility}. It fails the build when the
 * code drifts from the contract in a <em>breaking</em> way — in particular when a required field
 * that the contract promises is dropped ("unintentional removal of a required field breaks CI", the
 * S03-16 acceptance criterion). Compatible drift is reported but does not fail the build: the
 * baseline is refreshed deliberately via {@code ./gradlew generateOpenApiContract}.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiContractCheckTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @LocalServerPort private int port;

  @Test
  void currentContractDoesNotBreakTheCommittedBaseline() throws Exception {
    // The gate runs via ./gradlew check, which sets -Dopenapi.contract.baseline. When the class is
    // run directly (e.g. from an IDE) without it, skip instead of failing the build.
    String baselineProperty = System.getProperty("openapi.contract.baseline");
    Assumptions.assumeTrue(
        baselineProperty != null,
        "OpenAPI contract gate: run via ./gradlew check (sets -Dopenapi.contract.baseline)");
    Path baselinePath = Path.of(baselineProperty);
    assertThat(baselinePath).as("the contract baseline must exist").exists().isRegularFile();

    JsonNode current = mapper.readTree(fetchSpec());
    JsonNode baseline = mapper.readTree(Files.readAllBytes(baselinePath));

    OpenApiCompatibility.CompatibilityReport report =
        new OpenApiCompatibility().compare(baseline, current);

    assertThat(report.breakingChanges())
        .as(
            "the generated contract must not break the checked-in baseline. "
                + "If this is an intended breaking change, regenerate the baseline with "
                + "./gradlew generateOpenApiContract, add a CHANGELOG entry and an ADR if needed.\n"
                + report.summary())
        .isEmpty();
  }

  private byte[] fetchSpec() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/v3/api-docs"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<byte[]> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).as("api-docs must be reachable").isEqualTo(200);
    return response.body();
  }
}
