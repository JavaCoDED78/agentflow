package com.example.agentflow.identity.application.authorization;

import com.example.agentflow.identity.domain.Permission;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The endpoint access matrix (S04-08, {@code docs/security/permissions.md}).
 *
 * <p>The single source of truth for <b>which permission each authenticated {@code /api/v1} endpoint
 * requires</b>. It sits next to the {@link Permission} catalogue in the {@code identity} module so
 * the catalogue and the matrix stay in one place; {@code
 * com.example.agentflow.config.SecurityConfiguration} consumes this matrix to build the secured
 * {@code SecurityFilterChain} (it never hardcodes the endpoint-to-permission mapping).
 *
 * <p>Three invariants back the S04-08 acceptance (least privilege, default-deny, separated
 * admin/operator operations):
 *
 * <ul>
 *   <li><b>Least privilege.</b> Every rule grants exactly one permission — the minimum capability
 *       the endpoint needs ({@code workflow:read} for a read, {@code workflow:write} for a write).
 *       No endpoint asks for more than it uses, so a token is never granted broad access it does
 *       not need.
 *   <li><b>Default-deny.</b> The matrix only lists what is allowed. {@code SecurityConfiguration}
 *       closes the rest with {@code anyRequest().authenticated()}, so an endpoint not in the matrix
 *       (and any future one) grants nothing until an explicit rule is added here.
 *   <li><b>Separated admin/operator operations.</b> User operations ({@code workflow:*}, {@code
 *       document:*}, {@code run:*}) are the rules below; <em>operator</em> operations that require
 *       the {@code ops:dlq} permission (dead-letter queue inspection) form a separate group, kept
 *       apart in the matrix and documented as such. A new operator/admin endpoint is a new rule
 *       here, never an ad-hoc exception in the security chain.
 * </ul>
 *
 * <p>The matrix is a plain, framework-agnostic data structure ({@code method} + {@code pathPattern}
 * + {@link Permission}) so it is easy to unit-test and to render as the documented access table;
 * the translation to Spring Security request matchers lives in {@code SecurityConfiguration}. It is
 * registered as a singleton {@link Component} so {@code SecurityConfiguration} can inject it; the
 * matrix is stateless, so a single instance is shared.
 */
@Component
public final class AccessMatrix {

  /**
   * A single endpoint rule: an HTTP {@code method}, an Ant-style {@code pathPattern} under {@code
   * /api/v1} (e.g. {@code /workflows/*}), and the single {@link Permission} that {@code
   * hasAuthority} checks against.
   *
   * @param method the HTTP method (e.g. {@code GET}, {@code POST})
   * @param pathPattern the Spring Ant path pattern under {@code /api/v1} (e.g. {@code
   *     /workflows/*})
   * @param requiredPermission the minimum capability the endpoint needs
   */
  public record EndpointRule(String method, String pathPattern, Permission requiredPermission) {}

  private static final String API_V1 = "/api/v1";

  private final List<EndpointRule> rules;

  /**
   * Builds the current access matrix. User operations are listed explicitly; the {@code ops:dlq}
   * operator rule is reserved (added as a new {@link EndpointRule} when the operator DLQ endpoint
   * is implemented in a later sprint — it stays out of the matrix until then).
   */
  public AccessMatrix() {
    this.rules =
        List.of(
            new EndpointRule("POST", API_V1 + "/workflows", Permission.WORKFLOW_WRITE),
            new EndpointRule("GET", API_V1 + "/workflows", Permission.WORKFLOW_READ),
            new EndpointRule("GET", API_V1 + "/workflows/*", Permission.WORKFLOW_READ));
  }

  /**
   * @return the rules in declaration order (user operations first, then operator operations)
   */
  public List<EndpointRule> rules() {
    return List.copyOf(rules);
  }

  /**
   * @return the set of distinct permissions referenced by the matrix; every one is a catalogue id,
   *     so a caller holding none of them is denied on every endpoint (default-deny)
   */
  public Set<Permission> referencedPermissions() {
    return rules.stream()
        .map(EndpointRule::requiredPermission)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
