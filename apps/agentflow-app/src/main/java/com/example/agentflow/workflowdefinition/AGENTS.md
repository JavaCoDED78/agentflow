# AGENTS.md — `workflowdefinition`

> **Owner:** `workflowdefinition` — versioned workflow graph, DSL/graph validation and activation.
> **Applies:** `S02-07` — package-by-feature skeleton for this module.
> **Authority:** `docs/architecture/module-map.md` (owners + dependency direction) and
> `docs/architecture/module-api.md` (public-API boundary). They are the single source of truth;
> this file restates the essentials only.

## Responsibility

versioned workflow graph, DSL/graph validation and activation.

## Data ownership

`workflowdefinition` owns exactly one table (plus versions) and no other module may change its migration or schema: `workflow_definitions`, `workflow_definition_versions`.

## Package layout (internal vs public)

```text
com.example.agentflow.workflowdefinition/               ← public API boundary
├── domain/          pure model + invariants        internal
├── application/     use cases, transactions         public services + DTOs
├── web/             controllers + API DTOs          internal
├── persistence/     repositories, mappers, SQL       internal
└── worker/          Kafka listeners                 internal
```

`persistence`, `web`, `worker` and `domain` are internal: another module must not import them
(enforced by the architecture test, `S02-08` / `S02-09`).

## Rules

- Business rules stay in `application` (transactions) or `domain` (invariants); never in `web` or
  infrastructure.
- A new `module → module` edge requires an ADR (root `AGENTS.md`).
