package com.example.agentflow.identity.application;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single tenant resolver (S04-06, {@code docs/security/tenant-claim.md}).
 *
 * <p>Turns a <em>verified</em> security principal into a {@link TenantContext} and is the one place
 * the trusted tenant is pulled out of the {@code SecurityContext} for HTTP requests. It is the
 * concrete realisation of the plan's S04-06 result — "a single tenant resolver" — so every entry
 * point reads the tenant the same way instead of each controller or listener inventing its own
 * rule.
 *
 * <p><b>Single source of the tenant.</b> The tenant comes only from the verified JWT: {@link
 * TenantClaimExtractor} already runs after the OAuth 2.0 Resource Server (S04-03) has validated the
 * token, so its claims are trusted. {@code tenant_id} is never taken from the request body, query
 * parameter or Kafka header (invariant 1 / {@link TenantContext}). A verified token without a
 * well-formed {@code tenant_id} (no membership, or a non-UUID id) is <b>denied</b>, never defaulted
 * to a fallback — {@link #resolve(Jwt)} throws {@code 401} in that case.
 *
 * <p><b>Stateless &rarr; thread-safe.</b> The resolver keeps no per-request state: it is a Spring
 * singleton whose methods take the verified principal as an argument and return a fresh, immutable
 * {@link TenantContext}. That is exactly why <i>parallel requests from different tenants do not mix
 * context</i> (the S04-06 acceptance criterion) — a pooled or, on Java 25, a virtual thread that
 * reuses another thread's stack cannot leak one tenant's identity into another request, because
 * nothing is stored between calls. This is the deliberate alternative to a {@code ThreadLocal}:
 * {@code ThreadLocal} correlation is only safe if it is cleared on every path (including async
 * boundaries), which is easy to get wrong; explicit resolution avoids the trap entirely.
 *
 * <p><b>HTTP only.</b> This resolver reads from the {@code SecurityContext} principal, so it serves
 * the web role. Async workers (the workflow/ingestion roles) have no HTTP {@code SecurityContext};
 * they resolve the tenant by reading {@code tenant_id} from the stored run in PostgreSQL and
 * passing that into {@link #resolveFromStoredTenant(UUID, String)} — the same {@link
 * TenantContext}, just with a trusted value that was re-checked against the source of truth
 * (ADR-002) rather than taken from the Kafka envelope. That worker path is implemented where the
 * run repository exists (S06/ S07); the resolver and {@link TenantContext} it shares are ready for
 * it.
 */
@Component
public class TenantContextResolver {

  /**
   * Resolves the tenant for a verified JWT (the HTTP boundary). The {@link Jwt} principal is what
   * Spring Security places in the {@code SecurityContext} for a bearer token, so {@link
   * #resolveFromAuthentication(Authentication)} and this method resolve the tenant identically.
   *
   * @param jwt a verified JWT produced by the OAuth 2.0 Resource Server (S04-03); its claims are
   *     trusted. Never a raw token body or an externally supplied value.
   * @return the {@link TenantContext} scoped to the verified tenant and subject
   * @throws ResponseStatusException {@code 401 Unauthorized} when the token carries no well-formed
   *     {@code tenant_id} claim (no membership, or a malformed id): the caller cannot be scoped, so
   *     the request is denied rather than defaulted
   */
  public TenantContext resolve(Jwt jwt) {
    UUID tenantId =
        TenantClaimExtractor.extract(jwt)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "no authorized tenant for this request: the verified token carries no tenant membership"));
    return new TenantContext(tenantId, jwt.getSubject());
  }

  /**
   * Resolves the tenant from a Spring {@code Authentication} (its principal is the verified {@link
   * Jwt}). This is the form a controller reads off the {@code SecurityContext}, so the tenant is
   * resolved from the trusted security context and passed explicitly to the application service.
   *
   * @param authentication the current {@code Authentication}; the principal must be a verified
   *     {@link Jwt}
   * @return the {@link TenantContext} scoped to the verified tenant and subject
   * @throws ResponseStatusException {@code 401 Unauthorized} when the principal is not a verified
   *     {@link Jwt}, or when that token carries no well-formed {@code tenant_id} claim
   */
  public TenantContext resolveFromAuthentication(Authentication authentication) {
    Jwt jwt = verifiedPrincipal(authentication);
    return resolve(jwt);
  }

  /**
   * Builds the {@link TenantContext} for an async worker from a tenant that was read from
   * PostgreSQL (the stored run's {@code tenant_id}), not from the Kafka envelope. The envelope's
   * {@code tenantId} is only routing/correlation; the worker re-checks the run's real tenant
   * against the source of truth (ADR-002) and passes it in here, so an attacker-controlled header
   * value cannot change the scope (invariant 1).
   *
   * <p>Called from the worker role, which owns the run repository (S06/S07); the HTTP resolver
   * never uses this overload.
   *
   * @param tenantId the tenant of the run the worker is processing, as stored in PostgreSQL
   * @param actor the actor/subject of the machine identity (the worker's service principal)
   * @return the {@link TenantContext} scoped to the re-checked tenant
   * @throws ResponseStatusException {@code 401 Unauthorized} when the stored tenant is missing or
   *     not a UUID — the run is gone or unscoped, so there is nothing to process
   */
  public TenantContext resolveFromStoredTenant(UUID tenantId, String actor) {
    if (tenantId == null) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          "the processed run has no stored tenant — nothing to scope this worker to");
    }
    return new TenantContext(tenantId, actor);
  }

  private static Jwt verifiedPrincipal(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          "no verified principal in the security context — the request is not authenticated");
    }
    return jwt;
  }
}
