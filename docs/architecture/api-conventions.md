# API and persistence conventions — developer reference — AgentFlow

> **Status:** Accepted
> **Owner:** engineering
> **Task:** `S03-17`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (Sprint 3, `S03-06`…`S03-16`) ·
> `contracts/openapi/openapi.json` (the contract, generated deterministically) ·
> `docs/architecture/error-taxonomy.md`, `engineering-conventions.md`,
> `database-conventions.md`, `database-migrations.md` · `docs/adr/ADR-008` (Flyway) and
> `ADR-009` (Spring Data JDBC) · root `AGENTS.md` (invariants 1, 11)
> **Purpose:** a single, runnable reference for the `/api/v1` REST API and the
> PostgreSQL persistence it reads from. It explains pagination, RFC 9457 Problem
> Details, optimistic locking, tenant-scoped lookup and how to change the OpenAPI
> contract — with anonymized examples that match the committed contract.

---

## Scope

This reference documents the API and persistence that exist in **Sprint 3**. The
workflow-definition endpoints (`S03-13`, `S03-14`), the global error handler
(`S03-11`), the OpenAPI contract (`S03-12`), pagination and tenant scope
(`S03-14`), optimistic locking (`S03-10`), and the Flyway migration setup
(`S03-01`…`S03-07`) are all described here. Later sprints add endpoints, an OIDC
resource server (`S04`) and more tables; this document tracks the current state
and points to the sources that describe the rest.

The workflow-definition API is served by one controller
(`WorkflowDefinitionController`, `com.example.agentflow.workflowdefinition.web`),
whose public use cases live in `WorkflowDefinitionService`
(`workflowdefinition.application`) and whose tenant-scoped store is the
`WorkflowDefinitionRepository` (`workflowdefinition.persistence`). Other modules
reach this API only through the HTTP contract — never by importing the controller
(`module-api.md`).

---

## Base contract

- **Base path.** Every endpoint is served under `/api/v1`.
- **Authentication.** Requests carry an OIDC access token from Keycloak as
  `Authorization: Bearer <jwt>` (`bearerAuth` security scheme, `bearerFormat: JWT`).
  The issuer and audience are established by the OIDC resource server in
  **S04**. In Sprint 3 the resource server is not yet wired, so `bearerAuth`
  stands in for it.
- **Tenant is not client-controlled.** The tenant comes only from a trusted
  context, never from the request body, query or headers — invariant 1. In this
  sprint the tenant is read from application configuration
  (`agentflow.local.tenant-id`, defaulting to the zero UUID `00000000-...`); this
  is a temporary local-skeleton bypass that is removed in **S04-10**, when the
  tenant comes from the verified security context.
- **Content type.** JSON (`application/json`), `camelCase` API fields.

---

## Endpoints

The workflow-definition API is the only `/api/v1` surface today:

| Method + path | Purpose | Success | Client errors |
|---|---|---|---|
| `POST /api/v1/workflows` | Build a DRAFT definition | `201 Created`, `WorkflowDefinitionDto` | `400 validation` — a blank `name` |
| `GET /api/v1/workflows` | List one tenant's definitions (paginated) | `200 OK`, `WorkflowDefinitionPageDto` | `400 validation` — `page`/`size` out of range; `503` — no database |
| `GET /api/v1/workflows/{workflowId}` | Get one definition by `(tenant_id, id)` | `200 OK`, `WorkflowDefinitionDto` | `400` — non-UUID path value; `404 not_found` — missing or foreign tenant |

> The committed OpenAPI baseline records the `POST create` success under the
> generic success response; the controller returns `201 Created` at runtime (the
> `WorkflowDefinitionDto` is identical). See *Changing the OpenAPI contract*.

### Pagination (`GET`)

`page` and `size` are zero-based integer query parameters. `page` defaults to
`0`; `size` defaults to `20` and is clamped up to a configured maximum
(`MAX_PAGE_SIZE = 100`) by the service, so one call cannot pull the whole
collection. A negative page or a non-positive size is a `400 validation`.

The list is ordered by a *total* order — `created_at DESC, id DESC` (the
`workflow_definitions_tenant_created_at_idx` backs `created_at`, `id` is the
unique tie-breaker) — so every page is stable and pages do not overlap when rows
are inserted concurrently.

