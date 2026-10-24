package com.example.agentflow.identity.application;

import com.example.agentflow.identity.domain.Permission;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Central claims-to-authorities mapping (S04-04, {@code docs/security/permissions.md}).
 *
 * <p>The single place that turns the role claims in a verified JWT into Spring Security {@link
 * GrantedAuthority authorities} the endpoint matrix can check. Spring Security wires this bean into
 * the {@link org.springframework.security.oauth2.jwt.JwtAuthenticationConverter}, so every entry
 * point — HTTP controllers, Kafka listeners, scheduled jobs — derives the principal's authorities
 * from the same catalogue ({@link Permission}); controllers never parse the JWT themselves.
 *
 * <p>The mapping is deny-by-default and idempotent:
 *
 * <ul>
 *   <li>a role claim is granted <b>only</b> if its value is one of the catalogue ids in {@link
 *       Permission#ids()}; an unknown role claim is dropped, so an issued role outside the
 *       catalogue cannot silently grant a capability;
 *   <li>the same id is always mapped to the same authority string ({@link Permission#id()}), so the
 *       endpoint matrix and the Keycloak realm agree on exactly what a permission grants;
 *   <li>duplicate role claims collapse to a single authority.
 * </ul>
 *
 * <p>Supported Keycloak role claim shapes:
 *
 * <ul>
 *   <li><b>Client-scoped roles</b> — {@code resource_access} is a map of {@code clientId -> {roles:
 *       [ ... ]}}; the inner {@code roles} array is read for <em>every</em> client, not a fixed
 *       name, so it works regardless of which client the roles were assigned to;
 *   <li><b>Realm roles</b> — a flat {@code roles} claim (a single string or a list) at the top of
 *       the token.
 * </ul>
 *
 * <p>The token is only read after it has been verified by the OAuth 2.0 Resource Server ({@link
 * Jwt} is produced by the decoded+validated JWT, so its claims are trusted). This never derives the
 * <b>tenant</b> — tenant comes from the trusted security context only (see S04-05/06); it only
 * answers "what capability did the verified caller hold?".
 */
@Component
public class ClaimsToAuthoritiesMapper {

  /**
   * Maps the verified JWT's role claims into authorities, validated against the permission
   * catalogue.
   *
   * @param jwt a verified JWT produced by the Resource Server (its claims are trusted); never the
   *     raw body or external input
   * @return authorities whose values are catalogue ids, or an empty collection when the token holds
   *     no recognised roles
   */
  public Collection<GrantedAuthority> map(Jwt jwt) {
    Set<Permission> permissions = new LinkedHashSet<>();
    for (String roleClaim : roleClaims(jwt)) {
      // Deny by default: only grant what the catalogue recognises.
      Optional<Permission> permission = Permission.fromId(roleClaim);
      if (permission.isPresent()) {
        permissions.add(permission.get());
      }
    }
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    for (Permission permission : permissions) {
      authorities.add(new SimpleGrantedAuthority(permission.id()));
    }
    return authorities;
  }

  /**
   * Collects the raw role claims (before catalogue validation) from a JWT.
   *
   * @param jwt a verified JWT
   * @return the multiset of role strings, de-duplicated and in encounter order
   */
  private static Set<String> roleClaims(Jwt jwt) {
    Set<String> roles = new LinkedHashSet<>();
    for (Map.Entry<String, Object> entry : jwt.getClaims().entrySet()) {
      Object value = entry.getValue();
      // resource_access / realm-access: a JSON object (decoded as a Map by the JWT decoder).
      if (value instanceof Map<?, ?> section) {
        addRolesFromSection(roles, section);
      } else if ("roles".equals(entry.getKey())) {
        // realm-level roles as a top-level claim (a JSON array).
        addRoleList(roles, value);
      }
    }
    return roles;
  }

  /**
   * Extracts role strings from a decoded role-claim object. Handles both Keycloak shapes: realm
   * roles ({@code {roles: [...]}}) and client-scoped roles ({@code {client: {roles: [...]}}}, where
   * the {@code roles} list lives under each client entry rather than directly on the section).
   *
   * @param roles accumulator for the extracted role strings (de-duplicated)
   * @param section the decoded {@code resource_access} or {@code realm-access} object
   */
  private static void addRolesFromSection(Set<String> roles, Map<?, ?> section) {
    Object directRoles = section.get("roles");
    if (directRoles != null) {
      // realm-access: { roles: [...] }
      addRoleList(roles, directRoles);
      return;
    }
    // resource_access: { "<client>": { roles: [...] } }
    for (Object client : section.values()) {
      if (client instanceof Map<?, ?> clientRoles) {
        addRoleList(roles, clientRoles.get("roles"));
      }
    }
  }

  /**
   * Adds the strings in a claim value to the accumulator. The value may be a single role string or
   * a JSON array of roles. Gson (which backs the JWT decoder) decodes arrays as an {@link Iterable}
   * ({@code JsonArray}) rather than a {@link java.util.List}, so the iterable branch covers both.
   *
   * @param roles accumulator for the extracted role strings (de-duplicated)
   * @param claim a role string or the decoded roles array
   */
  private static void addRoleList(Set<String> roles, Object claim) {
    if (claim instanceof String s) {
      roles.add(s);
    } else if (claim instanceof Iterable<?> items) {
      for (Object item : items) {
        if (item != null) {
          roles.add(item.toString());
        }
      }
    }
  }
}
