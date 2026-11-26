#!/usr/bin/env python3
"""Get a user access token for local development and tests (authorization code + PKCE).

This is the S04-18 user-token acquisition method. The realm has no password
grant (S04-02) and the local test users (tenant-a-member, tenant-a-admin,
tenant-b-member, tenant-b-admin, no-tenant-member) are only meant for local
development. This helper performs the full Authorization Code + PKCE flow
headlessly against a local Keycloak:

  1. Builds a random PKCE verifier and its S256 challenge.
  2. Loads the Keycloak login page for the public ``agentflow-web`` client.
  3. Signs in with the test user's credentials (password from the environment).
  4. Completes any required-action step (e.g. profile verification).
  5. Exchanges the authorization code for an access token.
  6. Prints the token on stdout — or ``--decode`` prints the verified claims.

Environment:
  AGENTFLOW_KEYCLOAK_BASE   default http://localhost:8083
  AGENTFLOW_KEYCLOAK_REALM  default agentflow
  CLIENT_ID                default agentflow-web (the public SPA client)
  REDIRECT_URI             default http://localhost:3000/auth-callback
  KEYCLOAK_TEST_PASSWORD   REQUIRED — never stored in Git; take the value from
                           the git-ignored local .env or export it yourself.

Local-only guard: refuses any base URL that is not localhost / 127.0.0.1.
Never prints the password or any credential.

Usage:
  python3 scripts/get-user-token.py <username> [--base URL] [--decode]
"""

import argparse
import base64
import hashlib
import http.client
import json
import os
import re
import secrets
import sys
import urllib.parse

DEFAULT_BASE = "http://localhost:8083"
DEFAULT_REALM = "agentflow"
DEFAULT_CLIENT_ID = "agentflow-web"
DEFAULT_REDIRECT_URI = "http://localhost:3000/auth-callback"
TIMEOUT = 20


class Fail(Exception):
    pass


def refuse_remote(base):
    host = urllib.parse.urlparse(base).hostname or ""
    if host not in ("localhost", "127.0.0.1", "::1", "[::1]"):
        raise Fail(
            f"refusing to use a non-local Keycloak base URL ({base!r}); "
            "this helper exists for local development and tests only"
        )


class Client:
    def __init__(self, base):
        parsed = urllib.parse.urlparse(base)
        if parsed.scheme != "http":
            raise Fail("only http base URLs are supported by this helper")
        self.host = parsed.hostname or ""
        if not self.host:
            raise Fail("base URL has no host")
        self.port = parsed.port or 80
        self.cookie = None

    def request(self, method, path, data=None):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT)
        headers = {"User-Agent": "agentflow-headless-test-client"}
        body = None
        if data is not None:
            body = urllib.parse.urlencode(data).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            headers["Content-Length"] = str(len(body))
        if self.cookie:
            headers["Cookie"] = self.cookie
        conn.request(method, path, body=body, headers=headers)
        resp = conn.getresponse()
        raw = resp.read()
        set_cookie = resp.getheader("Set-Cookie")
        location = resp.getheader("Location") or ""
        status = resp.status
        resp.close()
        conn.close()
        # Keep the first Set-Cookie (session) — Keycloak's flow needs it.
        if set_cookie:
            self.cookie = set_cookie.split(";")[0]
        return status, location, raw.decode("utf-8", "replace")


def main():
    parser = argparse.ArgumentParser(
        description="Get a user access token for local development and tests (authorization code + PKCE)."
    )
    parser.add_argument("username", help="local test-user username, e.g. tenant-a-member")
    parser.add_argument("--base", default=os.environ.get("AGENTFLOW_KEYCLOAK_BASE", DEFAULT_BASE))
    parser.add_argument("--realm", default=os.environ.get("AGENTFLOW_KEYCLOAK_REALM", DEFAULT_REALM))
    parser.add_argument("--client-id", default=os.environ.get("CLIENT_ID", DEFAULT_CLIENT_ID))
    parser.add_argument("--redirect-uri", default=os.environ.get("REDIRECT_URI", DEFAULT_REDIRECT_URI))
    parser.add_argument(
        "--decode",
        action="store_true",
        help="print the verified claims instead of the token (never the password)",
    )
    args = parser.parse_args()

    refuse_remote(args.base)

    password = os.environ.get("KEYCLOAK_TEST_PASSWORD", "")
    if not password:
        raise Fail(
            "set KEYCLOAK_TEST_PASSWORD to the local test password "
            "(never stored in Git) — it is read from the environment only"
        )

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()

    client = Client(args.base)
    realm = args.realm

    auth_path = (
        f"/realms/{realm}/protocol/openid-connect/auth?"
        + urllib.parse.urlencode(
            {
                "client_id": args.client_id,
                "response_type": "code",
                "redirect_uri": args.redirect_uri,
                "code_challenge": challenge,
                "code_challenge_method": "S256",
            }
        )
    )

    # 1. Load the login page and extract its server-rendered form.
    status, _, body = client.request("GET", auth_path)
    if status != 200:
        raise Fail(f"unexpected status {status} on the auth endpoint")
    form = re.search(r'action="([^"]+)"', body)
    if not form:
        raise Fail("no login form found on the auth page")
    form_action = form.group(1).replace("&amp;", "&")

    # 2. Submit the credentials.
    status, loc, body = client.request(
        "POST",
        form_action,
        {"username": args.username, "password": password, "credentialId": ""},
    )

    # 3. Follow the flow; a required action (profile verification) may ask
    #    for an email update — submit what the user already has.
    for _ in range(4):
        if not (status in (301, 302, 303) and loc):
            break
        if "code=" in loc or "state=" in loc:
            break
        if "required-action" in loc:
            status, _, body = client.request("GET", loc)
            m = re.search(r'action="([^"]+)"', body)
            if not m:
                raise Fail("required-action page has no form")
            profile_action = m.group(1).replace("&amp;", "&")
            email = f"{args.username}@agentflow.local"
            status, loc, body = client.request(
                "POST", profile_action, {"email": email, "firstName": "Dev", "lastName": "Local"}
            )
            continue
        break

    if not loc:
        raise Fail(f"login failed (last status {status})")

    params = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query)
    code = params.get("code", [None])[0]
    if not code:
        raise Fail("no authorization code in the post-login redirect")

    # 4. Exchange the code for the token (PKCE).
    status, _, body = client.request(
        "POST",
        f"/realms/{realm}/protocol/openid-connect/token",
        {
            "client_id": args.client_id,
            "grant_type": "authorization_code",
            "redirect_uri": args.redirect_uri,
            "code": code,
            "code_verifier": verifier,
        },
    )
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        raise Fail(f"token response is not JSON: {body[:200]}")
    token = data.get("access_token")
    if not token:
        raise Fail(f"token exchange failed: {data}")

    if args.decode:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload))
        print(json.dumps(claims, indent=2, ensure_ascii=False))
        return

    print(token)


if __name__ == "__main__":
    try:
        main()
    except Fail as err:
        print(f"error: {err}", file=sys.stderr)
        sys.exit(1)
    except Exception as err:  # connection refused etc.
        print(f"error: {err}", file=sys.stderr)
        sys.exit(1)
