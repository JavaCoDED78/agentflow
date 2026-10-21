# Database migrations

Flyway runs from `classpath:migrations`. Every `.sql` file here is a
**forward-only** migration (`V<version>__<snake_case_description>.sql`, for
example `V3_3_0__create_tenants.sql`); repeatable migrations are
`@<description>.sql`.

- **One owning module per table.** The migration name and its SQL `COMMENT`
  state the module that owns the table, so ownership is obvious from the file
  (for example `V3_3_0__tenants_create.sql`). Business tables belong to the
  `S03` migrations of the owning module — this folder is not the place for
  unrelated tables.
- **Forward-only.** Never edit an applied migration; add a new one (expand /
  contract for incompatible changes). The tool, rules and process are in
  [ADR-008](../../../../../../docs/adr/ADR-008-database-migrations.md).
- Migrations apply as one explicit step (`./gradlew flywayMigrate`, or a
  controlled Helm Job), never on application startup and never concurrently
  from several replicas.

> Column and table conventions are planned for
> `docs/architecture/database.md` (⚠ planned, S03-16); this README links to it
> as a note only, so it stays complete without that document.
