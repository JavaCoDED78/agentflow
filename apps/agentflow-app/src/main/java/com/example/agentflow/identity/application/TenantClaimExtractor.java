package com.example.agentflow.identity.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the trusted tenant identity out of a verified OAuth 2.0 access token (S04-05, {@code
 * docs/security/tenant-claim.md}).
 *
 * <p>This is the single primitive that turns a <em>verified</em> JWT's tenant claim into a tenant
 * id. It is the building block the {@code TenantContext} (S04-06) wires into the {@code
 * SecurityContext} principal: by the time a {@link Jwt} reaches {@link #extract(Jwt)} the OAuth 2.0
 * Resource Server (S04-03) has already validated its signature, issuer, audience and expiry, so the
 * claims it carries are <b>trusted</b> — this class never treats a tenant supplied outside the
 * verified token (request body, query parameter, Kafka header, application default) as an
 * authorization source (invariant 1).
 *
 * <p>The contract (the decision this class implements):
 *
 * <ul>
 *   <li><b>Claim.</b> The tenant id lives in the {@code tenant_id} Keycloak claim — a mapped claim
 *       present only when the authenticated user has a tenant membership (see {@code
 *       infra/compose/keycloak/realm-agentflow.json}).
 *   <li><b>Signature.</b> It is trusted implicitly: {@code tenant_id} is part of a JWT whose
 *       signature the Resource Server verifies before this class runs. We never re-derive or
 *       re-sign it.
 *   <li><b>No membership.</b> A verified token without a {@code tenant_id} claim (the caller has no
 *       tenant membership) yields {@link Optional#empty()} — the caller cannot be scoped to any
 *       tenant, so the request is denied rather than defaulted to a tenant.
 *   <li><b>Well-formed only.</b> A {@code tenant_id} that is not a UUID is treated as absent
 *       (deny), never as an error that leaks parsing internals.
 * </ul>
 *
 * <p>Deliberately narrow: this class answers only <em>“what tenant does this verified token
 * declare?”</em> It does not thread the tenant through application services, bridge it from the
 * {@code SecurityContext}, or re-check it against PostgreSQL for async workers — those are the
 * {@code TenantContext} (S04-06) responsibilities.
 *
 * <p>It is a stateless primitive, not a Spring bean: S04-06 injects and calls it, it is not wired
 * by reflection.
 */
public final class TenantClaimExtractor {

  /** The Keycloak claim that carries the trusted tenant id (S04-05). */
  public static final String TENANT_CLAIM = "tenant_id";

  private TenantClaimExtractor() {}

  /**
   * Extracts the tenant id from a verified JWT's {@code tenant_id} claim.
   *
   * @param verifiedJwt a JWT verified by the OAuth 2.0 Resource Server (S04-03); its claims are
   *     trusted. Never a raw token body or an externally supplied value.
   * @return the tenant id, or {@link Optional#empty()} when the token carries no well-formed {@code
   *     tenant_id} claim (no membership, or a malformed id) — the caller must deny in that case
   */
  public static Optional<UUID> extract(Jwt verifiedJwt) {
    Object claim = verifiedJwt.getClaims().get(TENANT_CLAIM);
    if (!(claim instanceof String tenantId)) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(tenantId));
    } catch (IllegalArgumentException invalid) {
      // A tenant id that is not a UUID is not a valid tenant: deny, do not leak the parse error.
      return Optional.empty();
    }
  }
}
