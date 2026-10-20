# Contributing to AgentFlow

Contribution rules for the AgentFlow repository. The detailed rules live in the
root [`AGENTS.md`](../AGENTS.md) and the documentation conventions in
[`docs/AGENTS.md`](docs/AGENTS.md); this file is the short, practical guide for
a new contributor. Follow them so every change stays reviewable, testable, and
safe to run.

## Before you start

- Read [`docs/AGENTS.md`](docs/AGENTS.md) (documentation conventions) and the
  root [`AGENTS.md`](../AGENTS.md) (invariants, module rules, tests) first.
- Pick a task from `AGENTFLOW_ARCHITECTURE_AND_PLAN.md`, referenced by its id
  (`S##-##`). Prefer the smallest complete change that satisfies the task's
  acceptance criteria; do not widen scope or refactor unrelated code.

## Stack and build

- Java 25 LTS, Spring Boot 4.1.x, Gradle 9.x (wrapper pinned to `9.8.1`).
- **Always** build through the wrapper, never the system Gradle:

  ```bash
  ./gradlew check          # compile, unit, architecture, formatting
  ./gradlew integrationTest  # Testcontainers (PostgreSQL, Kafka, Redis)
  ./gradlew bootRun          # run the API locally
  ```

- The wrapper's distribution is required — the system Gradle on this host is too
  old for Java 25. Do not downgrade the wrapper to 8.x; do not chase `latest`.
- Versions live in `gradle/libs.versions.toml`; reference them via `libs.*`.
  Override a Spring Framework / Security / Kafka client / Jackson version on top
  of the BOM only with an ADR.

## Branch and PR model

- One PR = one task = one problem and one outcome. Do not bundle unrelated work.
- Reference the task id (`S06-02`) in the PR description and link the plan
  section or ADR it belongs to.
- Do not push straight to `main`. Open a PR; the description must state the real
  `PASS / FAIL / not-run` outcome of the verification commands.
- The application must stay runnable after your change.

## Tests

- Use **Testcontainers** for persistence and messaging (the same PostgreSQL,
  Kafka and Redis images as Compose/CI). **Do not** use H2 instead of
  PostgreSQL.
- Cover the changed behavior: state transitions and invariants, the negative /
  failure / recovery paths, tenant isolation, and the DB / Kafka / Redis /
  security paths where relevant.
- Fix a failing test by fixing the code or the assertion's premise — never by
  disabling it, excluding it, or weakening it to pass the build.
- Keep `./gradlew check` green.

## Documentation

- **Documentation as code.** Code, docs and contracts change in one PR. A new
  module, package, table, metric, permission, event, or tool updates its owning
  `docs/` document, the matching `contracts/`, and the relevant `AGENTS.md`.
- **One source of truth.** Link to a document instead of copying it.
- **Examples are executable.** Verify commands, workflow examples, `curl`
  against the OpenAPI spec and compose against `docker compose config`. Replace a
  stale example in the same change or delete it.
- **Remove the `⚠ planned` marker** from `docs/README.md` when a planned
  document is created, and never mark as done what is not in the code.
- Keep documentation in English (the conversation with the maintainer may be in
  another language; the repo stays in English).

## Secrets and tokens

- **Never** add real credentials, API keys, JWTs, internal tokens, `.env` files
  or full documents to the repository, build args, logs, metrics, traces or
  examples.
- Use `CHANGEME` placeholders in `.env.example` and environment variables / a
  secret store for real values. Committed examples must contain only placeholders.

## Changelog

- Add a user-visible entry under `[Unreleased]` in
  [`CHANGELOG.md`](../CHANGELOG.md) (categories `Added` / `Changed` / `Fixed` /
  `Security`) for notable API, event, security or operational changes.
- If nothing in `CHANGELOG.md` needs updating, record `N/A` with a reason in the
  PR template rather than leaving an empty entry.

## Review before opening the PR

- Run `git diff --check` and inspect the full diff.
- Confirm no secrets, git-ignored files, generated artifacts or disabled tests
  changed; no unrelated destructive edits.
- Verify code, documentation, examples and API/event contracts agree.
