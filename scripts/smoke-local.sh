#!/usr/bin/env bash
#
# Local smoke test for the AgentFlow base stack (S01-20).
#
# Brings up a Compose profile (default: `core`) from
# infra/compose/docker-compose.yml, waits for every service in that profile to
# become `healthy` through its compose healthcheck, probes the Garage S3
# endpoint from the compose network (for profiles that run Garage), prints a
# per-service summary and tears the stack down. It is the Sprint 1 "working
# smoke test": a green run proves the base stack starts reproducibly from a
# clean checkout without any verbal instructions.
#
# Usage:
#   bash scripts/smoke-local.sh                       # core profile, then down
#   bash scripts/smoke-local.sh --profile full        # full stack, then down
#   bash scripts/smoke-local.sh --keep                # leave `core` running
#   bash scripts/smoke-local.sh --profile messaging --keep
#   bash scripts/smoke-local.sh --help
#
# Defaults to the `core` profile (PostgreSQL + pgvector, Redis, Garage) - the
# minimum runtime stack. `messaging`/`full` need KAFKA_CLUSTER_ID; the local,
# git-ignored infra/compose/.env provides it, so it is sourced automatically and
# the smoke test of the `full` profile works out of the box.
set -uo pipefail

# ---- Configuration ----------------------------------------------------------
PROFILE="core"
KEEP=0
COMPOSE_FILE="infra/compose/docker-compose.yml"
ENV_FILE="infra/compose/.env"
WAIT_SECONDS_PER_SERVICE=120
CHECK_INTERVAL=5
GARAGE_PROBE_IMAGE="alpine:3.21"
# The Garage S3 endpoint probe runs from the compose network (S01-15): a real S3
# client needs the pinned SDK (S10-03, Sprint 7) — this probe only proves the
# endpoint serves an HTTP response, which is the readiness contract.

# container name -> "Display name|host port"
declare -A SERVICE_DISPLAY=(
  [agentflow-postgres]="PostgreSQL (pgvector)|5432"
  [agentflow-redis]="Redis|6379"
  [agentflow-garage]="Garage (S3)|3900"
  [agentflow-kafka]="Kafka (KRaft)|9092"
  [agentflow-keycloak]="Keycloak|8083"
  [agentflow-ollama]="Ollama|11434"
  [agentflow-observability]="Observability (Grafana)|3000"
  [agentflow-otel-collector]="OTel Collector (OTLP ingress)|4317/4318"
  [agentflow-prometheus]="Prometheus|9090"
  [agentflow-loki]="Loki|3100"
  [agentflow-tempo]="Tempo|3200"
)

usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

# Expected running containers (by container name) per profile.
expected_services() {
  case "$PROFILE" in
    core)          echo "agentflow-postgres agentflow-redis agentflow-garage" ;;
    messaging)     echo "agentflow-kafka" ;;
    identity)      echo "agentflow-keycloak" ;;
    ai-local)      echo "agentflow-ollama" ;;
    observability) echo "agentflow-otel-collector agentflow-observability agentflow-prometheus agentflow-loki agentflow-tempo" ;;
    full)          echo "agentflow-postgres agentflow-redis agentflow-garage agentflow-kafka agentflow-keycloak agentflow-ollama agentflow-otel-collector agentflow-observability agentflow-prometheus agentflow-loki agentflow-tempo" ;;
    *)
      echo "error: unknown profile '$PROFILE' (choose core, messaging, identity, ai-local, observability or full)" >&2
      exit 2
      ;;
  esac
}

# The container's compose health status, or "missing" if not running.
container_status() {
  docker inspect --format '{{.State.Health.Status}}' "$1" 2>/dev/null || echo "missing"
}

