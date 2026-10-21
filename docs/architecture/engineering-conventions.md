# Engineering Conventions — Time and Identifiers — AgentFlow

> **Status:** Accepted
> **Owner:** engineering (cross-cutting; every module applies these)
> **Task:** `S02-11`
> **Upstream reference:** `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` (§8 data model,
> §9.4 API conventions, §13 negative scenarios) · `docs/domain-glossary.md`
> (canonical identifiers) · `docs/architecture/error-taxonomy.md` ·
> `docs/adr/ADR-002`, `ADR-003`, `ADR-004` (consistency) · root `AGENTS.md`
> (invariant 10)
> **Purpose:** the canonical rules for what a *identifier* is, how *time* is
> represented and stored, and how identifiers are named. These make the target
> data model (`S03`), the `/api/v1` API (`S03-12`) and the state machine (`S05`)
> internally consistent and let time-dependent tests run against a fixed clock.

---

## Why these conventions

Three failures appear when time and IDs are chosen ad hoc:

1. **Inconsistent identifiers.** The same concept appears as `run_id` in a table,
   `runId` in a DTO, and `RUN` somewhere else, so a reader cannot trust that two
   tokens refer to the same thing. The domain glossary fixes *what* the concept is
   called; this doc fixes *how* that name is written in the database, in code and
   in the API.
2. **Lost or ambiguous time.** Storing `timestamp without time zone` with an
   assumed local zone, or `java.util.Date`/`Calendar`, silently drops the
   timezone. Two runs started at the same real instant then look different, and a
   lease expiry computed in one zone can fire early or late across DST.
3. **Untestable time.** Code that reads the system clock (`Instant.now()`,
   `System.currentTimeMillis()`) cannot be driven deterministically, so lease,
   retry and recovery tests are flaky. Time must come through an injectable
   `Clock`.

PostgreSQL is the source of truth (`ADR-002`), so these rules describe how the
relational and persistence layers express identifiers and time. Exactly-once is
never implied (`ADR-003`).

---

## Identifiers

| Rule | Choice | Reason |
|---|---|---|
| **Primary keys** | `UUID`, **`UUIDv7`** by default for generated IDs | `UUIDv7` is time-sortable and monotonic, so it indexes cleanly and preserves causal order on time-series tables (`workflow_events`, `workflow_runs`). |
| **`UUIDv4`** | only where ordering must be avoided (secrets, tokens) | random values avoid predictable ordering. |
| **Integer / sequential PKs** | **never** for business entities | guessable → ID enumeration → tenant/resource leakage. |
| **Foreign keys** | `<resource>_id`, stored `snake_case` | matches the glossary (`run_id`, `workflow_version_id`, `document_id`). |
| **`idempotency_key`** | `UUID` (server- or client-generated), unique per `(tenant_id, idempotency_key)` | idempotent create-run / upload-finalize / approval (`ADR-003`). |

Consequences:

- Every business table has exactly one UUID primary key `id`.
- A caller that knows another tenant's UUID still cannot read or change the
  resource (`404`/`403` policy); a UUID is **not** authorization.
- Never expose short, sequential or otherwise guessable IDs on the API.

---

## Time

| Rule | Choice |
|---|---|
| **Timezone** | **UTC** everywhere — no local zone at rest or on the wire. |
| **PostgreSQL** | `timestamptz` (`timestamp with time zone`) **only**; never `timestamp without time zone`. |
| **Java — point in time** | `Instant` (UTC instant, no offset) for immutable points; `OffsetDateTime` when a zone offset must be carried. |
| **Java — forbidden** | `java.util.Date`, `java.util.Calendar`, `LocalDateTime`/`LocalDate` (no zone) for persisted or transported points; `System.currentTimeMillis()`. |
| **Java — durations** | `Duration` / `LocalTime` are fine for spans, not instants. |
| **Money** | `NUMERIC` / `BigDecimal`, never `float`/`double`. |
| **Source of time** | the injected `Clock` (invariant 10) — never the system clock in domain/application code. |

Monotonic ordering on time-series tables (events, run history) follows from
`timestamptz` + `UUIDv7` (both encode creation order). If sub-millisecond
ordering is ever required, extend the index with an identity column rather than
switching the identifier type.

---

## Injectable `Clock` and fixed-clock tests

Time is produced through a single injected `Clock`. The production bean is
`systemUTC()`; time-dependent tests replace it with a fixed clock so a lease,
retry or recovery boundary is deterministic.

```java
// Production configuration — one UTC clock for the whole application.
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

```java
// Domain/application code — never call the system clock directly.
public final class LeaseService {

