# Changelog



# Sprint 6 — Messaging (transactional outbox / inbox)



## S06-16 — Event catalog and delivery guarantees (`S06-16`).



- **Event catalog and delivery guarantees (`S06-16`).** The event family now has a single
  human-readable catalog
  [`docs/architecture/events/README.md`](docs/architecture/events/README.md) — the human counterpart
  to the machine-readable contract family in [`contracts/events/`](contracts/events/) and the topic
  catalogue in [`docs/architecture/kafka-topics.md`](docs/architecture/kafka-topics.md). It
  enumerates the six `agentflow.*` topics with their producers and consumers, the
  `tenantId:runId` / `tenantId:documentId` partition key, the `envelope-v1` fields (and that
  `tenantId` is routing/correlation only, never an authorization source), the `schemaVersion`
  `const "1.0.0"` version-bump gate, one representative safe instance of each main event's envelope,
  the **at-least-once + idempotency** guarantee with its transactional-outbox / ack-bound publish /
  inbox-dedup / ack-after-durable-commit boundaries and the bounded-retry + DLQ policy
  (`ADR-002`…`ADR-004`, `S06-05`…`S06-11`), the safe-contract-change rules (adding an optional field
  or a new event type is compatible; changing/removing a field, its meaning or the partition key
  creates a new major topic version), and the security rules (consumer-side tenant re-check, the
  model as an untrusted planner, no high-cardinality IDs in telemetry). The guarantee is stated as
  **at-least-once with idempotency**; **exactly-once is never promised** between PostgreSQL and
  Kafka. The `⚠ planned` markers that pointed to this document are removed
  (`docs/README.md`, `docs/architecture/README.md`, and the `Related` links in `error-taxonomy.md`,
  `module-map.md`, `module-api.md` and `kafka-topics.md`). **Out of scope / follow-up:** the
  per-event payload JSON Schema files (also attributed to `S06-16` in `contracts/events/README.md` —
  the "Pending" note) are not yet created; the catalog gives representative safe instances and points
  to where those schemas will live. **Verification: documentation-only — there is no
  `./gradlew check` gate for a docs change.** Link integrity verified: `git diff --check` passes and
  every relative in-document link resolves to a present, on-topic destination; the topic names,
  envelope fields and guarantee match
  `contracts/events/json-schema/envelope-v1.json` and `docs/architecture/kafka-topics.md`. **No**
  dependency, Java code, contract, or schema change.



## S06-15 — Publish-delay / poll-load benchmark for the transactional-outbox publisher (`S06-15`).



- **Publish-delay / poll-load benchmark for the transactional-outbox publisher (`S06-15`).**
  The polling-vs-CDC trade-off is now quantified instead of left to instinct: the new
  `OutboxPublisherBenchmarkIT` (`com.example.agentflow.messaging.application`,
  `src/integrationTest`, Testcontainers on `pgvector/pgvector:0.8.6-pg18` — never `H2` per
  `AGENTS.md`) drives the real `OutboxPublisherService` (`pollOnce`) over a real PostgreSQL and a
  pooled HikariCP `DataSource`, with a synchronous fake deliverer (no broker, no model), and
  measures three things while asserting only structural invariants (so it is non-flaky): mean
  empty-poll cost with the due scan on the partial index `outbox_events_pending_idx` (never a seq
  scan); that the poll cost is **independent of total table size** (re-measured after seeding
  10 000 processed rows); and burst drain throughput. Measured baseline on the CI/dev box: empty
  `pollOnce()` **~0.98 ms** (200 polls, 1 ms index scan), still **~2.1 ms** after 10 000 rows,
  and **~1327 events/sec** draining 500 rows in 50-row batches (376 ms total). The published
  note [`docs/architecture/observability/outbox-polling-benchmark.md`](docs/architecture/observability/outbox-polling-benchmark.md)
  turns these into a **numerical CDC justification gate**: CDC becomes justified when the required
  propagation latency forces a poll interval small enough that the idle `1/T` polls/sec load
  becomes material, or when sub-poll reactivity is required. CDC is **not** connected — the note
  measures the base outbox (ADR-004) only, per the "no CDC before the base outbox" rule — and
  re-runs the same command before any future CDC ADR is considered. **Verification:**
  `./gradlew :apps:agentflow-app:integrationTest --tests "*OutboxPublisherBenchmarkIT*"` passes
  3/3 (numbers logged as observations).



## S06-14 — Reliability scenarios for the outbound/inbound messaging path (S06-14).



- **Reliability scenarios for the outbound/inbound messaging path (S06-14).** The at-least-once, never-exactly-once reliability contract for the `messaging` module (plan §6.4, invariants 6/7/12) is now proven end-to-end on real infra by the new `ReliabilityScenarioIT` integration suite (`com.example.agentflow.messaging.worker`, `src/integrationTest`, Testcontainers on `pgvector/pgvector:0.8.6-pg18` — never `H2` per `AGENTS.md`). It exercises the *whole* chain in one broker round-trip — a real `outbox_events` row, a real KRaft broker, and the consumer-side inbox — rather than each component in isolation (the sibling suites `S06-07`…`S06-13` cover each half separately): a single shared `@Container KafkaContainer` serves all three test methods, and each test seeds the DB, drains the publisher through the broker, and consumes into the inbox so the assertions observe the *committed* outcome. Three scenarios: `create_run_recovers_after_a_kafka_outage` — a run is started while the broker is unreachable, the publisher marks the request event a transient `FAILED` and schedules a bounded retry (invariant 6), the broker comes back, the retry drains, the consumer applies the business change, and the run is **completed** (the S06-14 criterion that a run is not lost across a broker outage); `a_duplicate_event_never_runs_the_business_change_twice` — a `run-requested` event is delivered to the inbox twice, and the consumer applies the business change **exactly once** (the dedup boundary, invariant 12 — a duplicate is a no-op, never a re-run); `a_crash_after_broker_ack_before_the_db_update_is_recovered_without_a_loss` — a delivery is acked to the broker but the process "crashes" before the DB update, and on recovery the event is still processed exactly once with no loss. The shared-container accumulation is accounted for: the suite asserts on event-scoped `inbox_messages` counts (`event_id = ?`), so rows left by one scenario never contaminate another's assertion — a shared `@Container` accumulates `inbox_messages` across methods (a `countByConsumerAndStatus` over *all* `event_id`s would be), and the event-scoped assertion is what keeps the suite deterministic under a real broker. **Verification:** `./gradlew :apps:agentflow-app:integrationTest` is green (3 tests, 0 failures, 0 skips) and `./gradlew :apps:agentflow-app:check` is green (unit + architecture + spotless formatting). **No** dependency, contract, schema, config, or `ADR` change — the suite is test-only and reads/asserts the existing `outbox_events` / `inbox_messages` / `workflow_runs` state.



