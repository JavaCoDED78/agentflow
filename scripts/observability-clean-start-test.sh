#!/usr/bin/env bash
#
# Observability clean-start test (S13-20).
#
# Drops the observability stack's state — the named volumes agentflow-observability
# (Grafana sqlite), agentflow-prometheus (Prometheus TSDB), agentflow-loki
# (chunks + TSDB index) and agentflow-tempo (blocks + WAL) — then brings the
# stack back up from `Git` and verifies that EVERY surface is re-provisioned
# automatically, with no UI-only step in between:
#
#   - all Grafana datasources declared in Git (uid, read-only)
#   - all dashboards declared in Git (provisioned: true)
#   - all alert rules declared in Git (provisioning API + live evaluation)
#   - a manual UI edit attempt against a provisioned dashboard is refused
#
# The expected uids are read FROM the Git files (the provisioning YAML and the
# dashboard directory), never hardcoded — the test proves that what Git ships
# is exactly what Grafana boots with.
#
# Usage:
#   bash scripts/observability-clean-start-test.sh
#
# Exits 0 (PASS) or 1 (FAIL / setup error). It wipes and recreates the named
# volumes named above; the dev/demo stack carries no durable data.
set -euo pipefail

COMPOSE_FILE="infra/compose/docker-compose.yml"
PROFILE="observability"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
# Same default the compose file uses (GF_SECURITY_ADMIN_PASSWORD).
GF_PASSWORD="${GF_ADMIN_PASSWORD:-admin}"
GRAFANA_AUTH="admin:${GF_PASSWORD}"
WAIT_SECONDS="${WAIT_SECONDS:-300}"
CHECK_INTERVAL=5
VOLUMES=(agentflow-observability agentflow-prometheus agentflow-loki agentflow-tempo)
DASHBOARD_DIR="infra/observability/grafana/dashboards"
DATASOURCE_FILE="infra/observability/grafana/provisioning/datasources/agentflow.yaml"
ALERTS_FILE="infra/observability/grafana/provisioning/alerting/agentflow-alerts.yaml"

FAILURES=0

