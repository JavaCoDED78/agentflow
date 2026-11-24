#!/usr/bin/env bash
#
# Mints the short-lived bearer token the local Prometheus uses to scrape the
# application's /actuator/prometheus endpoint (S13-05).
#
# The endpoint sits behind the default-deny secured chain (S13-02): a scrape
# must carry a bearer token, and the local Keycloak realm signs access tokens
# for only 300 s. Prometheus re-reads its bearer_token_file before every
# scrape (verified on the pinned image), so refreshing the mounted file is
# enough — Prometheus needs no reload and no restart.
#
# The token is obtained with the machine-to-machine client (`agentflow-service`,
# S04-02) via the Client Credentials grant. The client secret comes from
# AGENTFLOW_SERVICE_CLIENT_SECRET (same variable and local-only default as the
# Keycloak compose service); set it in the git-ignored infra/compose/.env or
# export it before running.
#
# By default the script mints one token and exits (good for ~5 minutes of
# scraping). Use --watch to keep refreshing for a longer local session:
#
#   bash scripts/mint-scrape-token.sh                # one token, then exit
#   bash scripts/mint-scrape-token.sh --watch        # refresh every 240 s
#   bash scripts/mint-scrape-token.sh --watch 120    # refresh every 120 s
#
# WARNING: the script overwrites infra/observability/prometheus/scrape/token,
# which is GIT-IGNORED (removed from the tree in S13-14): a fresh checkout does
# not contain it and never should. The file is created/updated on every mint;
# never commit it. Do not add it back to the repository.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="$REPO_ROOT/infra/observability/prometheus/scrape/token"

KEYCLOAK_BASE="${AGENTFLOW_KEYCLOAK_BASE:-http://localhost:8083}"
REALM="${AGENTFLOW_KEYCLOAK_REALM:-agentflow}"
CLIENT_ID="${AGENTFLOW_SERVICE_CLIENT_ID:-agentflow-service}"
# Same local-only default as the Keycloak compose service; a real value lives
# in the git-ignored infra/compose/.env.
CLIENT_SECRET="${AGENTFLOW_SERVICE_CLIENT_SECRET:-local-dev-m2m-secret-rotate-me}"

WATCH=0
INTERVAL=240

usage() {
  sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

while (($#)); do
  case "$1" in
    --watch)
      WATCH=1
      if [[ -n "${2:-}" && "$2" != --* ]]; then INTERVAL="${2:?--watch needs seconds}"; shift; fi
      shift
      ;;
    -h|--help) usage ;;
    *) echo "error: unknown argument '$1' (use --help)" >&2; exit 2 ;;
  esac
done

# Keycloak signs access tokens for 300 s in the local realm (accessTokenLifespan
# in infra/compose/keycloak/realm-agentflow.json); refreshing well inside the TTL
# keeps every scrape inside the token's validity window.
if (( INTERVAL >= 300 )); then
  echo "error: refresh interval must stay below the 300 s token TTL (got $INTERVAL)" >&2
  exit 2
fi

mint() {
  local response
  if ! response="$(curl -fsS -m 10 \
      -u "$CLIENT_ID:$CLIENT_SECRET" \
      -d grant_type=client_credentials \
      "$KEYCLOAK_BASE/realms/$REALM/protocol/openid-connect/token")"; then
    echo "error: token request failed (is the 'identity' profile up and reachable at $KEYCLOAK_BASE?)" >&2
    return 1
  fi
  local token
  token="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"
  printf '%s\n' "$token" > "$TOKEN_FILE"
  echo "scraping token minted (expires in 300 s) -> $TOKEN_FILE"
}

mint

while (( WATCH )); do
  sleep "$INTERVAL"
  mint || true
done
