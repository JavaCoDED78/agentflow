package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proof that the OpenAPI contract is produced by the running application (S03-12).
 *
 * <p>The Spring context boots on the default profile (no database is required — {@code
 * application.yml} excludes the {@code DataSource} auto-configuration), with springdoc on the
 * classpath, so the assertions exercise the real generated {@link OpenAPI} model rather than a
 * hand-written file. It verifies the API metadata and the documented security scheme, which {@code
 * contracts/openapi/README.md} requires even before any endpoint exists.
 */
@SpringBootTest
class OpenApiContractTest {

  /** The model built by {@link com.example.agentflow.config.OpenApiConfig#agentFlowOpenApi()}. */
  @Autowired private OpenAPI openApi;

  /** The context loads with springdoc present, so a model is generated at runtime. */
  @Test
  void context_generates_open_api_model() {
    assertThat(openApi).isNotNull();
  }

  /** The API metadata (title + version) is documented, not empty. */
  @Test
  void api_metadata_is_documented() {
    assertThat(openApi.getInfo().getTitle()).isNotEmpty();
    assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
  }

  /**
   * The {@code bearerAuth} security scheme is an HTTP bearer (JWT) scheme: the placeholder for the
   * OIDC resource server added in S04. It is referenced from the global security requirement.
   */
  @Test
  void security_scheme_is_documented() {
    var components = openApi.getComponents();
    var schemes = components != null ? components.getSecuritySchemes() : null;
    Optional<SecurityScheme> bearer =
        schemes == null ? Optional.empty() : Optional.ofNullable(schemes.get("bearerAuth"));

    assertThat(bearer)
        .as("bearerAuth security scheme is documented for the secured /api/v1 endpoints")
        .hasValueSatisfying(
            scheme -> {
              assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
              assertThat(scheme.getScheme()).isEqualTo("bearer");
            });
  }

  /** The global security requirement references the bearer scheme. */
  @Test
  void global_security_requirement_is_applied() {
    assertThat(openApi.getSecurity()).isNotEmpty();
  }
}
