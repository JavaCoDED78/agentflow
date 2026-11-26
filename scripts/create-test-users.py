#!/usr/bin/env python3
"""Create (or repair) the AgentFlow local-dev test users in Keycloak.

Idempotent: re-running it adds missing users, sets missing roles (never
removes roles assigned manually) and refreshes full_name/email attributes.

The test users are local-dev scaffolding, never production accounts:

  tenant-a-member   tenant_id=<tenant A uuid>  roles: workflow:read, document:read, run:execute
  tenant-a-admin    tenant_id=<tenant A uuid>  roles: all eight catalogue roles
  tenant-b-member    tenant_id=<tenant B uuid>  roles: workflow:read, document:read
  tenant-b-admin     tenant_id=<tenant B uuid>  roles: all eight catalogue roles
  no-tenant-member   no tenant claim           roles: workflow:read

Environment:
  AGENTFLOW_KEYCLOAK_BASE   default http://localhost:8083
  AGENTFLOW_KEYCLOAK_REALM  default agentflow
  KEYCLOAK_ADMIN_USER       default admin
  KEYCLOAK_ADMIN_PASSWORD   default admin (local only; matches the Compose default)
  KEYCLOAK_TEST_PASSWORD    REQUIRED — never stored in Git; take the value from
                            the git-ignored local .env or export it yourself.

The test password is set on the users at creation time and is never printed;
the script reports the usernames it created and the roles assigned. Never point
this at a non-local realm.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

KEYCLOAK_BASE = os.environ.get("AGENTFLOW_KEYCLOAK_BASE", "http://localhost:8083").rstrip("/")
REALM = os.environ.get("AGENTFLOW_KEYCLOAK_REALM", "agentflow")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "admin")
TEST_PASSWORD = os.environ.get("KEYCLOAK_TEST_PASSWORD", "")

# Fixed, documented UUIDs for the two local tenants. The tenant is derived
# from a well-formed uuid in the verified token, never from an identity input
# (invariant 1 / docs/security/tenant-claim.md).
TENANT_A_ID = "11111111-1111-4111-8111-111111111111"
TENANT_B_ID = "22222222-2222-4222-8222-222222222222"

ALL_ROLES = [
    "workflow:read",
    "workflow:write",
    "workflow:activate",
    "document:read",
    "document:write",
    "run:execute",
    "run:approve",
    "ops:dlq",
]

USERS = [
    {
        "username": "tenant-a-member",
        "email": "tenant-a-member@agentflow.local",
        "tenant_id": TENANT_A_ID,
        "roles": ["workflow:read", "document:read", "run:execute"],
    },
    {
        "username": "tenant-a-admin",
        "email": "tenant-a-admin@agentflow.local",
        "tenant_id": TENANT_A_ID,
        "roles": ALL_ROLES,
    },
    {
        "username": "tenant-b-member",
        "email": "tenant-b-member@agentflow.local",
        "tenant_id": TENANT_B_ID,
        "roles": ["workflow:read", "document:read"],
    },
    {
        "username": "tenant-b-admin",
        "email": "tenant-b-admin@agentflow.local",
        "tenant_id": TENANT_B_ID,
        "roles": ALL_ROLES,
    },
    {
        "username": "no-tenant-member",
        "email": "no-tenant-member@agentflow.local",
        "tenant_id": None,
        "roles": ["workflow:read"],
    },
]


def request(method, url, body=None, token=None, headers=None):
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode("utf-8")
            return resp.status, json.loads(text) if text else None
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8")
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = text
        raise RuntimeError(f"{method} {url} -> HTTP {err.code}: {payload}") from None


def admin_token():
    url = f"{KEYCLOAK_BASE}/realms/master/protocol/openid-connect/token"
    data = urllib.parse.urlencode({
        "client_id": "admin-cli",
        "grant_type": "password",
        "username": ADMIN_USER,
        "password": ADMIN_PASSWORD,
    }).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("Accept", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode("utf-8"))["access_token"]
    except urllib.error.HTTPError as err:
        text = err.read().decode("utf-8")
        raise RuntimeError(f"admin login failed: HTTP {err.code}: {text}") from None


def realm_admin_url(path):
    return f"{KEYCLOAK_BASE}/admin/realms/{REALM}{path}"


def realm_users(token):
    _, users = request("GET", realm_admin_url("/users"), token=token)
    return users or []


def create_user(token, spec):
    body = {
        "username": spec["username"],
        "email": spec["email"],
        "enabled": True,
        "firstName": spec["username"].split("-")[0],
        "lastName": "local",
    }
    if spec["tenant_id"]:
        body["attributes"] = {"tenant_id": [spec["tenant_id"]]}
    status, user = request("POST", realm_admin_url("/users"), body=body, token=token)
    user_id = user["id"] if isinstance(user, dict) else None
    if not user_id:
        # Some Keycloak versions return 201 with an empty body; recover by id lookup.
        _, users = request("GET", realm_admin_url(f"/users?username={spec['username']}"), token=token)
        user_id = users[0]["id"] if users else None
    if not user_id:
        raise RuntimeError(f"user {spec['username']} was not created")
    return user_id


def set_password(token, user_id, password):
    request(
        "PUT",
        realm_admin_url(f"/users/{user_id}/reset-password"),
        body={"type": "password", "value": password, "temporary": False},
        token=token,
    )


def get_realm_role(token, name):
    _, roles = request("GET", realm_admin_url("/roles"), token=token)
    for role in roles or []:
        if role.get("name") == name:
            return role
    raise RuntimeError(f"catalogue role {name!r} is missing from realm {REALM!r} — check realm-agentflow.json")


def get_user_realm_roles(token, user_id):
    _, roles = request("GET", realm_admin_url(f"/users/{user_id}/role-mappings/realm"), token=token)
    return {role.get("name") for role in roles or []}


def assign_role(token, user_id, role):
    request(
        "POST",
        realm_admin_url(f"/users/{user_id}/role-mappings/realm"),
        body=[role],
        token=token,
    )


def ensure_user(token, spec):
    # Find the user by username.
    _, users = request("GET", realm_admin_url(f"/users?username={spec['username']}"), token=token)
    user = users[0] if users else None
    if user:
        user_id = user["id"]
        created = False
    else:
        user_id = create_user(token, spec)
        created = True

    # Refresh the profile attributes (idempotent). A missing tenant claim is
    # the point of the no-tenant user: do not write a tenant_id here.
    body = {
        "email": spec["email"],
        "firstName": spec["username"].split("-")[0],
        "lastName": "local",
    }
    if spec["tenant_id"]:
        body["attributes"] = {"tenant_id": [spec["tenant_id"]]}
    request(
        "PUT",
        realm_admin_url(f"/users/{user_id}"),
        body=body,
        token=token,
    )
    set_password(token, user_id, TEST_PASSWORD)

    existing = get_user_realm_roles(token, user_id)
    missing = [r for r in spec["roles"] if r not in existing]
    assigned = []
    for role_name in missing:
        assign_role(token, user_id, get_realm_role(token, role_name))
        assigned.append(role_name)

    return {
        "username": spec["username"],
        "created": created,
        "tenant_id": spec["tenant_id"],
        "roles_assigned": assigned,
        "roles_present": sorted(existing | set(missing)),
    }


def main():
    if not TEST_PASSWORD:
        print(
            "error: set KEYCLOAK_TEST_PASSWORD to the local test password "
            "(never stored in Git). It is applied to the users and never printed.",
            file=sys.stderr,
        )
        sys.exit(2)
    token = admin_token()
    print(f"Keycloak: {KEYCLOAK_BASE} (realm {REALM!r})")
    failed = False
    for spec in USERS:
        try:
            result = ensure_user(token, spec)
        except RuntimeError as err:
            print(f"  [FAIL] {spec['username']}: {err}", file=sys.stderr)
            failed = True
            continue
        marker = "created" if result["created"] else "updated"
        print(
            f"  [OK] {result['username']} ({marker}) tenant_id={result['tenant_id']} "
            f"roles={','.join(result['roles_present'])}"
        )
        if result["roles_assigned"]:
            print(f"       + assigned {','.join(result['roles_assigned'])}")
    if failed:
        print("error: one or more test users could not be created", file=sys.stderr)
        sys.exit(1)
    print("Test users ready. The local test password is the environment variable KEYCLOAK_TEST_PASSWORD.")
    print("Never use these users or passwords outside local development.")


if __name__ == "__main__":
    main()
