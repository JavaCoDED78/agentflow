package com.example.agentflow.identity.application;

import java.util.UUID;

/**
 * The resolved, immutable tenant identity for a single unit of work (S04-06, {@code
 * docs/security/tenant-claim.md}).
 *
 * <p>This is the single type that carries the tenant through the application. It is created in one
 * of two places and <b>never</b> reconstructed from untrusted input:
 *
 * <ul>
 *   <li><b>HTTP boundary.</b> {@link TenantContextResolver#resolve(Jwt)} reads the tenant from the
 *       verified OAuth 2.0 JWT in the {@link org.springframework.security.core.Authentication} (the
 *       Resource Server has already checked its signature, issuer, audience and expiry), so the
 *       tenant is trusted. This is the only place {@code tenantId} may come from for a request.
 *   <li><b>Async worker.</b> the tenant is the {@code tenant_id} of the stored run re-read from
 *       PostgreSQL (the source of truth, ADR-002) and re-checked against the run actually being
 *       processed — the Kafka envelope's {@code tenantId} is only routing/correlation, never an
 *       authorization source (invariant 1). The worker builds the very same {@code TenantContext}
 *       from that value.
 * </ul>
 *
 * <p>It is threaded <b>explicitly</b> as a method argument from the boundary into every application
 * service; it is never stored in a {@code ThreadLocal} or any other shared, per-request state. That
 * is what makes parallel requests from different tenants not mix context: there is no shared
 * mutable context a pooled or virtual thread could leak (invariant 7 / the S04-06 acceptance
 * criterion).
 *
 * <p>Deliberately narrow for S04-06: {@code tenantId} plus the verified {@code actor}. The resolved
 * <em>permission scope</em> ({@code requiredScopes}) is layered on by {@code S04-07} (tenant-scoped
 * access) / {@code S04-08} (least privilege); that is a separate concern and is not part of this
 * value yet.
 */
public record TenantContext(UUID tenantId, String actor) {

  /**
   * @param tenantId the owning tenant, resolved from the verified security context (HTTP) or the
   *     stored run (worker) — never from request input
   * @param actor the verified subject ({@code sub}) of the verified JWT, recorded for the audit
   *     trail; present on every verified token, may be {@code null} only if the token carried none
   */
  public TenantContext {
    if (tenantId == null) {
      throw new IllegalArgumentException(
          "tenantId must not be null — there is no tenant to scope to");
    }
  }
}