## S06-13 — Event envelope contract tests (`S06-13`).



- **Event envelope contract tests (`S06-13`).** The committed envelope contract is now a
  checked-in CI gate rather than a schema sitting on disk: producer fixtures validate against
  [`contracts/events/json-schema/envelope-v1.json`](contracts/events/json-schema/envelope-v1.json)
  (draft 2020-12), and a breaking schema change fails `./gradlew check`. `EventContractTest`
  (`com.example.agentflow.eventcontract`) loads the schema once and validates the canonical
  producer fixture (`envelope-v1.example.json`) plus mutated variants: the fixture is accepted;
  each of the nine required fields is enforced (a parameterized test removes one at a time and
  asserts rejection); an unauthorized property is rejected (`additionalProperties: false`); the
  `schemaVersion` `const` is enforced as the envelope version-bump gate; a non-object payload and
  a non-UUID `tenantId` are rejected; the optional `causationId` is valid when absent or `null`;
  and the schema declares draft 2020-12. `EnvelopeContractValidator` is a pure test utility on the
  application's Jackson 3 (`tools.jackson`) line, so it runs the same code path as the rest of the
  app. **Build:** the test reads the committed schema directory from `-Pvent.contract.dir`, used on
  purpose — Gradle silently drops `-D` system properties carrying two or more dots, so a dotted key
  like `vent.contract.dir` would be ignored; the resolved path is passed to the forked test JVM as a
  plain `vent.contract.dir` system property, defaulting to `contracts/events/json-schema` when the
  Gradle property is absent (and skipping the suite via `@EnabledIfSystemProperty` on an IDE run).
  The dependency is test-only, pinned in the catalog (`com.networknt:json-schema-validator:3.0.8`),
  and excludes `com.fasterxml.jackson.*` so the Jackson 3 namespace stays unambiguous on the test
  classpath (no split package). **Scope:** the guard only covers the *envelope* shape against its
  committed schema; per-event payload schemas (`S06-16`) and the topic catalogue (`S06-02`) are
  separate. **Verification:** `./gradlew :apps:agentflow-app:test` and `:check` are green; a
  tampered `const` (`1.0.0` → `9.9.9`) or `additionalProperties: false` → `true` fails the suite,
  while the committed schema passes 16/16.



## S06-12 — Correlation metadata propagation (S06-12).



- **Correlation metadata propagation (S06-12).** A run's correlation context now travels with
  its event from the producer to the persisted envelope instead of `null`: `CorrelationHeaders`
  (new, `messaging.application`, immutable value type) builds the outbox `headers` from exactly the
  present fields — `correlationId`, `causationId`, `traceparent` — in a stable key order, omitting
  any that are absent (a `run-requested` event is the first of its chain, so its `causationId` is
  `null` and never serialized), and never emitting an empty header. The `workflowexecution` producer
  (`RunService`) is the sole writer: its `createAndPin` is now a 5-argument method that threads an
  optional `traceparent` (a W3C Trace-Context label, forwarded verbatim — a trace label only, never
  an authorization input, invariant 1), sets `correlationId` to the new `runId` and leaves
  `causationId` empty, and writes `CorrelationHeaders.toJson()` into `OutboxEvent.headersJson` inside
  the same transaction that appends the run row (invariant 3). `createRun(tenant, createdBy, request, fingerprint)`
  is retained as a no-traceparent overload that delegates. `WorkflowRunController` reads an optional
  HTTP `traceparent` header (absent → `null` → omitted) and passes it straight through to the
  service. **Scope:** the correlation value is `correlationId` / `causationId` / `traceparent`
  only — a correlation label, never a secret, the full prompt, or the full document (invariants 7/8),
  and the `tenantId` is *not* a header (it is the outbox row's own column; isolation stays enforced by
  the consumer, invariant 1). The **consumer-side inbox path is out of scope** — no inbox worker or
  consumer listener exists in `src/main`, so nothing reads these headers yet; this change only makes
  the data *reachable*, and the producer test asserts `causationId` survives the Kafka round-trip
  so the future consumer inherits the whole chain. **Tests:** `CorrelationHeadersTest` (DB-free:
  stable field order; null causation omitted for the first event; traceparent threaded while the chain
  id is kept; a blank traceparent is treated as absent; equivalent inputs yield byte-identical JSON),
  `RunServiceTest` additions (the request event carries `correlationId = runId`, `tenantId` from the
  stored FK and **no** `tenant` header; a request without `traceparent` omits `traceparent` and
  `causationId`; with `traceparent` it is threaded verbatim), and `OutboxEventRowMapperTest`
  (the persisted `headers` round-trips through `JdbcTemplate` unchanged). The producer/consumer
  `KafkaMessageDelivererIT` now asserts all three headers — `correlationId`, `causationId`,
  `traceparent` — arrive intact after a broker round-trip. **Verification:**
  `./gradlew :apps:agentflow-app:test` is green;
  `./gradlew integrationTest --tests *KafkaMessageDelivererIT*` is green (real PostgreSQL on
  `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`); `./gradlew :apps:agentflow-app:check`
  is green (unit + architecture + spotless). **No new external contract** — `CorrelationHeaders` is an
  internal `application`-port type (not in OpenAPI / the event JSON schema), and the envelope shape is
  unchanged. **Docs:** `docs/domain-glossary.md` §6 adds `correlation headers`;
  `docs/architecture/module-api.md` notes `messaging.application::CorrelationHeaders`.