# Garage S3 endpoint probe from the compose network (S01-15).
#
# Expected behaviour: the S3 API answers with an HTTP status line. An anonymous
# request to the root is deliberately denied by the bucket policy (the bucket is
# private, S10-02), so a 403 Forbidden is the pass shape; a connection failure
# (refused / no route / no DNS) means the endpoint is unreachable and fails the
# check. This proves the endpoint is reachable from a Compose-network client —
# the healthcheck only proves the in-container cluster is healthy.
s3_probe() {
  local out
  out=$(docker run --rm --network agentflow "$GARAGE_PROBE_IMAGE" \
        wget -S --spider -T 10 -q -S http://garage:3900/ 2>&1)
  if grep -qE 'HTTP/1\.[01]' <<<"$out"; then
    return 0
  fi
  echo "error: Garage S3 endpoint did not answer from the Compose network" >&2
  echo "       $out" >&2
  return 1
}

main() {
  local -a EXPECTED
  read -r -a EXPECTED <<<"$(expected_services)"

  printf '==> AgentFlow smoke test (profile: %s, keep=%s)\n' "$PROFILE" "$KEEP"

  # 1) Prerequisites: docker + reachable daemon.
  if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is not installed or not on PATH" >&2
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "error: the docker daemon is not reachable (is Docker Desktop running?)" >&2
    echo "       start Docker Desktop and run this script again." >&2
    exit 1
  fi

  # 2) Prerequisites: the compose file is syntactically valid for this profile.
  if ! docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" config >/dev/null 2>&1; then
    echo "error: 'docker compose config' failed for profile '$PROFILE' (see infra/compose)" >&2
    exit 1
  fi

  # Local, git-ignored values (KAFKA_CLUSTER_ID for messaging/full).
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$ENV_FILE"
    set +a
  fi

  # 3) Bring the profile up and wait for every service to become healthy.
  echo "==> Starting '$PROFILE' (docker compose up -d)..."
  if ! docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" up -d; then
    echo "error: failed to start the '$PROFILE' profile" >&2
    exit 1
  fi

  local -A STATUS=()
  local deadline=$((SECONDS + WAIT_SECONDS_PER_SERVICE))
  local all_ok=1
  while (( SECONDS < deadline )); do
    all_ok=1
    for name in "${EXPECTED[@]}"; do
      local st
      st="$(container_status "$name")"
      STATUS["$name"]="$st"
      [[ "$st" == "healthy" ]] || all_ok=0
    done
    (( all_ok == 1 )) && break
    sleep "$CHECK_INTERVAL"
  done

  # 4) Garage S3 endpoint probe (profiles that run garage).
  local s3_ok=1
  if [[ " ${EXPECTED[*]} " == *" agentflow-garage "* ]]; then
    echo "==> Probing the Garage S3 endpoint from the Compose network..."
    if ! s3_probe; then
      s3_ok=0
    else
      echo "       garage:3900 answers from the Compose network (anonymous root request denied as expected)"
    fi
  fi

  # 5) Report.
  printf '\n%-30s %-10s %s\n' "SERVICE" "PORT" "STATUS"
  for name in "${EXPECTED[@]}"; do
    printf '%-30s %-10s %s\n' "${SERVICE_DISPLAY[$name]%%|*}" "${SERVICE_DISPLAY[$name]#*|}" "${STATUS[$name]}"
  done
  if [[ " ${EXPECTED[*]} " == *" agentflow-garage "* ]]; then
    printf '%-30s %-10s %s\n' "Garage S3 (network probe)" "3900" "$([[ $s3_ok == 1 ]] && echo 'healthy' || echo 'FAIL')"
  fi

  # 6) Cleanup unless asked to keep.
  if (( KEEP == 0 )); then
    echo "==> Tearing down (docker compose down)..."
    docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" down >/dev/null
    printf '\nStopped the "%s" stack. To keep it running:\n' "$PROFILE"
    printf '  docker compose --file %s --profile %s up -d\n' "$COMPOSE_FILE" "$PROFILE"
  else
    printf '\nKept the "%s" stack running.\n' "$PROFILE"
    printf 'Stop it later with: docker compose --file %s --profile %s down\n' "$COMPOSE_FILE" "$PROFILE"
  fi

  (( all_ok == 1 && s3_ok == 1 )) && { echo "SMOKE TEST: PASS"; exit 0; }
  echo "SMOKE TEST: FAIL - not all services are healthy or the Garage S3 endpoint did not answer" >&2
  exit 1
}

# Parse arguments (positional or --profile/--keep/--help).
while (($#)); do
  case "$1" in
    --profile) PROFILE="${2:?--profile needs a value}"; shift 2 ;;
    --profile=*) PROFILE="${1#*=}"; shift ;;
    --keep) KEEP=1; shift ;;
    -h|--help) usage ;;
    *) echo "error: unknown argument '$1' (use --help)" >&2; exit 2 ;;
  esac
done

main
