# `infra/compose/keycloak/` — declarative realm

The local OIDC identity provider runs Keycloak and imports a realm declared in
Git. The realm is the single source of truth for clients, client scopes and
realm roles; there are no manual changes in the Keycloak UI (see the `Everything
from Git` rule in [`../../AGENTS.md`](../../AGENTS.md)).

## Import

The [`identity`](../docker-compose.yml) Compose profile starts Keycloak
(`quay.io/keycloak/keycloak:26.8.1`) with `start-dev --import-realm`, which
imports `<import-dir>/realm.json` on startup. The mounted file is
[`realm-agentflow.json`](realm-agentflow.json) (read-only). Because Keycloak
imports only when the realm is absent, a clean volume re-imports the same model;
Keycloak assigns client/role/scope `id` values at import time, so those UUIDs
are not stable across clean imports — the model is reproducible in structure,
not byte-for-byte in generated ids.

The realm is named `agentflow`. Registration is off, access tokens live 5
minutes, and refresh tokens are not revoked.

## Clients

S04-02 split the original single `agentflow-api` client (which mixed user login,
service accounts and the password grant) into three purpose-built clients. Every
AgentFlow client has `directAccessGrantsEnabled=false` — the password grant is
never used.

| Client | Type | Grants | Secret | Purpose |
|---|---|---|---|---|
| `agentflow-web` | public (SPA) | Authorization Code + PKCE | none | user login |
| `agentflow-service` | confidential | Client Credentials (service accounts) | from env, never in Git | machine-to-machine |
| `agentflow-api` | resource server | none | none | OIDC audience (`aud=agentflow-api`) |

- **`agentflow-web`** — the browser client. It is `publicClient` and authenticates
  with PKCE (no client secret). The Authorization Code flow is on (`standardFlowEnabled`);
  the password and service-account grants are off. `redirectUris` and `webOrigins`
  are limited to the local SPA origin (`http://localhost:3000`); set them to the
  production SPA origin before deploy.
- **`agentflow-service`** — the machine-to-machine client. It is `publicClient=false`
  and uses only the Client Credentials grant (`serviceAccountsEnabled=true`,
  `standardFlowEnabled=false`). Its secret is `${AGENTFLOW_SERVICE_CLIENT_SECRET}` —
  resolved from the container environment at import time by Keycloak's realm-import
  placeholder support, so the value never lives in Git. A real value comes from the
  git-ignored local `.env`; the Compose default (`local-dev-m2m-secret-rotate-me`)
  is local-only and clearly not a production secret.
- **`agentflow-api`** — the resource server. It validates incoming user tokens and is
  the OIDC audience (`aud=agentflow-api`, see the `cloak.audience` value in the root
  `.env.example`). It does not issue tokens, so it has no login flow, no service
  accounts and no client secret. Audience matching for the Spring Resource Server is
  configured in **S04-03**.

## Client scopes

The single default scope `agentflow` (added in S04-01) carries three
`oidc-usermodel-protocol-mapper` mappers — `preferred_username`, `email` and
`name` — plus a `oidc-usermodel-attribute-mapper` for the trusted `tenant_id`
claim (S04-05, present only for users with a tenant membership). They are
attached to the user (`agentflow-web`) and service (`agentflow-service`) clients.
The `agentflow-api` resource server carries no scopes: it only validates
tokens. The audience mapper that stamps `aud=agentflow-api` is a later task
(S04-03).

## Realm roles

`workflow:read`, `workflow:write`, `workflow:approve`, `knowledge:read`,
`tenant:admin` (S01 set). The Sprint 4 permission catalogue that maps these to
endpoint authorities is **S04-04**.

## Secrets

Real secrets never enter Git. The m2m client secret is an environment placeholder
in [`realm-agentflow.json`](realm-agentflow.json) and is injected into the Keycloak
container by Compose from a local `.env`; the local default is clearly marked as
local-only. Rotate any real value into a Kubernetes Secret / external secret store
before deploy.

## Getting a token for testing

No real secrets are used below; substitute your local value where marked.

Machine-to-machine (Client Credentials):

```bash
export AGENTFLOW_SERVICE_CLIENT_SECRET=local-dev-m2m-secret-rotate-me
curl -s -X POST http://localhost:8083/realms/agentflow/protocol/openid-connect/token \
  --data-urlencode "client_id=agentflow-service" \
  --data-urlencode "client_secret=$AGENTFLOW_SERVICE_CLIENT_SECRET" \
  --data-urlencode "grant_type=client_credentials"
```

User login (Authorization Code + PKCE) is an interactive, browser-based flow
started from the SPA at
`http://localhost:8083/realms/agentflow/protocol/openid-connect/auth?client_id=agentflow-web&response_type=code&redirect_uri=http://localhost:3000/&scope=openid`.
A service account (Service Tokens) token is issued with
`grant_type=client_credentials` against `agentflow-service` once its audience is
wired (S04-03).
