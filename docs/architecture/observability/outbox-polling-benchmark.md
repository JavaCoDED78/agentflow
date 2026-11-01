# Polling outbox — publish delay, poll load and the CDC justification gate

- **Status:** Observation (records evidence; the polling/CDC decision is **not** made here)
- **Owner:** engineering
- **Task:** `S06-15` (P1)
- **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (`S06-15`);
  [`../adr/ADR-004-transactional-outbox.md`](../adr/ADR-004-transactional-outbox.md)
  (current transactional-outbox design); `OutboxPublisherService` (`pollOnce`)
  in the `messaging` module.
- **Purpose:** quantify the two things `S06-15` asks for — **publish delay** (time from an
  outbox row being inserted to being delivered) and **poll load** (the steady-state cost of
  polling when nothing is pending) — and turn them into a **numerical gate** for deciding
  whether the polling publisher (ADR-004) should later be replaced by a Debezium CDC stream.
  It measures the baseline only; **CDC is not connected** and must not be, until the base
  outbox (ADR-004 / `S06-07`) is complete.

---

## Background

ADR-004 fixes a transactional outbox between PostgreSQL and Kafka. The `messaging` module's
`OutboxPublisherService` polls the `outbox_events` table for rows with `status = 'PENDING'`
that are due (`next_attempt_at IS NULL OR next_attempt_at <= now()`), claims them, delivers
them via `MessageDeliverer`, and marks them `SENT`. The publisher is **polling**, not event
driven: a run's event is delivered no earlier than the next poll. This note measures what
that design costs today so a future poll-vs-CDC choice can be made on numbers, not instinct.

The publisher currently uses a fixed batch of **50** rows per poll and a bounded retry; the
poll interval is a design parameter, not a code constant yet.

---

## Methodology

The benchmark is a Testcontainers integration test that runs against a **real PostgreSQL**
(`pgvector/pgvector:0.8.6-pg18`), never H2, and never a real Kafka or model:

- `OutboxPublisherBenchmarkIT` (`apps/agentflow-app/src/integrationTest/.../messaging/application/`)
  boots the real container, runs Flyway, and drives the real `OutboxPublisherService` through
  a real `OutboxEventRepository` over a pooled HikariCP `DataSource` (pool size 4, like a small
  worker). Delivery is a **synchronous fake** that only records that an event was delivered
  (no real broker) so the test is deterministic and needs no infrastructure.
- It measures three things and asserts only **structural** invariants (so it is non-flaky);
  every number is logged as an observation, never as a hard assertion:
  1. **Empty poll load** — mean time of a `pollOnce()` with zero pending rows (200 polls), and
     that the due scan uses the partial index `outbox_events_pending_idx`, not a seq scan.
  2. **Table-size independence** — re-measure the empty poll after inserting 10 000 processed
     (`SENT`) rows: the pending scan must stay an index scan and stay cheap, proving poll load
     does not grow with history.
  3. **Drain throughput** — publish a burst of 500 rows in batches of 50 across 10 polls with a
     1 s poll gap, and measure per-batch time and overall events/sec.
- Acceptance is numerical by design: a green run plus the printed numbers is the evidence. The
  same command re-runs the baseline before any future CDC ADR is considered.

```bash
./gradlew :apps:agentflow-app:integrationTest --tests "*OutboxPublisherBenchmarkIT*"
```

---

## Results

Measured on the CI/dev box: Docker 29.9.2, JDK 25, Gradle, one worker, batch 50, HikariCP pool
size 4, local `pgvector/pgvector:0.8.6-pg18`, fake synchronous deliverer.

| Measurement | Result |
|---|---|
| Empty `pollOnce()` (0 pending rows), mean over 200 polls | **0.98 ms** (196.83 ms total) |
| Empty `pollOnce()` after 10 000 processed rows inserted | **2.1 ms** — same index scan, no seq scan |
| Drain: publish 50 rows per poll (batch 50), steady state | **~33–37 ms/poll** (warm) |
| Drain: 500 rows across 10 polls | **376.72 ms** total; **1327 events/sec** |
| Index used for the due scan | `outbox_events_pending_idx` (partial), **no seq scan** in either the empty or the 10 000-row case |

The empty-poll cost (~1 ms) is a pooled connection checkout plus one **partial-index** scan
that touches only pending rows. The 10 000-row run proves the cost is **independent of total
table size**: the partial index keeps the scan on pending rows only, so history does not slow
the poll.

---

## Analysis

**Poll load (idle).** At a poll interval `T` seconds, the publisher issues `1/T` due-scan
queries per second when nothing is pending, each ~1 ms of index scan. At `T = 1 s` that is one
cheap scan per second; even at `T = 200 ms` it is ~5 scans/sec (~5 ms of scan work per second
per worker). The idle tax is small and flat, but it is a **continuous** load that polling
imposes forever — this is exactly the load a CDC stream would remove.

**Publish delay.** Because the publisher is event-free, a run's event is delivered **no earlier
than the next poll**. For a burst of `N` rows at batch 50 and interval `T`:

```
drain polls = ceil(N / 50);  drain time ≈ drain polls × (T + ~35 ms/batch)
publish delay ≈ up to T (first batch waits for the next tick) + drain time
```

So delay is dominated by `T`, not by the publish itself (~35 ms per 50 rows). The publish
*throughput* (1327 eps on this box) is well above what a single worker needs at moderate load;
the latency, not the throughput, is the polling constraint.

**Where the time actually goes.** Each poll of a full batch does one `claim` and one `markSent`
**per row** (1 + 50 writes for a 50-row batch), plus the index-scan `selectForClaim`. The
per-batch cost therefore grows with batch size. That is fine at low volume and is the right
shape to watch if batch size or frequency ever rises.

---

## The CDC justification gate (quantitative)

The note's acceptance criterion is *"the polling/CDC choice is justifiable numerically."* Use
this gate to decide when to re-measure and consider a new ADR (e.g. ADR-005, Debezium CDC):

1. **Propagation latency becomes a requirement.** If a run must reach its worker in a target
   latency `L` (say `L ≤ 500 ms p99`) and the polling worst case (`T + drain`) exceeds `L`, the
   only polling fix is a smaller `T` — which raises poll load and connection churn. When
   `poll_interval` required for the latency is small enough that poll load (item 2) becomes
   significant, **CDC is justified** (sub-poll propagation).
2. **Poll load is material.** If the required poll interval is small enough that `1/T`
   due-scan polls/sec is a meaningful, continuous share of worker capacity or database
   connection checks, **CDC is justified** (it eliminates the idle load).
3. **Between-poll reactivity.** If the system must act on a state change *before* the next
   poll, polling structurally cannot; **CDC is justified**.

**Recommendation:** keep the polling publisher (ADR-004) with a moderate interval (a few seconds;
publish delay is then dominated by `T`, which is acceptable for background run/workflow events).
Re-run this benchmark before any CDC ADR and compare like-for-like (same workload, compare CDC
propagation latency against the polling `T`), so the decision stays numerical.

---

## Caveats

- Single worker, local PostgreSQL, fake synchronous deliverer — the numbers are a **baseline**,
  not a production SLO. Relative findings (index scan, table-size independence, drain scales
  with batch size) hold regardless of hardware.
- The `DataSource` is pooled (HikariCP), so the ~1 ms empty-poll is a pooled checkout; a
  non-caching connection source would inflate every round-trip and is not representative.
- Throughput scales with batch size and parallel workers; polling has a hard ceiling of
  `1/T × batch-size`, which the gate accounts for.
