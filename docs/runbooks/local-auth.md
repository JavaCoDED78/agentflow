# Local authentication runbook (S04-14)

> Owner: the `identity` module (Resource Server) and the `infra` Compose stack (Keycloak realm).
> Governing docs: [`docs/security/tenant-claim.md`](../security/tenant-claim.md),
> [`docs/security/permissions.md`](../security/permissions.md),
> [`docs/security/response-policy.md`](../security/response-policy.md),
> [`docs/adr/ADR-007-tenant-isolation.md`](../adr/ADR-007-tenant-isolation.md); implementation:
> `apps/agentflow-app/.../config/SecurityConfiguration.java`.

This runbook walks through **logging in locally** against the AgentFlow API and calling a secured
`/api/v1` endpoint with a real Keycloak token — no code changes required. It covers the two local
login flows (machine-to-machine and the browser Authorization Code flow), shows the exact `curl`
commands, and explains the two things that most often go wrong locally: the **issuer** and the
**audience** of the token.

## What the secured API checks

Before any controller runs, the OAuth 2.0 Resource Server
(`SecurityConfiguration.securityFilterChain`, built on `NimbusJwtDecoder`) verifies the bearer
JWT. A token that fails any check is rejected with `401 Unauthorized` at the security layer.

1. **Signature** — signed by the issuer's private key; the matching public keys are fetched
   lazily from the issuer's OIDC discovery `jwk_set_uri` (never at startup).
2. **Issuer** — the `iss` claim must equal `agentflow.security.issuer-uri`
   (`AGENTFLOW_KEYCLOAK_ISSUER_URI`, `http://localhost:8083/realms/agentflow` locally).
3. **Audience** — the `aud` claim must contain `agentflow.security.audience` (`agentflow-api`).
   This is the "which resource server is this token for" check.
4. **Expiry** — the `exp` claim must not be in the past.

A verified token is then additionally gated on a **single least-privilege permission**
(`SecurityConfiguration` consumes the `AccessMatrix`, S04-08). A valid token without the right
permission gets `403`; every `/api/v1` action requires its own permission.

| Endpoint (local) | Method | Permission required |
|---|---|---|
| `/api/v1/workflows` | `POST` | `workflow:write` |
| `/api/v1/workflows` | `GET` | `workflow:read` |
| `/api/v1/workflows/{workflowId}` | `GET` | `workflow:read` |

> `workflow:{read,write}` are Keycloak client roles assigned to the caller. The closed permission
> catalogue is in [`docs/security/permissions.md`](../security/permissions.md) (S04-04); the
> endpoint-to-permission matrix is the `AccessMatrix` component in the `identity` module.

## Prerequisites

Keycloak and PostgreSQL are enough for this runbook.

```bash
# PostgreSQL (source of truth; the app boots without it until it is needed)
docker compose --profile core up -d

# Keycloak (imports the declarative realm on first start)
docker compose --profile identity up -d
```

Confirm Keycloak is reachable — the discovery document must return `200`:

```bash
curl -sS http://localhost:8083/realms/agentflow/.well-known/openid-configuration
```

Start the API locally. The issuer comes from the environment (the git-ignored `.env`); the
audience is fixed to `agentflow-api` in `application-local.yml`.

```bash
export AGENTFLOW_KEYCLOAK_ISSUER_URI=http://localhost:8083/realms/agentflow
./gradlew :apps:agentflow-app:bootRun -Dspring.profiles.active=local
```

> The `agentflow-service` Client Credentials secret and the Keycloak `admin` password are the local
> Compose defaults (`local-dev-m2m-secret-rotate-me` / `admin`). Rotate them in your own `.env` for
> any machine other than your laptop; never commit the real `.env`.

## Flow A — machine-to-machine (curl only)

`agentflow-service` is a confidential machine-to-machine client that uses only the Client
Credentials grant — the fastest way to get a token for testing the API.

```bash
TOKEN_ENDPOINT=http://localhost:8083/realms/agentflow/protocol/openid-connect/token

ACCESS_TOKEN=$(curl -sS "$TOKEN_ENDPOINT" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=agentflow-service' \
  --data-urlencode "client_secret=$AGENTFLOW_SERVICE_CLIENT_SECRET" \
  --data-urlencode 'grant_type=client_credentials' \
  | jq -r '.access_token')
```

Call the API:

```bash
curl -sS http://localhost:8080/api/v1/workflows \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Decode the token — without printing any secret — to see what the Resource Server actually checks:

```bash
python3 -c "import base64,json,sys; t=sys.argv[1]; p=json.loads(base64.urlsafe_b64decode(t.split('.')[1]+'==')); print(json.dumps(p, indent=2))" "$ACCESS_TOKEN"
```

## Flow B — user login (Authorization Code + PKCE)

`agentflow-web` is a public SPA client. It uses the Authorization Code flow with PKCE, so no client
secret leaves the browser. The browser performs the login and consent at `http://localhost:8083`,
then the code is exchanged for the token.

```bash
# 1. Open this in a browser to log in and consent (PKCE, no client secret).
#    Replace CODE_CHALLENGE with base64url(sha256(code_verifier)).
#    <your-app>/login is the redirect URI configured for agentflow-web.
#    http://localhost:8083/realms/agentflow/protocol/openid-connect/auth\
#     ?response_type=code&scope=openid&state=xyz\
#      &code_challenge=CODE_CHALLENGE&code_challenge_method=S256&redirect_uri=<your-app>/login

# 2. Keycloak redirects back to <your-app>/login?code=...&state=xyz. Exchange it:
curl -sS "$TOKEN_ENDPOINT" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=agentflow-web' \
  --data-urlencode 'code=<code-from-redirect>' \
  --data-urlencode 'redirect_uri=<your-app>/login' \
  --data-urlencode 'code_verifier=<the PKCE verifier>' \
  --data-urlencode 'grant_type=authorization_code' | jq -r '.access_token'
```

