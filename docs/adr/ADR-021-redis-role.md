# ADR-021. Redis is a helper component: correctness never depends on it

- **Status:** Accepted
- **Date:** 2026-11-19
- **Task:** S02-17 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** the stack-table entry that lists "Redis Open Source" as cache and
  ephemeral coordination without a bounded statement of use. Redis was in the stack
  from S01-13; this record fixes *what it may and may not hold* so it can never become
  a second source of truth.
- **Superseded by:** none

## Context

The runtime stack carries Redis (Open Source, pinned `redis:8.10.2`) alongside
PostgreSQL, Kafka and Garage (S01-13, S01-16). It is listed as "cache / ephemeral
coordination" and is reachable from the API for rate limiting. Postgres is the
source of truth for workflow state (`ADR-002`), and the lease, outbox and
idempotency model in `ADR-003` / `ADR-004` / `ADR-011` already live in PostgreSQL
and Kafka.

Redis is fast, convenient and shared across replicas, which makes it tempting to
store anything that must be *correct*: run state, the run lease, idempotency keys,
inbox records, permission/allowlist state, usage or cost counters. Each of those
usages quietly breaks an invariant — it moves the source of truth away from
PostgreSQL, turns "losing Redis must not lose a run" into a lie, and makes
availability of a cache gate correctness. Without a written boundary a later task
adds one more Redis-owned thing and the invariants erode by degrees.

We need a written boundary on Redis that every backlog task can be checked
against, because Redis appears as a dependency in several future tasks
(rate-limit state in S04-17, S12-19, S12-20; a runs cache in S14-18; the Redis
outage test in S14-08; the Kubernetes wiring in S15-20) and each must stay inside
it.

## Decision

Redis is a **helper / caddy component**. Its correctness never affects system
correctness. This constrains its use, its keys, its failure behaviour and where it
runs.

**Permitted uses only.** Redis holds one of:

1. **Rate-limit state** — a distributed rate limiter shared across API replicas
   (S12-19, S12-20). A protective throttle, not a correctness gate.
2. **A rebuildable cache** — a derived, recomputable copy of data that also lives
   in PostgreSQL (for example `GET /runs/{id}`, S14-18). Losing it only costs read
   amplification, never a wrong answer.
3. **Short-lived coordination whose loss does not change a result** — a transient,
   re-derivable value (a lock whose expiry simply causes "start over", a one-shot
   coordination token). If losing it could change an outcome, it does not belong in
   Redis.

**Prohibited uses — never.** Workflow state, the run lease / fencing
(`ADR-011`), idempotency keys, the inbox, access rights / allowlists, and usage or
cost counters. These stay in PostgreSQL or Kafka. A tenant is derived from verified
context, so Redis never holds authorization material (`ADR-007`).

**Keys, TTL and content.** Every key is built by one builder with the single
format `agentflow:{env}:{purpose}:{tenantId}:…`, where `purpose` is a closed set
(`ratelimit`, `cache`). Writes without a TTL are rejected; a tenant-scoped
`purpose` with no `tenantId` is rejected. Keys and values never contain secrets,
PII, model prompts or document text; values are versioned and size-bounded.
Cross-tenant separation lives in the key namespace, not as a post-filter.

**Failure behaviour — fixed per use before the code lands:**

- Rate-limit state → **fail open**: on Redis unavailability or a `command timeout`,
  fall back to a bounded **in-memory** limiter on the same instance, and write a
  metric + log. Rate limiting is a protective throttle, not an authorization gate,
  so availability must not collapse to `5xx`; a per-replica in-memory limit is
  acceptable and documented as such. The limiter output is never used for
  authentication or authorization.
- Rebuildable cache → **fail open**: a miss or an unavailable Redis falls straight
  through to PostgreSQL, the source of truth. The cache is a recomputable copy; a
  bounded TTL limits staleness and PostgreSQL still enforces tenant isolation.
- Short-lived coordination → **fail closed** unless a safe in-memory fallback for
  the instance exists; loss means "start over", never "commit a wrong result".

**Topology.** Redis runs in the `core` Compose profile (and `full`), same pinned
image as S01-13. Locally the **API** role connects to it (rate limiter / quota in
S12-19 / S12-20); the **worker** role does not connect to Redis — it reads durable
state from PostgreSQL. In Kubernetes (S15-20) Redis is reachable only from
API/worker pods, its credentials come from a Secret, and a role connects only when
`agentflow.redis.enabled` is true (`S12-16`). The app boots and passes readiness
**without** Redis; Redis health sits in a non-critical group and never in
readiness or liveness (`S12-17`).

## Alternatives considered

1. **Rate limit / cache only in PostgreSQL (no Redis).** Keeps a single store, but
   a per-tenant counter under heavy load adds write amplification and lock contention
   to the source-of-truth database, and evicting stale keys is manual. Rejected for
   the hot rate-limit path; PostgreSQL remains the fallback (fail-open), so no
   correctness is traded away.
2. **Store the run lease / idempotency in Redis for speed.** Faster lookups, but it
   makes Redis a second source of truth for workflow state and directly contradicts
   `ADR-002`, `ADR-003`, `ADR-004` and `ADR-011`. Rejected outright.
3. **No boundary — let each task pick.** Cheaper short-term, but erodes the
   invariants by degrees across several tasks. Rejected in favour of the written
   boundary above, which every Redis task can be checked against.

## Consequences

- **Positive:** Redis stays a cache and nothing else; "PostgreSQL is the source of
  truth" and "losing Redis must not lose a run" (`ADR-002`) remain literally true.
  Every Redis backlog task (S04-17, S12-16…S12-21, S13-22, S14-08, S14-18, S15-20)
  can be verified against one written boundary.
- **Operational / negative:**
  - Each permitted use must carry a documented failure mode at implementation time,
    not after an outage — S14-08 exists to prove them.
  - The fail-open in-memory fallback is **per-replica**, so under Redis outage the
    global limit is looser; that is accepted and documented (S04-17), never presented
    as globally exact.
  - Key format, TTL and the no-secret/PII/prompt/document rule are enforced by the
    key builder's unit tests (S12-18), so the rule survives without manual review.
- **Follow-ups:**
  - `S12-16` Redis client + `enabled` flag and typed config; `S12-17` health
    indicator + Testcontainers fixture; `S12-18` key builder and TTL policy;
    `S12-19` the distributed rate limiter applying this fail-open rule; `S12-20`
    quota/ops; `S12-21` the `docs/architecture/redis-usage.md` runbook.
  - `S14-08` Redis outage test; `S14-18` the `GET /runs` cache decision against this
    ADR; `S15-20` the Kubernetes wiring (restricted access, Secret credentials).
  - `S13-22` instrument Redis + rate limiter so the fail-open fallback is observable.