ok()   { printf '  [PASS] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; FAILURES=$((FAILURES+1)); }
note() { printf '==> %s\n' "$1"; }

# ---------- expected surfaces, read from Git ----------------------------------
EXPECTED_DASHBOARDS=($(ls "$DASHBOARD_DIR"/*.json | sed 's/.*\///; s/\.json$//'))
EXPECTED_DATASOURCES=($(grep -E '^    uid: ' "$DATASOURCE_FILE" | awk '{print $2}'))
EXPECTED_ALERTS=($(grep -E '^      - uid: ' "$ALERTS_FILE" | awk '{print $3}'))

if [ "${#EXPECTED_DASHBOARDS[@]}" -eq 0 ] || [ "${#EXPECTED_DATASOURCES[@]}" -eq 0 ] || [ "${#EXPECTED_ALERTS[@]}" -eq 0 ]; then
  echo "error: could not read the expected surfaces from the Git files" >&2
  echo "  dashboards: ${EXPECTED_DASHBOARDS[*]:-<none>}" >&2
  echo "  datasources: ${EXPECTED_DATASOURCES[*]:-<none>}" >&2
  echo "  alerts: ${EXPECTED_ALERTS[*]:-<none>}" >&2
  exit 1
fi

echo "Observability clean-start test (profile: ${PROFILE})"
echo "  dashboards: ${EXPECTED_DASHBOARDS[*]}"
echo "  datasources: ${EXPECTED_DATASOURCES[*]}"
echo "  alert rules: ${EXPECTED_ALERTS[*]}"
echo

# ---------- preconditions -----------------------------------------------------
note "Preconditions"
if ! command -v docker >/dev/null 2>&1; then
  echo "error: docker is not installed or not on PATH" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "error: the docker daemon is not reachable (is Docker Desktop running?)" >&2
  exit 1
fi
if ! docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" config >/dev/null 2>&1; then
  echo "error: 'docker compose config' failed for profile '$PROFILE'" >&2
  exit 1
fi
ok "docker + compose config valid"

# ---------- wipe state ----------------------------------------------------------
note "Wiping state (docker compose down + volume rm)"
docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" down >/dev/null 2>&1 || true
REMOVED=$(docker volume rm "${VOLUMES[@]}" 2>/dev/null || true)
[ -z "$REMOVED" ] && note "  (all volumes were already absent)"
ok "state wiped"

# ---------- bring the stack up -------------------------------------------------
note "Bringing the stack up (docker compose up -d)"
if ! docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" up -d; then
  echo "error: failed to start the '$PROFILE' profile" >&2
  exit 1
fi

EXPECTED_CONTAINERS=(agentflow-otel-collector agentflow-observability agentflow-prometheus agentflow-loki agentflow-tempo)
note "Waiting for every service to become healthy (max ${WAIT_SECONDS}s)"
DEADLINE=$((SECONDS + WAIT_SECONDS))
ALL_HEALTHY=0
while (( SECONDS < DEADLINE )); do
  ALL_HEALTHY=1
  for name in "${EXPECTED_CONTAINERS[@]}"; do
    STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)"
    [ "$STATUS" == "healthy" ] || ALL_HEALTHY=0
  done
  (( ALL_HEALTHY == 1 )) && break
  sleep "$CHECK_INTERVAL"
done
for name in "${EXPECTED_CONTAINERS[@]}"; do
  STATUS="$(docker inspect --format '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)"
  if [ "$STATUS" == "healthy" ]; then
    ok "$name healthy"
  else
    fail "$name is '$STATUS' (expected healthy)"
  fi
done
(( ALL_HEALTHY == 1 )) || { echo; echo "error: services did not become healthy in time — aborting verification" >&2; exit 1; }

# ---------- Grafana API verification -------------------------------------------
note "Grafana verification ($GRAFANA_URL)"
AUTH="-u $GRAFANA_AUTH"

# Health.
HEALTH=$(curl -s $AUTH "$GRAFANA_URL/api/health")
if [ "$(echo "$HEALTH" | python3 -c "import json,sys; print(json.load(sys.stdin).get('database',''))" 2>/dev/null)" == "ok" ]; then
  ok "Grafana healthy (database: ok)"
else
  fail "Grafana health check failed: $HEALTH"
fi

# Datasources.
DS=$(curl -s $AUTH "$GRAFANA_URL/api/datasources")
DS_COUNT=$(echo "$DS" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
DS_UIDS=$(echo "$DS" | python3 -c "import json,sys; [print(d['uid']) for d in json.load(sys.stdin)]" 2>/dev/null)
DS_READONLY=$(echo "$DS" | python3 -c "import json,sys; d=json.load(sys.stdin); print('yes' if all(x.get('readOnly') is True for x in d) else 'no')" 2>/dev/null)
for uid in "${EXPECTED_DATASOURCES[@]}"; do
  if echo "$DS_UIDS" | grep -qx "$uid"; then
    ok "datasource provisioned: $uid"
  else
    fail "datasource missing from Grafana: $uid"
  fi
done
if [ "$DS_READONLY" == "yes" ]; then
  ok "all $DS_COUNT datasources read-only (no UI edits)"
else
  fail "a datasource is not read-only"
fi
if [ "$DS_COUNT" != "${#EXPECTED_DATASOURCES[@]}" ]; then
  fail "datasource count mismatch: Grafana has $DS_COUNT, Git declares ${#EXPECTED_DATASOURCES[@]}"
fi

# Dashboards.
for uid in "${EXPECTED_DASHBOARDS[@]}"; do
  RESP=$(curl -s $AUTH "$GRAFANA_URL/api/dashboards/uid/$uid")
  CHECK=$(echo "$RESP" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    meta = d.get('meta', {})
    print('ok' if meta.get('provisioned') is True and d.get('dashboard', {}).get('uid') == '$uid' else 'bad')
except Exception:
    print('bad')
" 2>/dev/null)
  if [ "$CHECK" == "ok" ]; then
    ok "dashboard provisioned: $uid"
  else
    fail "dashboard missing/unprovisioned: $uid"
  fi
done

# Alert rules (provisioning API — the definition).
ALERTS=$(curl -s $AUTH "$GRAFANA_URL/api/v1/provisioning/alert-rules")
ALERT_UIDS=$(echo "$ALERTS" | python3 -c "import json,sys; [print(r['uid']) for r in json.load(sys.stdin)]" 2>/dev/null)
ALERT_COUNT=$(echo "$ALERT_UIDS" | grep -c .)
for uid in "${EXPECTED_ALERTS[@]}"; do
  if echo "$ALERT_UIDS" | grep -qx "$uid"; then
    ok "alert rule provisioned: $uid"
  else
    fail "alert rule missing from Grafana: $uid"
  fi
done
if [ "$ALERT_COUNT" != "${#EXPECTED_ALERTS[@]}" ]; then
  fail "alert rule count mismatch: Grafana has $ALERT_COUNT, Git declares ${#EXPECTED_ALERTS[@]}"
fi

# Alert rules (live evaluation — the rule is actually evaluating, provenance file).
LIVE=$(curl -s $AUTH "$GRAFANA_URL/api/prometheus/grafana/api/v1/rules")
LIVE_UIDS=$(echo "$LIVE" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    for group in data.get('data', {}).get('groups', []):
        for rule in group.get('rules', []):
            if rule.get('provenance') == 'file':
                print(rule.get('uid'))
except Exception:
    pass
" 2>/dev/null)
LIVE_OK=1
for uid in "${EXPECTED_ALERTS[@]}"; do
  if ! echo "$LIVE_UIDS" | grep -qx "$uid"; then
    fail "alert rule not evaluating (missing from live rules): $uid"
    LIVE_OK=0
  fi
done
if [ "$LIVE_OK" = "1" ]; then
  ok "all ${#EXPECTED_ALERTS[@]} alert rules evaluating (live, provenance file)"
fi

# Manual UI edit attempt against a provisioned dashboard must be refused.
SAMPLE_UID="${EXPECTED_DASHBOARDS[0]}"
RESP=$(curl -s -w '\n%{http_code}' $AUTH -X POST "$GRAFANA_URL/api/dashboards/db" \
  -H 'Content-Type: application/json' \
  -d "{\"dashboard\":{\"uid\":\"$SAMPLE_UID\",\"title\":\"UI EDIT ATTEMPT\"},\"overwrite\":true}")
BODY="${RESP%$'\n'*}"
CODE="${RESP#*$'\n'}"
if [[ "$CODE" == "400" && "$BODY" == *"Cannot save provisioned dashboard"* ]]; then
  ok "UI edit attempt on provisioned dashboard refused ($SAMPLE_UID)"
else
  fail "UI edit attempt was not refused (HTTP $CODE, body: $BODY)"
fi

# ---------- summary --------------------------------------------------------------
echo
if [ "$FAILURES" -gt 0 ]; then
  echo "CLEAN-START TEST: FAIL ($FAILURES failing check(s))" >&2
  exit 1
fi
echo "CLEAN-START TEST: PASS"
echo "  dashboards re-provisioned from Git with no UI step (${#EXPECTED_DASHBOARDS[@]} dashboards, ${#EXPECTED_DATASOURCES[@]} datasources, ${#EXPECTED_ALERTS[@]} alert rules, all read-only / file-provenance)."
