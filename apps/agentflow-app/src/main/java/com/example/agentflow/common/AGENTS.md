# AGENTS.md — `common`

> **Owner:** `common` — shared primitives, **no business logic**.
> **Applies:** `S02-07`.
> **Authority:** `docs/architecture/module-map.md` ("Cross-cutting packages").

## Purpose

`common` holds only <b>shared, domain-neutral primitives</b>: identifiers, an injectable {@code
Clock}, the error taxonomy (`S02-10`) and secret redaction. It owns no business table and is
<b>not</b> an escape route for business rules — a domain invariant, a tenant check or a
transaction never lives here.

## Rules

- Add only truly shared, domain-neutral types. If a type expresses a domain concept (a {@code
  RunId}, a {@code ToolRiskClass}), it belongs to the owning module, not {@code common}.
- No `@Service` / `@Component` business beans, no repositories, no controllers.
