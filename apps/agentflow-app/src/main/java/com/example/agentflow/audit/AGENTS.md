# AGENTS.md — `audit`

> **Owner:** `audit` — append-only security/business audit events with immutable history.
> **Applies:** `S02-07` — package-by-feature skeleton for this module.
> **Authority:** `docs/architecture/module-map.md` (owners + dependency direction) and
> `docs/architecture/module-api.md` (public-API boundary). They are the single source of truth;
> this file restates the essentials only.

## Responsibility

append-only security/business audit events with immutable history.

## Audit action catalogue (S12-07)

The complete, canonical verb/resource vocabulary the trail can express is fixed by
[`docs/security/audit-contract.md`](../../../docs/security/audit-contract.md) and expressed in code
by the `AuditAction` / `AuditResourceType` / `AuditOutcome` enums (`domain`). Each security- and
business-sensitive action is one row; the mandatory fields (actor / tenant / resource / outcome,
plus `correlationId` / `metadataRedacted`) are fixed by that contract. The audit catalogue records which actions already leave an entry (`WIRED`: definition create/activate, tool side-effect
`INVOKE`, approval approve/reject/expiry, the DLQ inspection `READ` on the operator `DLQ` resource, S12-11) and which are pending (`START` / `CANCEL` / `UPLOAD` /
`ALLOW` / `DENY` / `UPDATE`); the pending wiring reuses the existing audit edges or adds a new one (`knowledgebase → audit`, an ADR). The operator `DLQ` resource type
is owned by `messaging`; `messaging` now reaches `audit` only through the public `AuditService` port for the DLQ inspection audit (`ADR-022`).

## Data ownership

`audit` owns exactly one table and no other module may change its migration or schema: `audit_events`.

## Package layout (internal vs public)

```text
com.example.agentflow.audit/               ← public API boundary
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
