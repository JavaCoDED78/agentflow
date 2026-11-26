# Local test users (S04-18)

> Owner: the `identity` module and the `infra` Compose stack (Keycloak realm).
> Governing docs: [`docs/security/tenant-claim.md`](tenant-claim.md),
> [`docs/security/permissions.md`](permissions.md); implementation:
> [`infra/compose/keycloak/realm-agentflow.json`](../../infra/compose/keycloak/realm-agentflow.json),
> [`scripts/create-test-users.py`](../../scripts/create-test-users.py),
> [`scripts/get-user-token.py`](../../scripts/get-user-token.py).

The local Keycloak realm carries two test tenants and their users so the Resource Server,
the access matrix and the tenant-scope rules can be exercised end-to-end. These users are
**local-development scaffolding, never production accounts**: they carry fixed, documented
UUIDs and a shared throwaway password that only comes from the git-ignored local `.env`.

## The two test tenants

| Tenant | UUID | Notes |
|---|---|---|
| Tenant A | `11111111-1111-4111-8111-111111111111` | two users, four and one role sets |
| Tenant B | `22222222-2222-4222-8222-222222222222` | mirror of Tenant A |

The tenant is derived from a well-formed `tenant_id` claim in the verified token (see
[`docs/security/tenant-claim.md`](tenant-claim.md)) — never from an identity input. A user
without a `tenant_id` is **not** distinguishable from one whose tenant is unknown or
disabled (single 403/404 masking policy, `S04-09`).

## Test users

| Username | Tenant | Roles | Purpose |
|---|---|---|---|
| `tenant-a-member` | A | `workflow:read`, `document:read`, `run:execute` | tenant-scoped read + execute |
| `tenant-a-admin` | A | all eight catalogue roles | full tenant-A scope |
| `tenant-b-member` | B | `workflow:read`, `document:read` | cross-tenant isolation case (B, A↔B) |
| `tenant-b-admin` | B | all eight catalogue roles | full tenant-B scope |
| `no-tenant-member` | none | `workflow:read` | missing tenant claim — the "no membership" scenario |

The eight catalogue roles (`S04-04`, see
[`docs/security/permissions.md`](permissions.md)) are `workflow:read`, `workflow:write`,
`workflow:activate`, `document:read`, `document:write`, `run:execute`, `run:approve`, `ops:dlq`.
They are declared as realm roles in the realm import and bound to the shared `agentflow`
client scope (`fullScopeAllowed: false`, `roles: [...]`), so the token can only ever surface
catalogue roles — no other realm role leaks through.

## Where passwords come from

- **Test passwords never live in Git.** `scripts/create-test-users.py` and
  `scripts/get-user-token.py` read `KEYCLOAK_TEST_PASSWORD` from the environment; the real
  value is in the git-ignored local `.env` (`.env.example` documents the placeholder).
  Both scripts refuse to run without it and never print it.
- **Client secrets** (`agentflow-service`) are likewise environment-only; the realm import
  uses the `${AGENTFLOW_SERVICE_CLIENT_SECRET}` placeholder, resolved from the container
  environment at `--import-realm` time.
- Realm users are created and repaired by `scripts/create-test-users.py` (idempotent —
  re-running never removes roles a user has acquired manually). Never point it at a
  non-local realm.

## Getting a user token locally

The realm prohibits password grant (`S04-02`): both clients disable the direct-access grant,
`standardFlowEnabled` is off for the m2m client. User tokens are obtained via
**Authorization Code + PKCE** (the same method the public SPA client uses), which is also
what makes the local token path reproducible for `curl` and tests:

```bash
export KEYCLOAK_TEST_PASSWORD=<local throwaway password>
TOKEN=$(python3 scripts/get-user-token.py tenant-a-member)
curl -sS http://localhost:8080/api/v1/workflows -H "Authorization: Bearer ***"
```

`scripts/get-user-token.py` performs the full PKCE flow headlessly: generates a verifier and
challenge, signs in on the Keycloak login page, completes any required action, and exchanges
the authorization code for the token. With `--decode` it prints the verified claims instead
of the token (useful for confirming `iss`, `aud`, `tenant_id`, `roles`, …).

For machine-to-machine access the realm uses **client credentials** on
`agentflow-service` (confidential, no user flow). The full walkthrough is in the
[local-auth runbook](../runbooks/local-auth.md) (`S04-14`).

## What a freshly verified realm shows

After `docker compose --profile identity up -d` and `python3 scripts/create-test-users.py`:

| Check | Expected |
|---|---|
| Token `iss` | `http://localhost:8083/realms/agentflow` |
| Token `aud` | contains `agentflow-api` |
| Token `tenant_id` | A or B for the seeded tenant-scoped users; **absent** for `no-tenant-member` |
| Token `realm_access.roles` | exactly the user's catalogue roles (plus `offline_access`, `default-roles-agentflow`, `uma_authorization`) |
| `agentflow-web` | `grant_type=authorization_code`, `standardFlowEnabled=true`, `directAccessGrantsEnabled=false` |
| `agentflow-service` | `grant_type=client_credentials`, `standardFlowEnabled=false`, `directAccessGrantsEnabled=false` |
| No password grant anywhere | `directAccessGrantsEnabled` is false on both clients |
