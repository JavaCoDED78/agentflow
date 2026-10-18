# `infra/compose/` — local infrastructure as code

Docker Compose files for the runtime stack: PostgreSQL (+ pgvector), Kafka
(KRaft), Redis, MinIO and Keycloak, organised by Compose profiles
(`core`, `messaging`, `identity`, `ai-local`, `observability`, `full`).

Images are pinned to immutable tags. Secrets are never committed; local
credentials are kept out of the repository (see `.gitignore`).

Scaffolded in **S01-03**. The core profile's PostgreSQL (S01-12) and Redis
(S01-13) services are present now, plus the `messaging` profile's Kafka (S01-14);
MinIO/Keycloak (S01-15) are added in the following tasks, and the profiles are
fully defined in **S01-16**.

## Running the core profile

```bash
docker compose --file infra/compose/docker-compose.yml --profile core up -d
docker compose --file infra/compose/docker-compose.yml --profile core ps
```

The `postgres` service becomes `healthy` through its healthcheck and keeps data
across restarts via the `agentflow-pgdata` named volume. The `redis` service
(S01-13) also reaches `healthy` via `redis-cli ping`; it persists local
development data through an append-only file (AOF, `appendfsync everysec`) on the
`agentflow-redis` named volume, so it survives a restart. Redis is a cache /
short-lived coordination store, never a source of truth — workflow state lives in
PostgreSQL, so losing Redis must not lose a run. No auth is configured on the
trusted local network; production (Kubernetes) must enable `requirepass` from a
Secret. Stop and remove both with `docker compose ... down` (add `-v` to drop the
volumes too). Real credentials come from a local `.env` (git-ignored); the
defaults are local-only and clearly marked.

## Running the messaging profile (Kafka, S01-14)

The `kafka` service runs a single combined KRaft node (broker + controller) on
the pinned image `apache/kafka:4.3.1`, which is KRaft by default — no
Zookeeper. Start it with:

```bash
docker compose --file infra/compose/docker-compose.yml --profile messaging up -d
```

### One-time setup: cluster id

KRaft needs a cluster id to format its Raft log on the first start. It is
generated once and referenced through `KAFKA_CLUSTER_ID`; the compose file fails
fast until it is set. Generate it once and export it into a local `.env` (never
committed):

```bash
KAFKA_CLUSTER_ID=$(docker run --rm apache/kafka:4.3.1 \
  /opt/kafka/bin/kafka-storage.sh random-uuid)
echo "KAFKA_CLUSTER_ID=$KAFKA_CLUSTER_ID" >> .env
```

### Listeners and readiness

- `KAFKA_LISTENERS=INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093` — the
  `INTERNAL` listener carries inter-broker traffic; `CONTROLLER` is required by
  KRaft.
- `KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:9092` — peers on the compose
  network resolve the broker by the `kafka` network alias.
- `KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093` — the single-node quorum
  voter must resolve to `localhost`, not the service name.
- The healthcheck runs `kafka-broker-api-versions --bootstrap-server localhost:9092`
  inside the container, so it does not depend on compose DNS resolving the
  advertised alias and gates dependents on the broker actually accepting I/O.

### Acceptance: publish and consume from the compose network

Run a producer and a consumer as separate containers on the `agentflow-messaging`
network so they resolve the advertised `kafka:9092`. Wrap the tools in `sh -c`
(so they are not the container's PID 1 — see below). The broker exposes a
single message per run here; this is the acceptance check for S01-14:

```bash
NET=agentflow-messaging
T=agentflow.acceptance.v1

# topic
docker run --rm --network $NET --entrypoint sh apache/kafka:4.3.1 -c \
  '/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
   --create --topic '"$T"' --partitions 1 --replication-factor 1'

# produce (acks = all)
docker run --rm --network $NET --entrypoint sh apache/kafka:4.3.1 -c \
  'printf "%s\n" "{\"eventType\":\"workflow.run.requested\",\"runId\":\"01H\",\"tenantId\":\"t-1\"}" \
   | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
   --topic '"$T"' --sync --request-required-acks -1'

# consume from a fresh group
docker run --rm --network $NET --entrypoint sh apache/kafka:4.3.1 -c \
  '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
   --topic '"$T"' --group acceptance.s0114 --from-beginning \
   --max-messages 1 --timeout-ms 15000'
```

Note: the console **producer** run as the container's PID 1 (i.e.
`--entrypoint /opt/kafka/bin/kafka-console-producer.sh` with no `sh -c` wrapper)
silently writes nothing while the **consumer** works the same way — wrap the
producer in `sh -c` so it runs as a child of `sh`. This is a console-tool
artifact only; the Spring Kafka client is unaffected.
