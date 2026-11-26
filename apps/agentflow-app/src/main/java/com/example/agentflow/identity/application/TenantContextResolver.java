package com.example.agentflow.identity.application;

import com.example.agentflow.identity.persistence.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * The single tenant resolver (S04-06, {@code docs/security/tenant-claim.md}; S04-19, {@code
 * docs/security/authentication-authorization.md}).
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
 * parameter or Kafka header (invariant 1 / {@link TenantContext}).
 *
 * <p><b>Existence &amp; status (S04-19).</b> A tenant id from the verified token is trusted for
 * identity, but it is not <em>authorised</em> by that fact alone: it must also name a <em>live</em>
 * tenant in the {@code tenants} table (S03-03, the table {@code identity} owns). The resolver
 * checks the tenant exists and carries the {@code ACTIVE} status before it returns a {@link
 * TenantContext}. Only that exact status is accepted — a disabled, suspended or otherwise
 * non-active tenant is refused, exactly as if the claim were absent. The membership decision (claim
 * vs. Keycloak group) and the verification contract are documented in {@code
 * docs/security/authentication-authorization.md}; the resolver implements both.
 *
 * <p><b>Uniform denial.</b> The four ways the tenant gate can fail — no well-formed {@code
 * tenant_id} claim (no membership), a claim naming a tenant that does not exist, a claim naming a
 * tenant whose status is not {@code ACTIVE} (disabled / not live), or a missing database (the
 * repository absent) — all produce the <b>identical</b> response: a {@code 401 Unauthorized} with
 * the same, tenant-agnostic message. The body never names the tenant, its status or the reason, so
 * a caller cannot tell whether a tenant exists, is disabled or is simply absent — the single S04-09
 * policy applied to tenant resolution. The stored tenant read by an async worker is checked the
 * same way before it is used.
 *
 * <p><b>Fail closed.</b> When the {@link TenantRepository} is absent — the application is booting
 * without a database (S01-02) — the tenant cannot be verified, so the resolver denies every request
 * rather than skipping the check. The app keeps booting; only secured tenant-scoped work is
 * refused. Health/readiness probes are anonymous and never reach the resolver.
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
 * <p><b>HTTP + worker.</b> HTTP resolution reads from the {@code SecurityContext} principal; the
 * async worker resolves the tenant from the stored run's {@code tenant_id} (the source of truth,
 * ADR-002) re-read from PostgreSQL and re-checked against the same status gate — the Kafka
 * envelope's {@code tenantId} is only routing/correlation, never an authorization source. That
 * worker path is used where the run repository exists (S06/ S07); the resolver and the {@link
 * TenantContext} it shares are ready for it.
 */
@Component
public class TenantContextResolver {

  /** The only tenant status the resolver accepts (S04-19) — everything else is refused. */
  public static final String ACTIVE_TENANT_STATUS = "ACTIVE";

  /**
   * The one, tenant-agnostic denial body. Every failure of the tenant gate — no membership, an
   * unknown tenant, a non-ACTIVE tenant, a missing database — renders exactly this answer, so no
   * caller can tell which of those it hit.
   */
  public static final String NO_TENANT_MESSAGE = "no authorized tenant for this request";

  private final TenantRepository tenantRepository;

  /**
   * Builds the resolver. The repository is optional ({@code @Autowired(required = false)}): with no
   * {@code DataSource} the app must still boot (S01-02), and the resolver degrades to deny — it
   * never skips the tenant check.
   *
   * @param tenantRepository the {@code tenants}-table read path; may be {@code null} when no
   *     database is configured
   */
  public TenantContextResolver(@Autowired(required = false) TenantRepository tenantRepository) {
    this.tenantRepository = tenantRepository;
  }

  /**
   * Resolves the tenant for a verified JWT (the HTTP boundary). The {@link Jwt} principal is what
   * Spring Security places in the {@code SecurityContext} for a bearer token, so {@link
   * #resolveFromAuthentication(Authentication)} and this method resolve the tenant identically.
   *
   * @param jwt a verified JWT produced by the OAuth 2.0 Resource Server (S04-03); its claims are
   *     trusted. Never a raw token body or an externally supplied value.
   * @return the {@link TenantContext} scoped to the verified tenant and subject
   * @throws ResponseStatusException {@code 401 Unauthorized} when the token carries no well-formed
   *     {@code tenant_id} claim, when the claimed tenant does not exist, when its status is not
   *     {@code ACTIVE}, or when no database is configured to verify it — the caller cannot be
   *     scoped, so the request is denied rather than defaulted
   */
  public TenantContext resolve(Jwt jwt) {
    UUID tenantId = TenantClaimExtractor.extract(jwt).orElseThrow(() -> unauthorized());
    verifyLiveTenant(tenantId);
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
   *     {@link Jwt}, or when the token's tenant fails the gate (absent claim, unknown tenant,
   *     non-ACTIVE tenant, or no database to verify it)
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
   * @throws ResponseStatusException {@code 401 Unauthorized} when the stored tenant is missing,
   *     does not exist, is not {@code ACTIVE}, or no database is configured to verify it
   */
  public TenantContext resolveFromStoredTenant(UUID tenantId, String actor) {
    if (tenantId == null) {
      throw unauthorized();
    }
    verifyLiveTenant(tenantId);
    return new TenantContext(tenantId, actor);
  }

  /**
   * The S04-19 gate: the claimed/stored tenant must exist and carry the {@code ACTIVE} status. Any
   * other outcome — repository absent (no database), no row, a non-{@code ACTIVE} status — denies
   * with the identical, tenant-agnostic answer.
   */
  private void verifyLiveTenant(UUID tenantId) {
    Optional<String> status =
        tenantRepository == null ? Optional.empty() : tenantRepository.findStatus(tenantId);
    if (status.isEmpty() || !ACTIVE_TENANT_STATUS.equals(status.get())) {
      throw unauthorized();
    }
  }

  private static ResponseStatusException unauthorized() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, NO_TENANT_MESSAGE);
  }

  private static Jwt verifiedPrincipal(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
      throw unauthorized();
    }
    return jwt;
  }
}