The response is `WorkflowDefinitionPageDto`: `items[]`, `page`, `size`,
`totalElements`, `totalPages`, `hasNext`, `hasPrevious`.

### Problem Details (`S03-11`)

Every error is rendered by the cross-cutting `GlobalExceptionHandler`
(`common.error`) as a stable RFC 9457 envelope — never a stack trace or an
internals leak:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "error_class": "validation",
  "detail": "A validation error occurred",
  "instance": "/api/v1/workflows",
  "correlation_id": "3fa85f64-5717-4562-b3fc-2c663009d669",
  "retryable": false
}
```

`error_class` is the identifier shared by the handler and the taxonomy
(`error-taxonomy.md`); `correlation_id` is the trace id when tracing is wired
(`S13`) and a fresh UUID otherwise. The status → `error_class` mapping that
applies to these endpoints:

| Status | `error_class` | When |
|---|---|---|
| `400` | `validation` | blank/invalid field, non-UUID path value, malformed JSON |
| `404` | `not_found` | missing id, foreign-tenant id, or a route that matched nothing |
| `409` | `conflict` | stale optimistic-lock version or a duplicate `(tenant_id, name)` |
| `500` | `internal` | catch-all; the raw cause goes to the structured log, not the client |

### Optimistic locking (`S03-10`)

The mutable `workflow_definitions` row carries an application-managed
`version INTEGER NOT NULL DEFAULT 0` (migration `V3_3_4`). It is kept distinct
from the monotonic business `version` that addresses an immutable
`workflow_definition_versions` row. A metadata update is a conditional update
(`AND version = :expectedVersion`) that bumps `version = version + 1` in the same
statement; a zero-rows outcome (stale version, cross-tenant, wrong status, or
missing) is a `409 conflict`. Two writers cannot silently overwrite each other.

### Tenant-scoped lookup (invariant 1)

`GET /api/v1/workflows/{workflowId}` reads the resource by the pair
`(tenant_id, id)`; the `tenant_id` is filtered in the SQL itself, not applied as
a post-filter. A foreign tenant's definition is indistinguishable from a missing
one: it is reported as a permanent `404 not_found`, never a `403`, so the
existence of another tenant's resource cannot be probed through the error
message (`404`/`403` policy).

---

## Changing the OpenAPI contract

The contract is generated **from the running application** by
`./gradlew generateOpenApiContract` (the `OpenApiContractTool` boots on the
default profile — no database required — waits for the live
`/api/v1/v3/api-docs` and writes a *normalized* copy: the volatile ephemeral-port
`servers` entry is dropped, so the committed baseline is byte-for-byte
reproducible).

**Never edit `contracts/openapi/openapi.json` by hand.** Regenerate it instead;
the generation order is stable, so the committed file is the source of truth,
not a merge target.

The contract gate (`OpenApiContractCheckTest`, run by `./gradlew check`)
compares the baseline against the freshly generated spec and fails the build on
any *breaking* change: a removed path or HTTP method, a removed required
property, a property `type` change, or a removed `enum` value. Compatible
changes (added path/method, added property, added response) are reported but do
not fail.

When a change is intended — including an intentional breaking one — approve it
deliberately rather than letting the gate fail silently:

1. Regenerate the baseline: `./gradlew generateOpenApiContract`.
2. Review the diff of `contracts/openapi/openapi.json`.
3. Record the change in `CHANGELOG.md` (`Unreleased` → `Added`/`Changed`) and an
   ADR if the contract shape changed in a way the team should remember.
4. Open the PR; the gate is green on the refreshed baseline.

`contracts/openapi/README.md` documents this in full.

---

## Examples (anonymized)

> Values below are placeholders. Never put real tenant ids, secrets or data in a
> request or an example.

### Create a DRAFT definition

```bash
curl -i --request POST 'http://localhost:8080/api/v1/workflows' \
  --header 'Authorization: Bearer <jwt>' \
  --header 'Content-Type: application/json' \
  --data '{
    "name": "support-triage",
    "description": "Route inbound tickets by category."
  }'
