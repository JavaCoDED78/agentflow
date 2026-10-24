package com.example.agentflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the OpenAPI description served by springdoc (S03-12).
 *
 * <p>The description lives under the {@code /api/v1} REST prefix and documents the security scheme
 * for those endpoints. The model is generated deterministically and checked in under {@code
 * contracts/openapi/}; it is never edited by hand (see {@code contracts/openapi/README.md}).
 *
 * <p>The security scheme below is the HTTP bearer (JWT) scheme for the secured {@code /api/v1}
 * endpoints. The issuer and audience are enforced by the OAuth 2.0 Resource Server added in S04-03:
 * every request must present a valid Keycloak access token.
 */
@Configuration
class OpenApiConfig {

  /**
   * The OpenAPI model: API metadata plus the bearer/JWT security scheme for the secured {@code
   * /api/v1} endpoints.
   *
   * @return the OpenAPI model consumed by springdoc and written to the checked-in contract
   */
  @Bean
  OpenAPI agentFlowOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("AgentFlow API")
                .description(
                    "REST API for the AgentFlow durable workflow platform. Endpoints are served "
                        + "under {@code /api/v1}; the tenant is taken only from the verified "
                        + "security context, never from the request body.")
                .version("v1"))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "OIDC access token from Keycloak, sent as "
                                + "{@code Authorization: Bearer <token>} "
                                + "(issuer and audience established by the OIDC resource "
                                + "server in S04).")));
  }
}