## S06-11 — Consumer-side inbox error policy + DLQ (S06-11).



- **Consumer-side inbox error policy + DLQ (S06-11).** The messaging consumer error policy
  (plan §6.4, invariant 12) is now complete: a transient fault is retried with a bounded immediate
  retry, a permanent or malformed event short-circuits straight to a dead-letter, and neither path
  reprocesses or loses work. `InboxErrorPolicy` (new, `messaging.application`, stateless — a plain
  `defaults()` factory, no bean) classifies the failure thrown by the business change:
  `IllegalArgumentException` / `IllegalStateException` / `NullPointerException` are
  `PERMANENT` (bad input / contract violation — retrying identical input cannot succeed);
  every other throwable is `TRANSIENT` (e.g. a downstream DB / provider / broker fault).
  `InboxConsumerService` (new, the single orchestrating entry point a `workflowexecution` consumer
  builds — **not** a Spring bean, so the skeleton boots without Kafka, `S01-02`) wires the policy into
  the dedup helper: on `PERMANENT` it short-circuits the bounded immediate retry entirely and records the claim row `FAILED` (a duplicate redelivery of the same envelope is deduped as `OTHER_CLAIMED`, never a retry loop); on `TRANSIENT` it retries up to `agentflow.inbox.max-attempts` (default `3`,
  `AgentFlowProperties.Inbox.MaxAttempts`) using a **single savepoint re-rolled before each attempt**,
  rolling the partial change back while the claim row is kept, so nothing is ever marked `PROCESSED`
  on a failure (invariant 3). When the transient attempts are exhausted — or a permanent failure
  short-circuits — the consumer appends a `DlqMessage` (new domain record, `Status` `RETRY` /
  `DEAD_LETTER`) via the new `DlqService`, which records the disposition in the new
  `dlq_messages` table (`apps/agentflow-app/src/main/resources/migrations/V3_3_12__create_dlq_messages.sql`,
  `messaging`-owned, glossary §6) — one row per `(consumer_name, event_id)` (the **same** dedup key as
  the inbox), so a duplicate append is a no-op (`ON CONFLICT (consumer_name, event_id) DO NOTHING`,
  at-least-once, `ADR-003`, so an event is never dead-lettered twice). `dlq_messages` is the
  PostgreSQL side of the policy (invariant 2 — the consumer's authoritative disposition stays in
  `inbox_messages`, set through the dedup helper); it carries no `tenant_id` (the inbox is keyed by
  `(consumer, event)`, and tenant isolation is enforced by the consumer, invariant 1), `last_error` is
  redacted (invariant 8), and the composite `UNIQUE (consumer_name, event_id)` doubles as its own
  backing index. `InboxConsumeResult` now carries `RETRY` / `DEAD_LETTER` (the `FAILED` value from
  `S06-10` is removed — the disposition is `RETRY` for a retryable transient fault, `DEAD_LETTER` for
  the investigation path). `DlqService` and `DlqMessageRepository` are
  `@ConditionalOnBean(DataSource)` (optional), so the skeleton boots without a database. **Tests:**
  DB-free unit suite — `InboxErrorPolicyTest` (permanent vs. transient classification —
  `IllegalArgumentException`/`IllegalStateException`/`NullPointerException` are permanent, everything
  else transient), `InboxConsumerServiceTest` (permanent → short-circuit to `DEAD_LETTER`, never
  retried; transient → bounded immediate retry that rolls the change back while keeping the claim,
  never `PROCESSED`; a transient exhaustion → `RETRY`, never reprocessed), `DlqServiceTest` (idempotent
  append), `DlqMessageRowMapperTest`, `DlqMessageRepositoryTest` (mocked `JdbcTemplate`); the
  `dlq_messages` table is proven on a **real PostgreSQL** by `DlqMessageRepositoryIT`
  (`src/integrationTest`, Testcontainers on `pgvector/pgvector:0.8.6-pg18`, never `H2` per `AGENTS.md`):
  the migration applies, a valid `RETRY` row lands, a duplicate `(consumer_name, event_id)` is
  rejected by the unique constraint (idempotent), and `last_error` is nullable. `MigrationsTest`
  also covers version `3.3.12` and a `dlq_messages` acceptance case (a valid `RETRY` row lands and
  a duplicate `(consumer_name, event_id)` is rejected by the composite unique). **Verification:**
  `./gradlew :apps:agentflow-app:test --tests "com.example.agentflow.messaging.*"` is green (65
  messaging unit tests, 0 failures, 0 skips) and `./gradlew integrationTest --tests
  *DlqMessageRepositoryIT*` is green (4 tests, real PostgreSQL); `./gradlew :apps:agentflow-app:check`
  is green (unit + architecture + spotless formatting). **Placement:** the DB-free unit tests live in
  `src/test`; the DB-backed suite lives in `src/integrationTest` beside the other `*IT` suites, so the
  Docker-backed suite never slows the fast `check` gate. **No new external contract** — the policy
  reuses the existing `outbox_events` envelope + `event_id`, and `InboxErrorPolicy` / `InboxConsumerService`
  / `DlqService` / `DlqMessage` are internal `application`-port types (not in OpenAPI / the event JSON
  schema). **Docs:** `docs/domain-glossary.md` §6 adds `dlq_messages`; `docs/architecture/module-api.md`
  and `docs/architecture/kafka-topics.md` already document the consumer retry/DLQ policy (no change);
  `CHANGELOG.md` (`Unreleased`) records it. No dependency, Java module-boundary, or OpenAPI change.



## S06-10 — Consumer-side inbox dedup helper (S06-10).



