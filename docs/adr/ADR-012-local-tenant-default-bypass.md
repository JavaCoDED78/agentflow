# ADR-012. The local-skeleton tenant default is a documented, removable tenant-isolation bypass

- **Status:** Accepted
- **Date:** 2026-11-02
- **Task:** S03-13 / S03-14 (local-skeleton tenant default for the `workflowdefinition` REST skeleton),
  re-stated in S04-05 and tracked as a follow-up through S04-10
- **Supersedes:** none. A *temporary* relaxation of invariant 1, introduced with the early REST
  skeleton and documented here so the bypass is visible, scoped, and its removal is mandated.
- **Superseded by:** none (yet). The successor is the ADR for the new `workflowdefinition` →
  `identity` module edge that threads the tenant from the verified `TenantContext`, after which this
  default is removed.

## Context

AgentFlow is multi-tenant and invariant 1 of `AGENTS.md` is non-negotiable: *"Tenant comes only from
the verified SecurityContext (HTTP) or from the stored run/event re-checked against PostgreSQL
(worker). Never from body, query or Kafka headers."* `ADR-007` records the `(tenant_id, resource_id)`
rule that realises it.

When the `workflowdefinition` REST skeleton was added (S03-13 create, S03-14 list/get), the
`workflowdefinition` → `identity` dependency edge did not yet exist, so the controllers could not
resolve the tenant from the verified `TenantContext` (`identity/application/TenantContext`, resolved
by `TenantContextResolver` — `S04-05`/`S04-06`). Rather than block the skeleton, the tenant was read
from application configuration:

```java
@Value("${agentflow.local.tenant-id:00000000-0000-0000-0000-000000000000}") String tenantIdRaw
```

in `WorkflowDefinitionController` (`apps/agentflow-app/src/main/java/com/example/agentflow/workflowdefinition/web/WorkflowDefinitionController.java`,
the constructor argument at the `@Value`), and identically in
`WorkflowRunController` (`apps/agentflow-app/src/main/java/com/example/agentflow/workflowexecution/web/WorkflowRunController.java`).
The default is the zero UUID (`00000000-0000-0000-0000-000000000000`), with `actor` defaulting to
`local`.

This is a **tenant-isolation bypass**. It is not the same as a body/query `tenant_id`: the tenant is
never derived from the request, so a caller still cannot spoof another tenant through the request
body (`S04-13` proves this). The deviation from invariant 1 is narrower but real — the tenant is
resolved from **configuration**, not from the *verified* security context, so it does not yet satisfy
the invariant. It is acceptable as a local/dev bootstrap measure only while it is (a) explicitly
documented, (b) scoped to local/dev, and (c) removed the moment the verified-context path exists.
The permissive `test`-profile mock-auth that S04-10 removed was a *separate* bypass; this local
default was deliberately left in place because it carries its own dependency (the `workflowdefinition`
→ `identity` edge, itself an ADR).

## Decision

The `${agentflow.local.tenant-id}` default is a **documented, temporary tenant-isolation bypass**:

1. It is the *only* tenant source for the `workflowdefinition` and `workflowexecution` REST APIs
   until the verified-context path exists, and it is intentionally the zero UUID so a misconfigured
   local stack fails closed on an unknown tenant rather than defaulting to a real one.
2. It is **local/dev-only by design and intent**. Production must never rely on it: the tenant must
   come from the verified `TenantContext` (invariant 1 / `ADR-007`).
3. Its removal is **mandated** as soon as the `workflowdefinition` → `identity` edge is added, so
   both controllers resolve the tenant from the verified `TenantContext` and the `@Value` defaults
   are deleted. That new edge is a new `module → module` dependency and therefore needs its own ADR;
   when it lands it supersedes this record.

Until the edge lands, the bypass is audited by the architecture gate: the zero-UUID default must not
be shipped to a production profile, and any new `/api/v1` entry point must resolve the tenant from
`identity`, not from another `@Value` default.

## Alternatives considered

1. **Resolve the tenant from the verified `TenantContext` from the start.** Correct in principle, but
   the `workflowdefinition` → `identity` edge did not exist and is a new module dependency that
   requires an ADR (`AGENTS.md`, module-map edge table). Blocked the skeleton on an ADR that did not
   yet exist. Rejected for the bootstrap step; it is the chosen *remediation*.
2. **Ship the local default in production.** Con: the tenant is config-derived, not verified — a
   direct violation of invariant 1. Rejected; the default is local/dev-only.
3. **Document the bypass and mandate removal once the verified-context edge exists (chosen).** Turns
   an implicit hack into a visible, reviewable, expiring decision, so it cannot silently outlive its
   purpose or ship to production.

## Consequences

- **Positive:**
  - The early REST skeleton could be built and tested without the `identity` dependency, without
    weakening the *read* path (reads are still `(tenant_id, id)`-scoped in SQL, `ADR-007`).
  - The bypass is explicit and quotable — the controller Javadoc names it as a follow-up — so a
    future reader understands *why* the tenant comes from config and that it must change.
  - Removal is a bounded, well-defined follow-up rather than an open-ended debt.
- **Negative / operational:**
  - While present, the tenant is not derived from the trusted security context; the isolation that
    protects the app is the SQL `tenant_id` predicate, not the verified-JWT rule invariant 1 demands.
    It must stay local/dev-only and be excluded from any production/local profile that serves real
    tenants.
  - Every new `/api/v1` entry point is a place this pattern could be (re)introduced by mistake; the
    architecture gate and the PR architecture-review checklist ("ADR required? / tenant scope") are
    the guard.
- **Follow-ups:**
  - Add the `workflowdefinition` → `identity` edge (its own ADR): resolve the tenant in
    `WorkflowDefinitionController` and `WorkflowRunController` from the verified `TenantContext` and
    delete the `${agentflow.local.tenant-id}` / `${agentflow.local.actor}` defaults.
  - Remove this record from *Current architectural constraints* and mark it `Superseded` in
    `docs/adr/README.md` when the edge lands; keep the file as the historical reason the bypass
    existed.
