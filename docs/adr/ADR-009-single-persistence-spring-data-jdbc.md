---
title: Single persistence approach — Spring Data JDBC
status: Accepted
date: 2026-10-22
task: S03-08
supersedes: none
superseded-by: none
---

# ADR-009. Single persistence approach — Spring Data JDBC

## Context

The monolith persists its business tables in PostgreSQL, which is the source of
truth (ADR-002). The write/read path carries the heaviest correctness burden in
the system, so the persistence approach has to satisfy hard requirements that
come straight from the invariants:

- **Tenant filter is part of the query (invariant 1).** Another tenant's row must
  be indistinguishable from a missing one, and the tenant predicate must live in
  the SQL itself (including the vector/FTS search, S10), not be applied as a
  post-filter. This requires explicit, reviewable SQL — an ORM that lazily adds
  `WHERE tenant_id = ?` only where it is told to invites the post-filter anti
  pattern.
- **State changes only through the state machine (invariant 4).** A transition is
  a conditional `UPDATE ... WHERE status = ?` plus an optimistic-version bump plus
  a `workflow_events` row in one transaction. The persistence layer must expose a
  conditional update and surface a zero-rows update as a conflict (409) — an
  ORM whose dirty-checking writes updates silently defeats this discipline.
- **Lease / fencing on every worker write (invariant 5).** Same conditional-write
  shape, checked against a `lease_epoch` column.
- **JSONB, pgvector and FTS (S08+, S10).** Payloads are JSONB; search is a
  `cosine` vector or `tsvector` query. These are first-class SQL features, not an
  ORM concern.
- **Reviewability and one approach (fixed-stack invariant).** Every table and
  business rule has one owning module; the SQL that backs it must be readable and
  reviewed, and there is exactly one persistence approach. Mixing JPA with a
  hand-rolled layer, or with jOOQ, would add a second surface to review.
- **Simplicity and the smallest complete change (working-process principle).**
  The change should introduce a minimal new surface on a stable, BOM-managed
  library, and it must not add surprising runtime behaviour (lazy loading, dirty
  checking, a session-per-request context) that fights the state-machine
  discipline.

The application currently boots without a database (S01-02): only
`flyway-core`, `flyway-database-postgresql` and the PostgreSQL JDBC driver are on
the classpath, and `spring-boot-starter-jdbc` is intentionally absent so Spring
Boot does **not** auto-configure a `DataSource`. This decision wires that
starter in and fixes the single persistence approach for the whole monolith.

## Decision

Use **Spring Data JDBC** as the *only* persistence approach for the entire
monolith (every module, every business table):

- Each table has one repository in its owning module, exposed through that
  module's public API.
- Reads and writes are plain SQL through `JdbcTemplate` / `SqlRunner`. Reads are
  always tenant-filtered and return `Optional` (never a cross-tenant row).
- State changes go through a conditional update (`WHERE ... = expected`) plus an
  optimistic-version column (S03-10) plus the outbox event, committed in a single
  `@Transactional` method owned by the owning module.
- A zero-rows conditional update maps to a conflict (409); a uniqueness-constraint
  violation maps to a conflict (409); a lost write under a lease maps to a
  conflict (409). See the mapping table below.
- JSONB columns are mapped with a `ColumnMapper`; vector/FTS search is native
  SQL in the repository (S08+, S10).
- One `DataSource` + one `DataSourceTransactionManager` per app, managed by the
  Spring Boot BOM. No `latest`/snapshot version.

The repository is active only when a `DataSource` exists
(`@ConditionalOnBean(DataSource.class)`); until the `local`/`test` profiles
configure a datasource the application still boots without a database.

## Alternatives considered

- **JPA / Hibernate.** Rejected. Its dirty-checking writes updates silently,
  which breaks the conditional-update / state-machine discipline (invariant 4)
  and makes it easy to persist a status change outside the state machine. Its
  session-per-request persistence context and lazy loading add hidden queries, and
  tenant scoping is routinely applied as a post-filter rather than in the query —
  exactly the invariant-1 violation. It also adds the largest surface of the two
  ORM candidates.

- **jOOQ.** A strong candidate and kept as the alternative for later: jOOQ gives
  type-safe, reviewable SQL with clean conditional updates, which suits it for
  query-heavy paths. It is not adopted now because (a) the whole monolith must
  use *one* approach, and (b) its code generation adds a build step and a second
  artifact to review at a time when the monolith has very few tables. If, after
  S03-09 (JSONB) and S10 (vector/FTS) expose a set of complex, query-dominated
  tables that outgrow hand-written SQL, an ADR may adopt jOOQ *for those tables
  only* — but that would then require re-evaluating the single-approach rule.

- **Hand-rolled plain JDBC.** Rejected as more boilerplate and a larger manual
  surface than the thin, BOM-managed Spring Data JDBC layer.

## Consequences

- **Positive.** The tenant filter stays in the SQL and is reviewed with the SQL
  that owns it. Conditional updates make the state-machine and lease/fencing
  discipline explicit instead of implicit. One approach means one thing to
  review, one migration line, and no hidden ORM behaviour. The change is minimal:
  one starter, one repository per table, and no new runtime layer.
- **Negative.** Spring Data JDBC does not ship optimistic locking, so the
  optimistic-version pattern (S03-10) is applied explicitly at the repository.
  Relations and joins are written as SQL by hand rather than navigated — this is
  deliberate (explicit is reviewable) but more verbose for query-heavy tables,
  which is the scenario jOOQ would later cover.
- **Follow-ups.** Map JSONB with a `ColumnMapper` (S03-09); add the
  optimistic-version column and its 409 mapping (S03-10); cover the real
  PostgreSQL behaviour with a Testcontainers integration suite (S03-15); record
  the per-table mapper pattern in `docs/architecture/database-conventions.md`.

## Mapping rules (how the invariants surface at the repository)

| Situation                                   | Behaviour at the repository                                  | Error / result         |
|---------------------------------------------|--------------------------------------------------------------|------------------------|
| Read a row by id                            | `SELECT ... WHERE id = ? AND tenant_id = ?`                  | `Optional`             |
| Cross-tenant read                           | zero rows match the `(tenant_id, id)` pair                   | `Optional.empty()`     |
| Conditional state update (0 rows affected)  | `UPDATE ... WHERE id = ? AND tenant_id = ? AND status = ?`   | conflict (409)         |
| Optimistic-version mismatch                 | `UPDATE ... WHERE ... AND version = ?`, expected != current  | conflict (409)         |
| Uniqueness constraint on write              | PostgreSQL rejects the row                                   | conflict (409)         |
| Lease held by another worker                | `UPDATE ... AND lease_epoch = ?` affected by 0 rows          | conflict (409)         |
| No `DataSource` available                   | repository bean is not registered                          | app boots without a DB |