- **Consumer-side inbox dedup helper (S06-10).** The application-facing `InboxPort` is
  implemented by `InboxDedupService`, a single-transaction boundary a `workflowexecution` consumer
  uses to apply a business state change atomically with the inbox dedup claim (invariant 3): it
  **claims** the event by inserting its `(consumer_name, event_id)` row as `PENDING` (the composite
  `PRIMARY KEY` rejects a second insert of the same pair, so a concurrent duplicate Kafka delivery
  — at-least-once, `ADR-003` — is skipped and never re-runs the change, so N concurrent duplicates
  create exactly one domain transition), **executes** the `InboxConsumerAction` business change in
  the same transaction, and on success **marks** the row `PROCESSED` (fenced to `PENDING`). The
  `TransactionCallback` cannot declare a checked exception, so every `InboxConsumerAction#execute`
  call is guarded here: a transient failure is retried up to `agentflow.inbox.max-attempts`
  (default `3`, `AgentFlowProperties.Inbox.MaxAttempts`) using a **single savepoint re-rolled
  before each attempt**, so the partial change is rolled back while the claim row is kept; nothing
  is ever marked `PROCESSED` on a failure (invariant 3), and after the attempts are exhausted the
  row is recorded `FAILED` (never `PROCESSED`) and the returned disposition is `RETRY` for the consumer retry / DLQ policy (S06-11). The
  business change is applied through the owning module's repository/service, never arbitrary or
  model-generated code (invariant 7); model output, uploaded documents and tool results handled by
  the change are untrusted data, and a redacted failure summary is stored on the row (`last_error`,
  invariant 8). The bean is wired at the `worker` layer (constructed by the consumer, like
  `OutboxPublisherService`), so the skeleton still boots without a database. **Tests:** DB-free unit
  tests for the control flow (`InboxDedupServiceTest`, a mocked `TransactionTemplate`/`TransactionStatus`
  driving the callback against a mocked `InboxMessageRepository` — claim runs first, the business
  change runs at most once, a transient failure marks `RETRY`/never `PROCESSED`), the claim write
  and the `DuplicateKeyException` → `false` rejection (`InboxMessageRepositoryTest`), and the row
  mapping (`InboxMessageRowMapperTest`); the acceptance criterion is proven on a **real PostgreSQL**
  by `InboxDedupConcurrencyIT` (`src/integrationTest`, Testcontainers): N concurrent deliveries of
  the same pair run the business change exactly once and leave exactly one `PROCESSED` row. **No new
  external contract** — the helper reuses the existing `outbox_events` envelope + `event_id`, and
  `InboxConsumeResult` / `InboxConsumerAction` are internal `application`-port types (not in
  OpenAPI / the event JSON schema).



## S06-09 — Kafka producer reliability tuning for the outbound outbox adapter (S06-09).



