#!/usr/bin/env bash
#
# Local smoke test for the AgentFlow base stack (S01-20).
#
# Brings up a Compose profile (default: `core`) from
# infra/compose/docker-compose.yml, waits for every service in that profile to
# become `healthy` through its compose healthcheck, prints a per-service
# summary and tears the stack down. It is the Sprint 1 "working smoke test": a
# green run proves the base stack starts reproducibly from a clean checkout
# without any verbal instructions.
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

# container name -> "Display name|host port"
declare -A SERVICE_DISPLAY=(
  [agentflow-postgres]="PostgreSQL (pgvector)|5432"
  [agentflow-redis]="Redis|6379"
  [agentflow-garage]="Garage (S3)|3900"
  [agentflow-kafka]="Kafka (KRaft)|9092"
  [agentflow-keycloak]="Keycloak|8083"
  [agentflow-ollama]="Ollama|11434"
  [agentflow-observability]="Observability (Grafana)|3000"
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
    observability) echo "agentflow-observability" ;;
    full)          echo "agentflow-postgres agentflow-redis agentflow-garage agentflow-kafka agentflow-keycloak agentflow-ollama agentflow-observability" ;;
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

  # 4) Report.
  printf '\n%-30s %-10s %s\n' "SERVICE" "PORT" "STATUS"
  for name in "${EXPECTED[@]}"; do
    printf '%-30s %-10s %s\n' "${SERVICE_DISPLAY[$name]%%|*}" "${SERVICE_DISPLAY[$name]#*|}" "${STATUS[$name]}"
  done

  # 5) Cleanup unless asked to keep.
  if (( KEEP == 0 )); then
    echo "==> Tearing down (docker compose down)..."
    docker compose --file "$COMPOSE_FILE" --profile "$PROFILE" down >/dev/null
    printf '\nStopped the "%s" stack. To keep it running:\n' "$PROFILE"
    printf '  docker compose --file %s --profile %s up -d\n' "$COMPOSE_FILE" "$PROFILE"
  else
    printf '\nKept the "%s" stack running.\n' "$PROFILE"
    printf 'Stop it later with: docker compose --file %s --profile %s down\n' "$COMPOSE_FILE" "$PROFILE"
  fi

  (( all_ok == 1 )) && { echo "SMOKE TEST: PASS"; exit 0; }
  echo "SMOKE TEST: FAIL - not all services are healthy" >&2
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
