# ADR-007. Tenant isolation by default

- **Status:** Accepted
- **Date:** 2026-10-25
- **Task:** S04-07 (tenant-scoped access; `tenant_id` from `S03-04`, the verified `TenantContext`
  from `S04-05`/`S04-06`)
- **Supersedes:** none. One of the core domain/runtime ADRs (`ADR-005`…`ADR-007`, reserved in the
  plan §3).
- **Superseded by:** none

## Context

AgentFlow is multi-tenant: every document, chunk, workflow, result and audit event can belong to
one of many organizations, and a caller must only see its own. `AGENTS.md` invariant 1 makes this
non-negotiable: "Tenant isolation. Tenant comes only from the verified SecurityContext; a resource
is read by the pair `(tenantId, resourceId)`; another tenant's resource is indistinguishable from a
missing one."

The danger is subtle and common. The `tenantId` in a request body, a query parameter, or a Kafka
header looks just as legitimate as the one in the verified JWT. If a lookup were scoped by a
request-supplied tenant, a caller could read another tenant's data simply by sending a different
`tenantId`. If the tenant filter were a post-filter instead of part of the query, it could be
bypassed. If a foreign resource returned `403`, a caller could probe a guessed UUID and learn that
another tenant owns a resource with that id.

The acceptance criterion (also the plan's) is that integration tests prove a caller cannot read
another tenant's data, and that a foreign resource is indistinguishable from a missing one.

## Decision

**Every domain record carries `tenant_id` where it applies, and every read is scoped by the pair
`((tenant_id, resource_id))`.** The tenant is derived only from the verified security context — the
`TenantContext` (`identity/application/TenantContext`) resolved by `TenantContextResolver` from the
verified OAuth 2.0 JWT (`S04-05`/`S04-06`) — and never from the request body, query, or Kafka
header.

The tenant filter is a predicate **in the SQL itself** (the `tenant_id` column of the `WHERE`
clause — e.g. `workflow_definitions.tenant_id` → `tenants`, a foreign key that enforces it) and
never a post-filter in application code. A lookup for a resource not in the caller's tenant returns
empty, and the application layer maps that empty to `404 not_found` — the same response a missing
resource gets. So a foreign resource is indistinguishable from a missing one, and never a `403`
(the 403/404 masking policy is `docs/security/response-policy.md`, `S04-09`).

For writes, the same pair governs a conditional update (conditional `WHERE` + optimistic
`version`), so a write scoped to the wrong tenant affects zero rows and surfaces as a `409
conflict` — but only for a resource the caller already proved they read; a foreign resource never
reaches the write path.

## Alternatives considered

1. **Trust the `tenantId` from the request.** Con: a caller controls it; sending another tenant's
   id reads their data. The verified-context rule closes this. Rejected.
2. **Filter in application code after the DB returns rows (post-filter).** Con: a bypassable
   filter and a potential cross-tenant leak if a query returns another tenant's row. Rejected; the
   filter must be part of the query.
3. **Return `403` for a foreign resource.** Con: distinguishes foreign from missing, enabling an
   enumeration/leak. Rejected; a foreign resource is `404` (`docs/security/response-policy.md`,
   `S04-09`).
4. **`tenant_id` in every record, tenant from verified context, filter in SQL (chosen).** Matches
   invariant 1, `ADR-002` (PostgreSQL source of truth) and the masking policy.

## Consequences

- **Positive:**
  - A foreign resource is indistinguishable from a missing one — no enumeration leak.
  - The tenant filter is in the query, so it cannot be bypassed by returning rows and filtering
    later.
  - Works with the durable engine (`ADR-002`) and the at-least-once delivery (`ADR-003`): the
    tenant scope travels with the stored state, re-checked against PostgreSQL.
- **Negative / operational:**
  - Every module that owns tenant-scoped data must scope its own reads and writes; a new table
    without a `tenant_id` filter is a hole. Each table's owner (plan §4.2) is responsible.
  - The worker path has no HTTP `SecurityContext`; the tenant comes from the stored run's
    `tenant_id` re-read from PostgreSQL (`S04-06`), never from the Kafka envelope.
- **Follow-ups:**
  - `S04-13` adds the HTTP-boundary security suite, including the mandatory negative test that a
    cross-tenant read is denied (`SecurityFilterChainTest.crossTenantReadIsIndistinguishableFromMissingThroughTheSecuredChain`)
    and the positive that a body-carried `tenant_id` is ignored (`SecurityFilterChainTest.bodyTenantSpoofingIsNotScopedThroughTheSecuredChain`),
    through the live bearer-token chain on the `secured-test` profile.
  - The `identity` module owns the verified `TenantContext` and the `tenant_id` claim contract
    (`docs/security/tenant-claim.md`, `S04-05`).
