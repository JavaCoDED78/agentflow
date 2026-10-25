# Permission catalogue (S04-04)

> Owner: the `identity` module. Implementation: `com.example.agentflow.identity.domain.Permission`.
> Central mapping: `com.example.agentflow.identity.application.ClaimsToAuthoritiesMapper`.

The permission catalogue is the **single source of truth** for the `authorization`
capability string a verified caller must hold to perform an action. It is a small,
closed enum (`Permission`) in the `identity` module. Every capability token in the
system — the claims-to-authorities mapper, the endpoint access matrix and the
Keycloak realm — refers to the same `Permission.id()`, so the three always agree on
what a permission *is*.

## The catalogue

S04-04 canonical set (from the plan's `S04-04`):

| Constant | `Permission.id()` | Grants |
|---|---|---|
| `WORKFLOW_READ` | `workflow:read` | Read a workflow definition (list / get). |
| `WORKFLOW_WRITE` | `workflow:write` | Create or edit a workflow definition draft. |
| `WORKFLOW_ACTIVATE` | `workflow:activate` | Activate an immutable workflow version. |
| `DOCUMENT_READ` | `document:read` | Read a document or search the knowledge base (citations only from real chunks). |
| `DOCUMENT_WRITE` | `document:write` | Upload a document to the knowledge base. |
| `RUN_EXECUTE` | `run:execute` | Create a run, read run status/steps/result and cancel it. |
| `RUN_APPROVE` | `run:approve` | Approve or reject a human-approval step. |
| `OPS_DLQ` | `ops:dlq` | Inspect and reprocess dead-letter queue messages. |

### Lookup API

- `Permission.ids()` — every id in the catalogue, in declaration order; the single
  source of truth for the allowed authority values.
- `Permission.fromId(String)` — look up a permission by id (the mapper uses this to
  validate a role claim before granting it).
- `Permission.exists(String)` — membership test.
- `Permission.all()` — the catalogue in declaration order (reference list for the
  endpoint matrix and the realm).

## Why the catalogue is closed

The catalogue is intentionally **closed**. A role claim that is not one of these ids
is:

- ignored by `ClaimsToAuthoritiesMapper` (never becomes an authority), and
- denied by the endpoint access matrix.

So nobody can silently gain a capability by issuing a token with an undocumented role.
Extend the catalogue **here only** (`Permission`) — never with an ad-hoc string
elsewhere. The model is an **untrusted planner** (ADR-006): it may propose a plan but
never changes this catalogue or the allowlist — every action is validated server-side
before it runs.

## Mapping claims to authorities

`ClaimsToAuthoritiesMapper` is the **single** place that turns the role claims of a
verified JWT into Spring Security `GrantedAuthority` values. Spring Security wires it
into the `JwtAuthenticationConverter` bean, which the secured `SecurityFilterChain`
uses; every entry point — HTTP controllers, Kafka listeners, scheduled jobs — derives
the principal's authorities from the same catalogue. Controllers never parse the JWT
themselves (S04-04 acceptance: "controllers do not parse JWT differently").

Rules:

- **Deny by default.** A role claim grants an authority **only** if its value is one
  of `Permission.ids()`. An unknown role claim is dropped, so a role outside the
  catalogue cannot grant a capability.
- **Idempotent.** The same id always maps to the same authority string
  (`Permission.id()`), and duplicate role claims collapse to a single authority.
- **Trusted input only.** The token is read after the OAuth 2.0 Resource Server has
  verified it (`Jwt` claims are trusted). This mapper never derives the **tenant** —
  tenant comes from the trusted security context only (S04-05/06).

### Supported Keycloak role claim shapes

- **Client-scoped roles** — `resource_access` is a map of `clientId -> { roles:
  [...] }`; the inner `roles` array is read for **every** client, not a fixed name.
- **Realm roles** — a flat top-level `roles` claim (a single string or a list).

## Endpoint access matrix (S04-08)

The catalogue answers *what capabilities exist*; the **endpoint access matrix**
answers *which capability each authenticated action requires*. It is the single
source of truth for the endpoint-to-permission binding, and it lives in the
`identity` module next to the catalogue — `com.example.agentflow.identity.application.authorization.AccessMatrix` — so the catalogue and the matrix agree in one place.
`com.example.agentflow.config.SecurityConfiguration` consumes this matrix when it
builds the secured `SecurityFilterChain`; it never hardcodes the mapping.

Each rule (`AccessMatrix.EndpointRule`) is an HTTP `method`, an Ant-style
`pathPattern` under `/api/v1`, and the single `Permission` that the
`hasAuthority` check verifies against:

| Method | `pathPattern` | Required permission |
|---|---|---|
| `POST`  | `/api/v1/workflows`    | `workflow:write`  |
| `GET`   | `/api/v1/workflows`    | `workflow:read`   |
| `GET`   | `/api/v1/workflows/*`  | `workflow:read`   |

The chain applies the matrix by iterating its rules and gating each action on its
own permission (`authorize.requestMatchers(method, pattern).hasAuthority(requiredPermission.id())`),
then closes the rest with `anyRequest().authenticated()`.

Three invariants back the S04-08 acceptance:

- **Least privilege.** Every rule grants exactly one permission — the minimum the
  endpoint needs (`workflow:read` for a read, `workflow:write` for a write). No
  endpoint asks for more than it uses, so a token is never granted broad access it
  does not need.
- **Default-deny.** The matrix only lists what is allowed. An endpoint not in the
  matrix (and any future one) grants nothing until an explicit rule is added here,
  so nothing is reachable without an explicit rule. A caller holding none of the
  referenced permissions is denied on every endpoint.
- **Separated admin/operator operations.** User operations (`workflow:*`,
  `document:*`, `run:*`) are the rules above; operator operations that require the
  `ops:dlq` permission form a separate group, documented and reserved in the matrix
  rather than bolted on as an ad-hoc exception in the security chain. Adding a new
  operator/admin endpoint is a new rule here.

To add an endpoint: add one `EndpointRule` to `AccessMatrix` with the minimum
permission it needs. The matrix is a plain, framework-agnostic data structure; the
translation to Spring Security request matchers lives in `SecurityConfiguration`.

## Consumers

| Consumer | Uses the catalogue |
|---|---|
| Claims-to-authorities mapper (S04-04) | grants an authority only when its value is a catalogue id. |
| Endpoint access matrix (S04-08) | maps every `/api/v1` action to the required id(s); default-deny. |
| Keycloak realm (S04-01) | assigns these ids as client roles, so the token carries what the mapper expects. |

## Relationship to other security topics

- **Tenant scope** is orthogonal and stronger: a permission scope authorizes the
  *capability* ("can approve"), while the tenant/ownership check authorizes the
  *resource* ("this run is yours"). A permission scope never replaces the per-resource
  tenant/ownership check (ADR-007).
- **403/404 policy (S04-09)** decides how a missing/cross-tenant resource is masked — as a permanent
  `404 not_found`, never a distinguishing `403`; the single source is
  [`docs/security/response-policy.md`](../security/response-policy.md). The catalogue decides which
  capabilities exist.
- **Least privilege (S04-08)** assigns the minimum id(s) to each endpoint on top of
  this catalogue.
