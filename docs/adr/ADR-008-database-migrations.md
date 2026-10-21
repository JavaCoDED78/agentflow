# ADR-008. Single migration tool: Flyway

> Copy this file to `docs/adr/ADR-NNN-<slug>.md`, fill every placeholder, then delete
> this heading. The real ADRs (`ADR-001`…`ADR-007` in the plan §3) are the reference
> to match. Every section is mandatory; delete a section only if it genuinely has no
> content and say why on one line.

- **Status:** Accepted
- **Date:** 2026-10-21
- **Task:** S03-01
- **Supersedes:** none
- **Superseded by:** none

## Context

S03-01 requires choosing **one** database migration tool. AgentFlow persists workflow
state in PostgreSQL (`ADR-002`) and applies changes as forward-only migrations that
must never be edited once applied. The invariants (`AGENTS.md`, §Invariants) constrain
the choice:

- **PostgreSQL is the source of truth**; the migration tool records schema history
  there and must not be circumvented.
- **One owning module per table / rule**; every migration belongs to a single module
  and is forward-only.
- **One persistence approach and one migration tool** (`AGENTS.md`, Fixed stack): no
  mixing of JPA/Hibernate DDL generation with a separate tool, and no two migration
  tools in parallel.
- **Migrations run as one explicit, ordered step** — never concurrently between
  replicas, never automatically on application startup (`ADR-002`/invariants: a
  startup auto-migrate race between replicas is a reliability hazard).

Stakeholders: the API role, the workflow/ingestion workers, and the deployment layer
(which runs the same migration step as an ordered job).

## Decision

Use **Flyway (12.4.0, BOM-managed via the Gradle catalog)** as the single migration
tool for the AgentFlow PostgreSQL schema.

- Migrations are plain SQL under `classpath:migrations`, applied forward-only; a
  changed applied migration is rejected, never edited.
- The PostgreSQL dialect (`flyway-database-postgresql`) is a mandatory dependency —
  Flyway Core no longer ships a PostgreSQL dialect from v10, so Flyway cannot
  recognise PostgreSQL 18 without it.
- The migration is applied by a single explicit step (`./gradlew flywayMigrate`, a
  JavaExec task running the in-repo `FlywayMigrate` entry point), later a Helm Job —
  **not** by Spring Boot's `spring.flyway` auto-configuration, which is disabled
  (`spring.flyway.enabled=false`) so migrations never run on app startup or
  concurrently between replicas.
- Connection settings come from the `FLYWAY_*` environment variables (falling back to
  the Compose `local` profile defaults), never hardcoded in application code.
- The Flyway version on the migration task's classpath is the BOM-managed one shared
  with the application, so the task and the app cannot diverge on the migration line.

Supported by `docs/adr/ADR-002` (PostgreSQL source of truth) and `ADR-003`/`ADR-004`
(forward-only, owned migrations).

## Alternatives considered

- **Liquibase.** Declarative changelogs in XML/JSON/YAML diverge from the SQL-first,
  human-reviewable migrations this project wants; YAML diffs are noisy and it is a
  second tool to keep in step with the version catalog. Rejected.
- **JPA / Hibernate `ddl-auto`.** Couples schema to the persistence layer, is not
  forward-only (drops/recreates or silently no-ops), and would violate the "one
  persistence approach, one migration tool" invariant. Rejected.
- **Manual SQL scripts applied by hand.** No versioning, no `flyway_schema_history`,
  no ordering or "already applied" tracking — fails the source-of-truth invariant.
  Rejected.
- **DbUp / other .NET-centric tools.** Weaker PostgreSQL tooling and an extra
  dependency surface; Flyway is the established, PG-native choice for this stack.
  Rejected.

## Consequences

- **Positive:** uniform SQL-first migrations with history tracked in PostgreSQL,
  BOM-managed versions, and verified PostgreSQL 18 recognition (Flyway 12.4.0 +
  `flyway-database-postgresql`). Single tool, single approach — satisfies the fixed
  stack invariant.
- **Negative / operational:** migrations are not applied automatically on startup, so
  deployment must run the explicit step (Gradle task now; Helm Job in S05+). A missed
  migration step leaves the schema out of sync; the runbook must state that the
  migration job must complete before rolling workers.
- **Follow-ups:** author the first module migrations (e.g. the tenants table) in
  S03-03, wire the migration job into Helm in S05-01, and record the `migrations/`
  location rules in the planned `docs/architecture/database.md`.