- **Kafka producer reliability tuning for the outbound outbox adapter (S06-09).** The
  transport-agnostic `MessageDeliverer` port now has a real Kafka-backed implementation,
  `KafkaMessageDeliverer` (a `@Service`, `@ConditionalOnProperty("agentflow.kafka.producer.enabled")`
  so the skeleton still boots without Kafka, `S01-02`). The producer client properties are bound
  from the new typed `AgentFlowProperties.Kafka.Producer` (validated `@Valid`/`@Min`): `acks=all`
  (durable before the publisher marks the row `SENT`), `delivery.timeout.ms` (a send that does not
  confirm in the bound is classified **ambiguous**, re-published at a bounded-backoff instant — a
  duplicate, never a loss, at-least-once `ADR-003`), `retries` + `retry.backoff.ms` (the producer
  retries a request-fault internally, on top of the publisher's bounded retry), and bounded
  batching (`batch.size`, `linger.ms`, `buffer.memory`). `delivery-timeout-seconds`
  is `60`, above the Kafka `delivery.timeout.ms >= linger.ms + request.timeout.ms`
  bound (`request.timeout.ms` defaults to 30s, so a lower value makes the `KafkaProducer`
  constructor reject the config with a `ConfigException`). The envelope is sent to
  `outbox_events.topic` with `outbox_events.event_key` as the partition key (per-aggregate
  ordering) and the row's correlation headers intact. `agentflow.kafka.producer.enabled` is
  **disabled** in the base profile and enabled in `local`/`kubernetes` with the bootstrap servers
  from the `AGENTFLOW_KAFKA_BOOTSTRAP` Secret. Only the bare `kafka-clients` client and the BOM-
  managed `testcontainers-kafka` are added (nothing pinned inline, BOM-managed); Kafka
  transactions are deliberately **not** used as the end-to-end safety net (invariant 3 — the
  transactional outbox `ADR-004` plus consumer-side inbox dedup `S06-10` provide correctness, not
  exactly-once Kafka). **Tests:** DB-free verification that the typed config maps to valid producer
  client properties is in the unit suite; the delivery contract is proven on a real KRaft broker by
  `KafkaMessageDelivererIT` (`src/integrationTest`) — envelope with the right topic/key/value/
  correlation headers, an unreachable broker classified `TRANSIENT_FAILURE` (never a hang), and
  producer construction validating the bound config. **Placement:** the `worker` package is internal
  (behind the `application` port) per the `messaging` `AGENTS.md`. **Docs:** the producer reliability
  settings are recorded in `docs/architecture/kafka-topics.md`. No change to the `OutboxEvent`
  domain record.



## S06-08 — Mark the outbox `published` only on broker ack, re-publish on an ambiguous outcome (S06-08).



- **Mark the outbox `published` only on broker ack, re-publish on an ambiguous outcome (S06-08).**
  The polling publisher now records a row as delivered — and stamps `published_at` — only on a
  broker **confirmation** (`MessageDeliverer.DeliverResult.DELIVERED`); an **ambiguous** broker
  outcome (`AMBIGUOUS`, e.g. a producer timeout or connection reset where the producer did not
  confirm whether the broker holds the envelope) is **never** treated as delivered: the row is
  re-published at a bounded-backoff instant (`min(baseDelaySeconds shl (attempts-1), maxDelaySeconds)`,
  capped at `maxDelaySeconds`, bounded by `maxAttempts`), so a re-publish of a delivered envelope is
  a **duplicate** (deduped by the consumer-side inbox, `S06-10`) but never a **loss** (at-least-once,
  ADR-003, invariant 6). When the bound is exhausted the row is held terminal (`next_attempt_at`
  NULL) for the auto-poll and visible to the DLQ (`S06-11`) — never silently dropped. A publisher
  that crashes **after claiming** a row but **before** recording the outcome leaves it `CLAIMED`; the
  poll now re-selects such a **stale** claim (older than `claim-timeout-seconds`) and re-publishes it.
  The `claimed_at` column (new migration `V3_3_11__add_claimed_at_to_outbox_events.sql`) is stamped
  by the publisher on every claim to identify a stale claim. `OutboxEventRepository` gained
  `selectForClaim` (re-selects a stale `CLAIMED` row older than the timeout, plus the
  `PENDING`/`FAILED` retries, oldest-first, bounded) and a fenced `claim` that can reclaim a stale
  `CLAIMED` row. No row is ever deleted before the broker confirms (invariant 6); the outbox is a
  **bounded** re-publisher, not a retry forever. Configurable via `AgentFlowProperties.Outbox.Publisher`
  (`batch-size`, `max-attempts`, `base-delay-seconds`, `max-delay-seconds`, and `claim-timeout-seconds`,
  default `300`, `@Min(1)`). **Tests:** DB-free `OutboxPublisherServiceTest` covers the retry-on-ambiguous
  outcome (within the bound) and the terminal hold after exhaustion (both assert the row is never marked
  `SENT`); the `OutboxEventRepositoryTest` asserts the fenced claim SQL and the stale-claim re-select.
  The crash-window recovery — a stale `CLAIMED` row (simulating a crashed publisher) is reclaimed and
  delivered, **no loss** — is proven end-to-end on a real database by
  `OutboxPublisherConcurrencyIT` (`OutboxPublisherConcurrencyIT.a_stale_claimed_row_is_reclaimed_and_delivered_after_a_publisher_crash`,
  Testcontainers on `pgvector/pgvector:0.8.6-pg18`). **Placement:** DB-free unit tests in
  `src/test`; the crash-window/concurrency suite in `src/integrationTest` beside the other `*IT`
  suites, so the Docker-backed suite never slows the fast `check` gate. **Docs:**
  `docs/architecture/module-api.md` (the ack-bound publisher and the `AMBIGUOUS` delivery result) and
  `docs/architecture/kafka-topics.md` (the ack-bound publish rule) record the S06-08 contract. No
  change to the `OutboxEvent` domain record.



## S06-07 — Polling outbox publisher (`OutboxPublisherService`, `S06-07`).



- **Polling outbox publisher (`OutboxPublisherService`, `S06-07`).** The `messaging`-owned
  publisher drains the `outbox_events` table to the broker in bounded batches without ever holding
  a database transaction across a network call. One poll (`pollOnce`) does four isolated steps:
  select a bounded, oldest-first batch of due rows (`PENDING`, plus a `FAILED` row whose bounded
  retry instant has arrived) via
  `OutboxEventRepository.selectForClaim(int)` — the partial
  `outbox_events_pending_idx` backs the scan, never a full table scan — then claim them atomically
  with `OutboxEventRepository.claim(List)` (a single conditional `UPDATE ... SET status='CLAIMED'
  WHERE id = ANY(?) AND status='PENDING'`, so two publishers can never claim the same row, which is
  exactly the `S06-07` acceptance criterion), then publish each claimed row OUTSIDE any transaction
  through the new transport-agnostic `MessageDeliverer` port, then record the outcome: `SENT` on
  delivery (`markSent`) or `FAILED` with a bounded-retry `next_attempt_at` on a transient fault and
  terminal for the DLQ (`S06-11`) on exhaustion / a permanent fault (`markFailed`). Retry is
  bounded — exponential backoff `min(baseDelaySeconds shl (attempts-1), maxDelaySeconds)` capped at
  `maxDelaySeconds` — so a stuck broker drains slowly instead of forever, and the config lives in
  `AgentFlowProperties.Outbox.Publisher` (`batch-size`, `max-attempts`, `base-delay-seconds`,
  `max-delay-seconds`, `@Valid`/`@Min`) rather than the code. The deliverer is optional (the Kafka
  producer is wired in `S06-09`; with none the poll is a no-op and the rows stay `PENDING`, never
  lost) and the repository is optional (boots without a database, `S01-02`); a present deliverer
  with no database fails loudly with `503`, mirroring the write path. **Invariant 6:** the attempt
  is persisted before the call and an ambiguous broker outcome is recorded in `last_error`, never
  treated as exactly-once. **Tests:** DB-free `OutboxPublisherServiceTest` (new) covers
  claim→deliver→sent, transient-failure retry, permanent-failure terminal, no-op when no deliverer,
  and the `503` with no database; `OutboxEventRepositoryTest`/`OutboxEventRowMapperTest` cover the
  bounded oldest-first scan and the claim/mark SQL. **The `S06-07` acceptance criterion is proven
  end-to-end on a real database** by the new `OutboxPublisherConcurrencyIT` integration suite on
  `pgvector/pgvector:0.8.6-pg18` (Testcontainers, never `H2` per `AGENTS.md`): with Flyway
  migrating the schema in the test, 4 concurrent publisher instances drain 200 seeded pending rows
  and the suite asserts every event is delivered exactly once (no loss, no duplicate) and that at
  least two instances made progress — proving two publishers neither lose pending events nor
  serialise onto one. **Placement (two-tier split, S03-15):** the DB-free unit tests
  (`OutboxPublisherServiceTest`, `OutboxEventRepositoryTest`, `OutboxEventRowMapperTest`) live in
  `src/test`, while the `OutboxPublisherConcurrencyIT` concurrency suite lives in
  `src/integrationTest` beside the other `*IT` suites — so the Docker-backed suite never slows the
  fast `check` gate. **Verification:** both tiers are green — the `messaging` DB-free unit package
  (22 tests) passes on `./gradlew check`, and the moved concurrency IT (1 test, real
  `pgvector/pgvector:0.8.6-pg18`) passes on `./gradlew integrationTest --tests
  *OutboxPublisherConcurrencyIT*`. No dependency, contract, or schema change beyond the new
  publisher, port and tests. **Docs:** `docs/architecture/module-api.md` records `OutboxPublisherService`
  (the target-contract publisher), its `MessageDeliverer` port and the `OutboxEventRepository`/
  `OutboxEventRowMapper` persistence classes; `docs/adr/ADR-004-transactional-outbox.md` already
  references the polling publisher.



## S06-06 — Idempotent create-run under `Idempotency-Key` (`S06-06`).



- **Idempotent create-run under `Idempotency-Key` (`S06-06`).** `POST /api/v1/runs` now answers
  idempotently: a repeat with the same key *and* the same request payload returns the original run,
  and a repeat with the same key but a *different* payload is a permanent `409 conflict` (never a
  silent overwrite). The controller (`workflowexecution.web.WorkflowRunController`, new) resolves the
  key from the `Idempotency-Key` request header when present, otherwise from the body's
  `idempotencyKey` (`CreateRunRequest`); it feeds the resolved key back into the request and
  fingerprints the *resolved* request as the lowercase-hex SHA-256 via the new opaque
  `common.Fingerprint` (invariant 8 — a one-way digest, never a secret, a full prompt or a full
  document, and never an authorization source). **The idempotency decision is owned by
  `IdempotencyKeyService`** (`workflowexecution.application`, new; named in `docs/architecture/module-api.md`
  as the create-run idempotency service): on a key its {@code findByIdempotencyKey} looks the run up
  by `(tenant_id, idempotency_key)`, and its {@code answerReplay} replays a stored run with a
  matching fingerprint (the original `RunDto`, no second run, no second event — a client that retries
  a lost response succeeds cleanly) or rejects a stored run with a mismatching fingerprint as
  `409 Conflict`. `RunService.createRun` (`workflowexecution.application`, rewritten for the target
  contract) delegates the lookup and the replay/mismatch decision to that service and is only the
  *creation* boundary: a missing run falls through to the sole creation path — `WorkflowRunRepository.create(tenantId, versionId, key,
  fingerprint, actor)` now stores the key and fingerprint alongside the `QUEUED / attempt 1` row, so
  a concurrent duplicate create collides on the `UNIQUE (tenant_id, idempotency_key)`; the
  `at-least-once` winner-resolution (re-resolve + answer the replay rather than surface the raw
  constraint error) also flows through `IdempotencyKeyService` (at-least-once,
  `ADR-003`/`ADR-004`). A create with no key stores `NULL` for both and is not deduplicated. The
  durable half is the forward-only, idempotent `migrations/V3_3_10__add_run_idempotency_fingerprint.sql`
  (S06-06 / `workflowexecution`, verified by `MigrationsTest` now covering version `3.3.10` and the
  new `run_idempotency_columns_are_nullable` assertion — the key and the fingerprint are nullable, the
  fingerprint carries its own `S06-06` column `COMMENT`). **Target-contract alignment:** the create
  entry point is `RunService.createRun(tenantId, createdBy, CreateRunRequest, payloadFingerprint)`,
  matching `docs/architecture/module-api.md` — the controller builds the `CreateRunRequest`, the
  service owns the idempotency rule, and the repository is the single keyed creation path. **Tests:** unit `WorkflowRunControllerTest` (new, MockMvc + the real `GlobalExceptionHandler`: `202` echoes
  the key/fingerprint, `409` renders as `conflict`, `400` on malformed JSON), `RunServiceTest`
  (creation boundary + delegation through `IdempotencyKeyService`: replay/id/409/concurrent-duplicate),
  `IdempotencyKeyServiceTest` (new, DB-free — `findByIdempotencyKey` delegates to the repository,
  matching fingerprint replays, mismatching is `409`, and `503` with no database), and the
  `RunServiceAtomicIT` integration suite on real `pgvector/pgvector:0.8.6-pg18`
  (Testcontainers) — same-key replay returns the original run and creates nothing new (invariant 6), a
  different payload under the same key is `409` without overwriting, plus the `S06-05` atomic-create
  and cross-tenant cases. **Verification:** `./gradlew :apps:agentflow-app:test` and
  `./gradlew integrationTest --tests *RunServiceAtomicIT* --tests *MigrationsTest*` are green; the
  OpenAPI contract (`contracts/openapi/openapi.json`, regenerated via
  `./gradlew generateOpenApiContract`) now documents `/api/v1/runs` with `202` / `400` / `409` /
  `503` and the `Idempotency-Key` parameter (the `OpenApiContractCheckTest` gate passes — the
  `/runs` path is additive). **Docs:** `docs/architecture/module-api.md` records the target
  `createRun` contract; `CHANGELOG.md` (`Unreleased`) documents it; `docs/architecture/run-state-machine.md`
  references the idempotent create. No dependency change.



## S06-05 — Transactional outbox path for creating a run (`RunService.createRun`, `S06-05`).



- **Transactional outbox path for creating a run (`RunService.createRun`, `S06-05`).**
  A run is started by a single `@Transactional` (default `REQUIRED`) use case that pins it to the
  tenant's one *active* workflow version and then appends its `workflow.run.requested.v1` event to the
  transactional outbox — so the run row and the event commit *together* or not at all (invariant 3,
  the `S06-05` acceptance criterion). The `workflowexecution`-owned `RunService.createRun` returns the
  run only after the commit; it fails `503` when no database is configured (S01-02, as
  `RunStateMachineService` does) and `404` when the workflow has no active version — a run is never
  created against a draft or a foreign version (invariant 13). The new `messaging`-owned boundary
  `OutboxService.enqueue` writes a `PENDING` `OutboxEvent` through the new `OutboxEventRepository`,
  inside the caller's transaction, so a broker failure cannot lose the event any sooner than the run.
  `WorkflowVersionService.getActiveVersion` /
  `WorkflowVersionRepository.findActiveVersion` resolve the tenant-scoped active version (the `ACTIVATED`
  row with the highest version number) behind the module boundary; `WorkflowRunRepository.create` is the
  single creation path (`QUEUED`, `attempt = 1`, pinned to the resolved version). **Tests:** unit
  `RunServiceTest` / `OutboxEventRepositoryTest` (DB-free) plus the `RunServiceAtomicIT` integration
  suite (real `pgvector/pgvector:0.8.6-pg18`, Testcontainers) that proves all three faces of the
  invariant — `create_run_commits_the_run_and_its_outbox_event_together`,
  `a_failing_outbox_append_rolls_back_the_run`, and
  `create_run_without_an_active_version_creates_nothing`. **Verification:** `./gradlew
  :apps:agentflow-app:test` and `./gradlew integrationTest --tests *RunServiceAtomicIT*` are green
  (307 unit/architecture tests + 3 integration tests, no failures, no skips).



## S06-04 — Consumer-side inbox dedup table (`inbox_messages`, `S06-04`).



- **Consumer-side inbox dedup table (`inbox_messages`, `S06-04`).**
  `apps/agentflow-app/src/main/resources/migrations/V3_3_9__create_inbox_messages.sql` is the
  `messaging`-owned migration for the consumer-side dedup store (glossary §6, plan §8.1, `ADR-003`).
  It holds one row per `(consumer_name, event_id)` pair a consumer processes, so a business change
  and its inbox record write in the *same* PostgreSQL transaction (inbox dedup in the transaction,
  `S06-10`). Kafka delivers at-least-once (`ADR-003`), so the same event can arrive many times; the
  composite `PRIMARY KEY (consumer_name, event_id)` makes a second insert of the same pair fail the
  unique constraint, so a duplicate delivery can never re-run the side effect — only the first claim
  proceeds, which is exactly the `S06-04` acceptance criterion: the DB constraint prevents two
  workers from processing one event for the same consumer. The table matches the glossary's canonical
  inbox shape (`consumer_name`, `event_id`, `processed_at`, `outcome`; PK `(consumer_name, event_id)`)
  plus the columns the `S06-04` task requires for transient-error behaviour and the `S06-03` sibling's
  retry scaffolding — a processing-`status` (`PENDING`/`PROCESSED`/`FAILED`, application-managed by the
  inbox consumer/dedup helper), `attempts` (bumped on retry), `next_attempt_at` (bounded-retry backoff
  anchor, NULL while still PENDING) and `last_error` (redacted, never a secret — invariant 8). A
  transient fault bumps `attempts` and advances `next_attempt_at` but leaves the row `PENDING` and
  `processed_at` NULL, so it stays retryable (`S06-10`: never mark a transient failure processed);
  `processed_at`/`outcome` are set only when the row durably reaches `PROCESSED`. The table stores no
  `tenant_id` / FK: the inbox is keyed by `(consumer, event)`, not by tenant, and tenant isolation is
  enforced by the consumer, which processes in its own tenant context (invariant 1); the composite PK
  doubles as its own backing index for the dedup INSERT, so no secondary index is required. The
  migration is forward-only and idempotent (`IF NOT EXISTS`), tagged `S06-04` / `messaging` in the
  table `COMMENT`, and verified in this change by the `MigrationsTest` integration suite (real
  `pgvector/pgvector:0.8.6-pg18`, Testcontainers — never `H2` per `AGENTS.md`), which now covers
  version `3.3.9` — the applied version list, the `inbox_messages` base table, the `NOT NULL` columns,
  the composite `inbox_messages_consumer_event` primary key, and a data-driven acceptance case proving
  a valid `PENDING` inbox row lands and a duplicate `(consumer_name, event_id)` is rejected by the
  unique constraint (all rolled back). **Verification:** `./gradlew integrationTest --tests *MigrationsTest*`
  is green against the real `pgvector/pgvector:0.8.6-pg18` Testcontainers image — all 11 tests apply
  `V3_3_9` in order and prove the schema, the `NOT NULL` columns, the composite primary key and the
  acceptance case; `./gradlew check` is green (unit + architecture + spotless formatting + OpenAPI
  contract gate). No dependency, Java application code, contract, or schema change beyond the
  migration. Docs: `docs/domain-glossary.md` §6 already names `inbox_messages` with `(consumer_name,
  event_id, processed_at, outcome)` and `PK (consumer_name, event_id)` (no change);
  `docs/architecture/kafka-topics.md`, `docs/adr/ADR-003-at-least-once-idempotency.md` and
  `docs/architecture/module-map.md` already reference the inbox (no change).



## S06-03 — Transactional outbox table (`outbox_events`, `S06-03`).



- **Transactional outbox table (`outbox_events`, `S06-03`).**
  `apps/agentflow-app/src/main/resources/migrations/V3_3_8__create_outbox_events.sql` is the
  `messaging`-owned migration for the DB-side transactional outbox (glossary §6, plan §8.1,
  `ADR-004`). It stores the events the workflow/ingestion modules must publish to Kafka after
  they commit their durable state, so state and the event cannot diverge — *a run is never
  accepted without its outbox event, and an event is never published without durable state*.
  The table matches the plan's canonical outbox columns (`event_id`, `aggregate_type`,
  `aggregate_id`, `topic`, `event_key`, `payload`, `headers`, `created_at`, `published_at`,
  `publish_attempts`, `last_error`) plus the two columns the `S06-03` task requires — `status`
  (`PENDING`/`PUBLISHED`/`FAILED`, application-managed by the outbox publisher) and
  `next_attempt_at` (bounded-retry backoff anchor) — and `tenant_id` → `tenants`, stored
  directly because `messaging` owns the table and scopes its own pending reads by it (the same
  pattern as the sibling `workflow_events`, `S05-09`; the envelope `tenantId` is routing only,
  never an authorization source, invariant 1). `event_id` is the idempotent publish key
  (`ADR-003`), `event_key` is the stable `tenantId:aggregateId` aggregate key that keeps all of
  one aggregate's state events in the same partition (S06-02), and `payload`/`headers`/`last_error`
  are redacted correlation/audit text — never a secret, a full prompt or a full document (invariant 8).
  One partial index, `outbox_events_pending_idx` on `(created_at) WHERE status = 'PENDING'`, backs
  the polling publisher's oldest-first bounded scan (S06-07); the atomic claim (`UPDATE ... SET
  status WHERE id = ? AND status = 'PENDING'`) is fenced by the conditional `WHERE`, so two
  publishers cannot publish the same row. The migration is forward-only and idempotent
  (`IF NOT EXISTS`), tagged `S06-03` / `messaging` in the table `COMMENT`, and verified in this
  change by the `MigrationsTest` integration suite (real `pgvector/pgvector:0.8.6-pg18`,
  Testcontainers — never `H2` per `AGENTS.md`), which now covers version `3.3.8` — the applied
  version list, the `outbox_events` base table, the `NOT NULL` columns, the `tenant_id` foreign
  key, the `outbox_events_pending_idx` index, the `S06-03` table `COMMENT`, and a data-driven
  acceptance case proving a valid PENDING event lands and the `NOT NULL` / foreign-key guards
  reject the invalid ones (all rolled back). **Verification:** `./gradlew check` is green (unit +
  architecture + spotless formatting) and `./gradlew integrationTest --tests *MigrationsTest*` is
  green against the real `pgvector/pgvector:0.8.6-pg18` Testcontainers image — the full suite (10
  tests) applies `V3_3_8` in order and proves the schema and every data-level acceptance criterion
  above (the applied version list, the `outbox_events` base table, the `NOT NULL` columns, the
  `tenant_id` foreign key, the `outbox_events_pending_idx` index and the `S06-03`/`messaging` table
  `COMMENT`, plus the acceptance case). No dependency, Java application code, contract, or schema
  change beyond the migration. Docs: `docs/domain-glossary.md` §6 already names `outbox_events` (no change).



## S06-02 — Kafka topic catalogue (`S06-02`).



- **Kafka topic catalogue (`S06-02`).** `docs/architecture/kafka-topics.md` is the new canonical
  catalogue of the six `agentflow.*` topics (glossary §6, plan §7): `run-requested`, `step-ready`,
  `run-cancelled`, `run-finished` (key `tenantId:runId`) and `ingestion-requested`, `indexed`
  (key `tenantId:documentId`). Each row documents the topic's purpose, partition key, consumer
  owner, retention and partition count; `messaging` owns every `agentflow.*` topic's contract
  (envelope, key, versioning, publisher, consumer, DLQ), and the per-topic consumers live in the
  `workflowexecution` (`agentflow.workflow-worker`) and `ingestion` (`agentflow.ingestion-worker`)
  worker roles, plus the DLQ group (`S06-11`). The partition key is a **stable aggregate key**
  (`tenantId:<aggregateId>`) so all state events of one run land in the same partition and are
  ordered — `runId` is fixed at run creation, so a re-published record re-routes to the same
  partition and dedups idempotently (`outbox_events.event_key`); the key orders a **run**, not a
  tenant. Delivery is documented as **at-least-once with idempotency** (`ADR-003`) — no
  exactly-once promise — with the transactional outbox (`S06-05`), inbox dedup
  (`(consumer_name, event_id)`, `S06-10`) and ack-after-durable-commit boundaries the topics carry.
  Retention/partition counts are recorded as configurable topic policy (dev 1–3 partitions, MVP
  3–6 per plan §7, production sized by a load test), never guessed. **Acceptance criterion met** —
  every topic documents its semantics and a consumer owner. **Docs:** the `⚠ planned (S06)` marker
  in `docs/README.md` (the tree and the "Document type → location" table) is removed;
  `contracts/events/README.md` moves the topic catalogue out of "Pending"; `error-taxonomy.md` and
  `module-map.md` split the `kafka-topics.md` / `events/README.md` Related link now that
  `kafka-topics.md` exists (the `events/README.md` half stays `⚠ planned`, `S06-16`);
  `docs/domain-glossary.md` §6 already matches (no change). **Verification: documentation-only —
  there is no `./gradlew check` gate for a docs change.** Link integrity verified: `git diff --check`
  passes and every relative in-document link in the new and edited files resolves to a present,
  on-topic destination; `git grep -n 'kafka-topics'` confirms the only remaining references are the
  plan text, the `contracts/AGENTS.md` rule statement, and the links resolved above. **No**
  dependency, Java code, contract, or schema change.



## S06-01 — Event envelope v1 contract (`S06-01`).



- **Event envelope v1 contract (`S06-01`).** `contracts/events/json-schema/` gains
  [`envelope-v1.json`](contracts/events/json-schema/envelope-v1.json) (JSON Schema, draft
  2020-12, `$id` `.../event-envelope/1.0.0.json`) and its canonical instance
  [`envelope-v1.example.json`](contracts/events/json-schema/envelope-v1.example.json). The
  envelope is the minimal payload every Kafka record carries (glossary §6):
  `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `tenantId`, `aggregateType`,
  `aggregateId`, `correlationId`, `causationId`, `payload`. `additionalProperties: false` on
  the envelope; **required** fields are every one except `causationId` (which is
  `["string","null"]` — null for the first event in a correlation chain, the triggering
  event's `eventId` otherwise). `tenantId` is correlation/routing only and is explicitly not
  an authorization source (invariant 1); the payload is an open object by design because its
  concrete shape is defined per event (S06-02/S06-16), but the schema documents that it must
  carry only redacted, non-secret data — no secret, full prompt or full document (invariants 7
  and 8). **Naming reconciliation:** the plan's task text lists `eventVersion`, but the
  canonical, already-cited field is `schemaVersion` (glossary §6, `contracts/AGENTS.md`, and
  the existing `workflow-graph.json` `schemaVersion` const `"1.0.0"`), so the envelope version
  field is `schemaVersion` (`const "1.0.0"`) and any breaking change to the envelope shape
  creates a new major schema per `contracts/AGENTS.md`; new *event types* are additive and do
  not touch this file. **Verification (real, no Gradle gate for a contract-only change):** the
  example was validated against the schema with the draft-2020-12 validator `ajv` 8.20.0 +
  `ajv-formats` 3.0.1 — 9/9 checks pass, including the positive example and the negative
  controls (missing `eventId`, extra property, non-UUID id, wrong `schemaVersion`, non-object
  payload all rejected). Run: `node validate.js D:/Project/AI/AGENTFLOW` from
  `C:/Users/andro/AppData/Local/Temp/evt-validate` (see the `RESULT: 9 passed, 0 failed`
  output). **Docs:** `contracts/events/README.md` restates the envelope and its pending items;
  the existing `docs/domain-glossary.md` §6 entry already matches (no change needed). **No**
  dependency, Java code, or existing-schema change.
