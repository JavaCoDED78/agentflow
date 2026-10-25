# Security response policy (S04-09)

> **Owner:** `common` (cross-cutting HTTP responses) + `identity`/`workflowdefinition` (tenant
> scope). Implementation: `com.example.agentflow.common.ErrorClass`, the global
> `GlobalExceptionHandler`, and the tenant-scoped `(tenant_id, resource_id)` lookups in each module.

This policy is the single answer to one question: **what does a caller see when a resource does
not belong to them?** The rule is that a resource that exists but belongs to another tenant is
*indistinguishable* from a resource that does not exist — the response is always `404`
`not_found`, never `403` and never a different body. This closes the "can I tell another tenant's
resource exists?" information leak (invariant 1 / `ADR-007`).

---

## The three identity-related responses

| Response | Meaning | Who can trigger it |
|---|---|---|
| `401` | The caller is **unknown** — missing, invalid, expired, or wrongly-issued credential. | Anyone without a verified token. |
| `403` | The caller is **known but lacks the capability** for the *action* (missing `scope`, tool policy denial). | A verified caller calling an endpoint they are not permitted to use. |
| `404` | The *resource* is **not present in the caller's tenant** — this **includes another tenant's resource**, which is masked as missing. | Any verified caller that has the capability but requests a resource that is not `(tenant_id, resource_id)`-scoped to them. |

The `tenant_id` comes only from the verified security context (`ADR-007`), never from the request
body, query, or Kafka header — so spoofing `tenantId` changes nothing and cannot turn a `404`
into a `403`.

---

## Why `404`, not `403`, for a foreign resource

`403 Forbidden` and `404 Not Found` are **different answers**. If a foreign tenant's resource
answered `403` (`authorization_failed`) while a missing one answered `404` (`not_found`), a caller
could probe a guessed UUID and learn whether another tenant owns a resource with that id — a
directory/enumeration leak.

So a foreign resource is **masked as missing**:

- The tenant filter is a predicate **in the SQL itself** (the `tenant_id` column of the
  `WHERE` clause), never a post-filter in application code (invariant 1 / plan §8.2).
- A lookup for a resource not in the caller's tenant returns empty, and the application layer
  maps empty to `404 not_found` — the same path a genuinely missing resource takes.
- The global error handler (`S03-11`) renders **every** `404` as the permanent `not_found`
  problem, so the status, the `error_class`/`code`, and the `retryable` flag are identical for
  foreign and missing. The only difference in a `404` body is the `instance` (the request URI),
  which is the caller's own input and reveals nothing.

This is the S04-09 acceptance criterion: **you cannot determine the existence of another tenant's
resource by the difference in errors.**

---

## What is *not* masked — and why that is safe

`403` still exists, but it signals a **capability** problem, never a **resource** problem:

- `403` is raised by the security layer (`AuthorityAuthorizationManager`, the endpoint access
  matrix, `S04-08`) **before** the resource is ever looked up, when the caller lacks the
  `scope` for the *action*. It tells the caller "you may not do this," not "a resource exists
  that you cannot see."
- `tool_policy_denial` (`403` · `tool_denied`) is a tool-layer policy decision (unknown tool,
  disallowed risk class, missing human approval) — again about the action/tool, not about a
  resource's ownership.

The two never collide in a way that leaks existence: a caller with the `workflow:read` scope that
requests a foreign workflow gets `404` (the capability check passes; the tenant-scoped read then
misses); a caller without `workflow:read` gets `403` (the capability check fails before any read).

---

## The write path does not leak either

A conditional write (`updateMetadata`) takes a `current` object that **was read by a tenant-scoped
lookup** (so it already proves ownership), then re-checks `tenant_id`, status and the optimistic
`version` in the `WHERE` clause. A zero-rows outcome — missing, wrong tenant, wrong status, or
stale version — is mapped to `409 conflict`.

This never leaks a foreign resource because the write can only start from a `current` that the
tenant-scoped read already returned, and that read returns `404` for a foreign id. A caller can
never reach the `409` path with a resource they do not own. (The `409` is a same-tenant stale
version, which does not reveal any other tenant's resource.)

---

## Application

- **`common` owns the error taxonomy** (`ErrorClass`); the global handler renders every `404` as
  `not_found` and every capability `403` as `authorization`.
- **Each module owns its tenant scope.** Reads are `(tenant_id, resource_id)`-filtered in SQL; a
  foreign id resolves to the same `404` a missing id does.
- **The response is deterministic.** The status, `error_class`/`code`, and `retryable` are the
  same for every foreign-or-missing resource, so no probe distinguishes them.

---

## Related

- [`error-taxonomy.md`](../architecture/error-taxonomy.md) — the canonical error classes, HTTP
  mapping and retry decision (`S02-10`); the `401/403/404` behaviour is this policy's HTTP
  realisation.
- [`permissions.md`](./permissions.md) — the permission catalogue and endpoint access matrix that
  decide the `403` (capability) side; tenant scope is orthogonal and stronger (the resource-side
  `404`).
- **`ADR-007`** — tenant isolation by default; the trust anchor a resource lookup is scoped to.