```

`201 Created`:

```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "tenantId": "00000000-0000-0000-0000-000000000000",
  "name": "support-triage",
  "description": "Route inbound tickets by category.",
  "status": "DRAFT",
  "version": 0,
  "createdBy": "local",
  "createdAt": "2026-10-23T10:00:00Z"
}
```

A blank `name` fails bean validation and is rendered as `400 validation`
Problem Details (see above).

### List one tenant's definitions

```bash
curl -i --request GET \
  'http://localhost:8080/api/v1/workflows?page=0&size=20' \
  --header 'Authorization: Bearer <jwt>'
```

`200 OK`:

```json
{
  "items": [
    {
      "id": "123e4567-e89b-12d3-a456-426614174000",
      "tenantId": "00000000-0000-0000-0000-000000000000",
      "name": "support-triage",
      "description": "Route inbound tickets by category.",
      "status": "DRAFT",
      "version": 0,
      "createdBy": "local",
      "createdAt": "2026-10-23T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

### Get one definition

```bash
curl -i --request GET \
  'http://localhost:8080/api/v1/workflows/123e4567-e89b-12d3-a456-426614174000' \
  --header 'Authorization: Bearer <jwt>'
```

A foreign-tenant id returns `404 not_found`:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "error_class": "not_found",
  "detail": "The requested resource was not found",
  "instance": "/api/v1/workflows/123e4567-e89b-12d3-a456-426614174000",
  "correlation_id": "3fa85f64-5717-4562-b3fc-2c663009d669",
  "retryable": false
}
```

---

## Persistence (PostgreSQL)

The API reads and writes PostgreSQL through one persistence approach — Spring Data JDBC
with explicit, tenant-filtered SQL via `JdbcTemplate` (`ADR-009`). The workflow-definition
table (`workflow_definitions`) is the `workflowdefinition`-owned tenant-scoped
container of a definition; its `tenant_id` foreign key scopes every row to one
isolated organization. See `database-migrations.md` for how the schema is
changed and applied, and `database-conventions.md` for the column/index/
versioning conventions.

Migrations are forward-only Flyway SQL under `classpath:migrations`, applied as
one controlled step — **not** on startup (`spring.flyway.enabled=false`):

```bash
./gradlew flywayMigrate          # apply classpath:migrations to the configured DB
```

Override the connection with `-Dflyway.url`, `-Dflyway.username`,
`-Dflyway.password`, or the `FLYWAY_*` env vars (falling back to the Compose
`local` defaults). Rollback is a new forward migration or a backup restore, not
`flyway rollback` (see `database-migrations.md`).

### Migrations in this sprint

| Migration | Version | Owner | Task |
|---|---|---|---|
| `V3_3_0__create_tenants.sql` | 3.3.0 | `identity` | `S03-03` |
| `V3_3_1__create_workflow_definitions.sql` | 3.3.1 | `workflowdefinition` | `S03-04` |
| `V3_3_2__create_workflow_definition_versions.sql` | 3.3.2 | `workflowdefinition` | `S03-05` |
| `V3_3_3__index_tenant_queries.sql` | 3.3.3 | `workflowdefinition` | `S03-07` |
| `V3_3_4__add_workflow_definitions_version.sql` | 3.3.4 | `workflowdefinition` | `S03-10` |

---

## Related

- [`contracts/openapi/openapi.json`](../../contracts/openapi/openapi.json) — the contract the examples above match.
- [`contracts/openapi/README.md`](../../contracts/openapi/README.md) — generation and the baseline-approval flow.
- [`database-migrations.md`](./database-migrations.md) — the single process for changing the schema.
- [`database-conventions.md`](./database-conventions.md) — tables, columns, indexes, optimistic versioning.
- [`engineering-conventions.md`](./engineering-conventions.md) — `UUID`, `timestamptz`, naming.
- [`error-taxonomy.md`](./error-taxonomy.md) — the full `error_class` set and retry decisions.
- [`ADR-008`](../adr/ADR-008-database-migrations.md) — Flyway as the single migration tool.
- [`ADR-009`](../adr/ADR-009-single-persistence-spring-data-jdbc.md) — Spring Data JDBC as the single persistence approach.
- [`domain-glossary.md`](../domain-glossary.md) — canonical names (concept — one name).
