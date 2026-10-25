# Trusted tenant claim (S04-05)

> Owner: the `identity` module. Implementation: `com.example.agentflow.identity.application.TenantClaimExtractor`.
> Governing invariant: `ADR-007` (tenant isolation by default).

The **tenant** is the unit of data separation. S04-05 decides which value carries the tenant in an
authenticated request and, critically, **which sources are not allowed to**. This contract is the
"S04-05 result — tenant claim contract" the plan asks for; `S04-06` builds the `TenantContext`
abstraction that threads this value through the application, and `S04-13` proves the behaviour with
the security regression suite.

## The decision

1. **Claim.** The tenant id is carried in the Keycloak `tenant_id` claim — a mapped claim present
   **only** when the authenticated user has a tenant membership. It is a `UUID`, matching the
   `tenants.id` column and the `tenant_id` fields in [`docs/domain-glossary.md`](../domain-glossary.md).
2. **Trust.** The claim is trusted **implicitly**: it lives inside an OAuth 2.0 access token whose
   signature, issuer, audience and expiry the Resource Server validates (`S04-03`) before any
   application code sees it. `TenantClaimExtractor` reads the claim from that already-verified
   `Jwt`; it never re-derives, re-signs, or independently verifies the value.
3. **No membership.** A verified token without a `tenant_id` claim — the caller has no tenant
   membership — yields **no tenant**. The request is **denied**; it is never defaulted to a fallback
   tenant and never assigned the first tenant the caller happens to name.
4. **Never from input.** `tenant_id` is **never** taken from the request body, query parameter, or
   Kafka header (invariant 1 / `ADR-007`). Those are untrusted. The only accepted source is the
   verified security context.

## How `tenant_id` reaches the token

`tenant_id` is not a standard OIDC claim. Keycloak injects it with a **Mapped Claim** bound to
tenant membership (a realm/client role per tenant, or an org membership), so a user who is not a
member of any tenant simply gets no `tenant_id` claim. The realm import is
[`infra/compose/keycloak/realm-agentflow.json`](../../infra/compose/keycloak/realm-agentflow.json)
(currently maps `preferred_username`, `email`, `name`, and the `tenant:admin` role); the
`tenant_id` mapper is added there so the realm stays declarative and reproducible.

## Behaviour table

| Verified JWT | `tenant_id` claim | Result |
|---|---|---|
| member of a tenant | present, valid UUID | tenant resolved, request continues scoped to that tenant |
| member of a tenant | present, not a UUID | **deny** (malformed — treated as absent) |
| not a member of any tenant | absent | **deny** — no authorized tenant |

## Relation to the other Sprint 4 tasks

- **`S04-03` Resource Server** — verifies the token the claim rides on (signature/issuer/audience/expiry).
- **`S04-06` `TenantContext`** — reads the `Jwt` from the `SecurityContext`, extracts the tenant with
  `TenantClaimExtractor`, passes it explicitly through application services, and for async workers
  re-checks the tenant of the stored run/event against PostgreSQL.
- **`S04-07` tenant-scoped access** — resolves resources by `(tenant_id, resource_id)`; another
  tenant's resource is indistinguishable from a missing one.
- **`S04-10` remove the local auth bypass** — the temporary `${agentflow.local.tenant-id}` default
  (S03-13/S03-14 local-skeleton) must be deleted; it is exactly the "tenant not from a non-verified
  source" rule the plan defers to this sprint.
- **`S04-13` security regression** — `body tenant spoofing` and `cross-tenant read/write` are the
  acceptance proof for this contract: substituting another tenant's id does not change the access
  scope.

## Acceptance

> Substituting another tenant's id does not change the access scope.

Holds by construction: the tenant comes only from the verified `tenant_id` claim, so a caller-supplied
`tenant_id` in the body/query/headers is never consulted. Proven by the `S04-13` HTTP-boundary
suite; the extraction primitive is unit-tested in `TenantClaimExtractorTest`.