The `aud` of a user token is whatever the realm stamps for the caller — see the audience note
below.

## The audience claim — the most common local failure

The Resource Server requires the token's `aud` claim to contain **`agentflow-api`**. In OIDC the
`aud` identifies the resource server the token is meant for. The realm now stamps the `agentflow-api`
audience into both token kinds (S04-03, wired via the `fixed-audience` mapper on the shared
`agentflow` client scope):

- **`agentflow-service` (m2m)** — its access token carries `azp=agentflow-service` and now
  `aud=["agentflow-api"]`.
- **`agentflow-web` (user)** — its access token carries the `agentflow-api` audience (the shared
  `agentflow` client scope stamps it for the browser flow too).

A `401` on the audience check still happens when **no** `aud` claim is present, or it does not
contain `agentflow-api`. This is the correct behaviour, not a misconfiguration: the audience
validator is doing its job. On a running local Keycloak, decode a freshly issued `agentflow-service`
token — the payload shows `iss`, `sub`, `azp=agentflow-service`, `scope=agentflow`, and now
`aud=["agentflow-api"]`. If you ever see no `aud`, the realm was not re-imported after the change
(see below).

## Troubleshooting

| Symptom (HTTP status) | Cause | Fix |
|---|---|---|
| `401`, issuer mismatch | `AGENTFLOW_KEYCLOAK_ISSUER_URI` points at a different issuer than the running Keycloak | Point it at `http://localhost:8083/realms/agentflow`; confirm with the discovery document |
| `401`, audience missing/mismatch | Token has no `aud=agentflow-api` | Realm not re-imported after the S04-03 audience mapper; see below |
| `401`, expired | Token `exp` is in the past | Re-issue — access tokens are short-lived |
| `401`, signature | Token signed by a different key | Point at the correct Keycloak; a stale one is the usual cause |
| `401` (empty body) on *any* well-formed token, *before* the audience check | The running Keycloak (26.8.1) exposes the JWK set under `jwks_uri`, not the OIDC-standard `jwk_set_uri`; the secured chain's `NimbusJwtDecoder` (`SecurityConfiguration`) resolves `jwk_set_uri` from the discovery document and rejects the token's signature while the audience check has not run yet. Fixed in code — `jwkSetFromIssuer` now falls back to `jwks_uri` (both point at `/protocol/openid-connect/certs`) | Confirm with the discovery document; the discovery field is Keycloak's behaviour, not a realm misconfiguration |
| `403` | Valid token, but missing the required permission (e.g. `workflow:read` on `GET`) | Grant the client role in Keycloak, or use a caller that holds it |
| `404` | Valid token + permission, but the resource is missing or belongs to another tenant | Expected: cross-tenant reads are `404`, never `403` (S04-09, the masking policy) |

## Unblocking the audience check (S04-03)

For a local token to pass the `aud=agentflow-api` check, the realm must stamp that audience into the
token. This is an **infrastructure (realm) change**, owned by `S04-03` — it is **not** a code
change, so the API keeps running unchanged. It is done by adding the `oidc-audience-mapper`
`fixed-audience` (`included.client.audience=agentflow-api`) to the shared `agentflow` client scope in
[`infra/compose/keycloak/realm-agentflow.json`](../../infra/compose/keycloak/realm-agentflow.json),
which is re-imported declaratively when Keycloak restarts. The mapper is present in the realm file
and in the running realm today; a freshly issued token carries `aud=[...,"agentflow-api"]` and the
`curl` examples above return a real response instead of `401`.

## Verified locally

| Step | Command | Result |
|---|---|---|
| Keycloak reachable | `docker compose --profile identity up -d` | `agentflow-keycloak` `healthy` |
| OIDC discovery | `curl .../.well-known/openid-configuration` | `HTTP 200`; issuer `http://localhost:8083/realms/agentflow`; token endpoint `/protocol/openid-connect/token` |
| m2m token issuance | Client Credentials for `agentflow-service` | Token issued (a wrong secret returns `401`) |
| m2m token claims | decode the access token | `iss`, `sub`, `azp=agentflow-service`, `scope=agentflow`, `aud=["agentflow-api"]` (S04-03 audience wired) |
| App audience config | `application-local.yml` | `agentflow.security.audience: agentflow-api` |
| Secured API — no token | `curl .../api/v1/workflows` (no `Authorization`) | `401 unauthorized` — rejected at the Resource Server (S04-03) |
| Secured API — valid token | `curl .../api/v1/workflows` with the m2m token | `403 authorization` — the token passed signature/issuer/audience; `agentflow-service` has no `workflow:read` role, so the `AccessMatrix` (S04-08) denies it. Grant the client role in Keycloak (or use a role-bearing caller) to see `200` |

## Related

- [`docs/security/tenant-claim.md`](../security/tenant-claim.md) — how `tenant_id` rides on the
  verified token.
- [`docs/security/permissions.md`](../security/permissions.md) — the permission catalogue and the
  endpoint access matrix.
- [`docs/security/response-policy.md`](../security/response-policy.md) — the `403`/`404` masking
  policy.
- **`S04-15`** — `docs/security/authentication-authorization.md` (OIDC user flow, m2m flow,
  allowed claims, the permission catalogue, tenant resolution, the `401`/`403`/`404` policy) links
  back to this runbook.