    private final Clock clock;

    public LeaseService(Clock clock) {   // constructor injection
        this.clock = clock;
    }

    public Instant leaseUntil(long seconds) {
        return clock.instant().plusSeconds(seconds);   // t0 is injectable
    }
}
```

```java
// Time-dependent unit test — fixed clock (the S02-11 acceptance criterion).
class LeaseRecoveryTest {

    private final Clock clock = Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final LeaseService lease = new LeaseService(clock);

    @Test
    void staleWorkerCannotExtendItsLease() {
        Instant t0 = clock.instant();
        long epoch1 = lease.claim("run-1", "worker-1", t0);
        assertEquals(1, epoch1);

        Instant t0plus30s = t0.plusSeconds(30);
        assertThat(lease.isStale("worker-1", t0plus30s)).isTrue();   // 30s > lease

        long epoch2 = lease.claim("run-1", "worker-2", t0plus30s);   // new owner wins
        assertEquals(2, epoch2);                                     // bumped (fencing)
        assertThat(lease.isStale("worker-1", t0plus30s)).isTrue();   // still stale
    }
}
```

Rules that follow:

- **Inject `Clock`** wherever a timestamp is produced: run/step `timestamps`,
  `lease_until`, `next_action_at`, outbox `created_at`, usage `recorded_at`.
- **Time-dependent tests** override it with `Clock.fixed(instant, UTC)`; the
  whole class can share one fixed clock for determinism.
- **Lease/recovery tests** use a fixed clock to make `lease_until` deterministic
  and assert fencing by `lease_epoch` (`ADR-003`).

---

## Naming conventions

The glossary defines *which* name a concept has; this table defines *how* that
name is written in each layer. Tables and columns match the glossary verbatim.

| Layer | Style | Example |
|---|---|---|
| Database table | `snake_case`, plural | `workflow_runs`, `workflow_step_runs` |
| Database column / FK | `snake_case` | `tenant_id`, `created_at`, `lease_until`, `run_id` |
| API / JSON field | `camelCase` | `runId`, `workflowVersionId`, `createdAt` |
| Enum value (DB) | `UPPER_SNAKE_CASE` | `QUEUED`, `RUNNING`, `WAITING_FOR_APPROVAL` |
| Enum value (JSON) | `snake_case` | `wait_for_approval`, `run_requested` |
| Event type | `snake_case` | `run_requested`, `run_finished` |
| Kafka topic | kebab-case + version | `agentflow.workflow.run-requested.v1` |
| Error class | `snake_case`, lowercase, Prometheus-safe | `validation`, `transient_infra`, `provider_timeout` (`S02-10`) |
| Correlation / trace id | `correlation_id` (DB/API); `traceId` / `spanId` (OpenTelemetry) | `correlation_id` |

Consequences:

- A business identifier is `run_id` in the table and `runId` in the API response
  — the glossary is the single source of truth for *which* token, this table for
  *how* it is written.
- Enum values keep one spelling per surface (DB vs JSON) so a status is never
  `RUNNING` in one place and `running` in another.
- `error_class` is lowercase snake_case so it is a valid Prometheus label and the
  canonical domain error name (`S02-10`), not `tenantId`, `runId` or `userId`
  (invariant 9).

---

## Applying these conventions

- **New table/column:** match the glossary's canonical name and this table's
  style; the migration (`S03-16`) is the only place the schema changes.
- **New API field:** `camelCase`, consistent with the field's DB column.
- **New timestamp field:** `timestamptz` at rest, `Instant` in Java, written via
  the injected `Clock`.
- **The `common` package** owns no business logic; the `Clock` bean and any shared
  time/identifier helpers live there as primitives every module depends on.

These rules are enforced by review and by the tests in this doc's spirit
(fixed-clock time tests, tenant-isolated reads by `(tenant_id, id)`); they are
not a separate automated gate.

---

## Related

- [`domain-glossary.md`](../domain-glossary.md) — the canonical *names* of the
  identifiers and timestamps these conventions format.
- [`error-taxonomy.md`](./error-taxonomy.md) — the `snake_case` `error_class`
  naming and Prometheus-safe labels.
- **`database.md`** — tables, columns, migrations and SQL conventions
  (`⚠ planned`, `S03-16`); this doc covers the time/identifier *format*, not the
  migration mechanics.
- `docs/adr/ADR-002`, `ADR-003`, `ADR-004` — PostgreSQL source of truth,
  at-least-once + idempotency, transactional outbox.
- `AGENTFLOW_ARCHITECTURE_AND_PLAN.md` — Sprint 2, `S02-11`.
