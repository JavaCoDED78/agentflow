# Runbook — Controlled redrive of a held DLQ message

> **Status:** Accepted
> **Owner:** engineering (on-call)
> **Task:** `S12-12`
> **Upstream reference:** `audit-contract.md` (`READ`/`DLQ` operator action, §4.6),
> `durable-execution.md` (outbox → inbox delivery model), `module-api.md` (`DlqRedriveService`,
> the `dlq_redrive_attempts` table), `ADR-022-messaging-audit-edge.md` (the `messaging → audit`
> edge), and the DLQ inspection runbook / `DlqInspectionController` (how to open the held message).

This runbook is for the operator who holds `ops:dlq` and has already **inspected** a held message
(`GET /api/v1/ops/dlq/{dlqId}`, `READ`/`DLQ`, audited) and concluded that the business change behind
it is safe to run again. It says **when** to redrive, **how**, what the response means, and how to
**confirm** the redrive took effect. It never suggests a raw database edit — the redrive is a
controlled, idempotent, audited API.

## 1. Before you touch it: redrive is not a blind retry

Redrive re-publishes the **original** failed envelope under a **fresh** id so the inbox re-runs the
business change once. It is safe to redrive only when:

1. **You have inspected the message** (`GET /api/v1/ops/dlq/{dlqId}`) and understand *why* it
   dead-lettered, and the cause is now cleared (e.g. a transient provider outage has recovered).
2. **The inbox shows the change did *not* complete.** Redrive is **refused (`409`) if the inbox
   already has a `PROCESSED` row** for that event — the business change already completed and must
   not be duplicated. It is also refused (`409`) if the inbox row is `PENDING` (an in-flight
   delivery). Only a `FAILED`/absent inbox row means the change truly never took effect.
3. **You have a reason.** The API requires a non-blank operator reason (recorded, redacted, in the
   audit trail — a redrive without a reason is an unaccounted re-delivery).

> **Idempotent by design.** A redrive is recorded once per DLQ record (`dlq_redrive_attempts`,
> `dlq_id` PRIMARY KEY). A second redrive of the same record returns the **same** re-published id
> (`200`) rather than a second envelope — so clicking "redrive" twice is safe and never duplicates.
> The first redrive *is* the redrive; there is nothing to "redo".

## 2. What redrive does (the mechanics)

The DLQ holds only a **pointer** to a failed `outbox_events` row (the payload lives in the outbox).
`DlqRedriveService.redrive(dlqId, reason)`:

1. Resolves the DLQ record and the original outbox envelope (`404` if either is gone).
2. **Idempotency check** — if this record was already redriven, returns the existing result (`200`),
   re-publishing nothing.
3. **Inbox guard (invariant 2)** — reads the inbox by `(consumer, event_id)`; a `PROCESSED` or
   `PENDING` row refuses with `409` (the terminal side effect is never duplicated).
4. Re-publishes the original envelope under a **fresh `event_id`** (the inbox dedup is keyed by
   `(consumer, event_id)`, so a fresh id is a fresh claim and the change runs **once**, never a
   same-event re-run).
5. Appends the `dlq_redrive_attempts` linkage row and records `READ`/`DLQ` in the audit trail —
   **all three in one transaction** (invariant 3): they commit or roll back together, so the audit
   never diverges from the re-publish.

The re-published envelope carries the original tenant / aggregate / topic / payload, so the
consumer re-checks the same tenant context (invariant 1).

## 3. How to redrive

```
POST /api/v1/ops/dlq/{dlqId}/redrive
Authorization: Bearer <token with ops:dlq>
Content-Type: application/json

{ "reason": "<why the cause is cleared and it is safe to re-run>" }
```

Requires the `ops:dlq` permission — an ordinary `tenant:*` principal is denied `403`. The `tenant`
and `actor` are read from configuration (the trusted security context), **never** from the request
(invariant 1).

## 4. What the response means

| Code | Meaning | Action |
|---|---|---|
| `200` | Re-published. Body carries `requeuedEventId` (the fresh envelope id) and the operator context. | §5 — confirm it landed, then confirm the consumer processed it. |
| `200` (replay) | `requeuedEventId` equals the **first** redrive's id — this was a duplicate click, nothing re-published. | No action; idempotent. |
| `400` | Blank operator reason. | Resubmit with a reason. Nothing was written. |
| `403` | Caller lacks `ops:dlq`. | Use a token with `ops:dlq` (see `local-auth.md`). |
| `404` | No DLQ row with that id, or the original outbox envelope is gone. | Confirm the id from `GET /api/v1/ops/dlq`; do not re-run against a guessed id. |
| `409` | **Inbox already `PROCESSED` or `PENDING`** — the change completed or is in flight; redrive refused so it is not duplicated. | **Stop.** Do not redrive. Re-run as a *new* run if the change genuinely needs to run again (`workflow-recovery.md` / create-run). |
| `503` | No database wired. | Operational — the store is unavailable, not empty. See §6. |

## 5. Confirm the redrive took effect

1. **The re-published envelope is in the outbox** (fresh event id, same tenant/payload):
   ```sql
   SELECT event_id, consumer_name, topic, tenant_id, created_at
   FROM   outbox_events
   WHERE  requeued_event_id IS NOT NULL          -- see dlq_redrive_attempts.event_id → requeued_event_id
     AND  event_id = '<requeuedEventId from the 200 body>';
   ```
   Or, directly via the linkage row:
   ```sql
   SELECT event_id, requeued_event_id, operator, created_at
   FROM   dlq_redrive_attempts
   WHERE  dlq_id = '<dlqId>';
   ```
2. **The consumer delivered it.** Confirm the business side effect happened (the downstream system,
   or the consumer's own success log), not just that the envelope was published.
3. **The audit trail recorded it** — one `READ` / `DLQ` row for the operator action, tenant-scoped:
   ```sql
   SELECT actor, resource_type, resource_id, created_at
   FROM   audit_events
   WHERE  action = 'READ' AND resource_type = 'DLQ' AND resource_id = '<dlqId>';
   ```

## 6. When to escalate

| Signal | Likely cause | Action |
|---|---|---|
| `409` on redrive | The change already completed or is in flight (invariant 2). | Stop. Do not retry against the same DLQ record — run the change again as a **new run**, if at all. |
| Repeated `404` after inspecting | Wrong id, or the original outbox envelope was purged. | Re-confirm the id from the inspection list; do not guess ids. |
| `503` on every redrive | No database connection wired (the store is unavailable, never empty). | Operational outage — the DLQ/outbox/inbox store is down; do not treat as "nothing to redrive". |
| Envelope published, consumer never delivers | The root cause (provider/tool) did not actually clear. | Re-inspect the dead-letter; do not redrive again — fix the dependency first. |

## 7. Reference: identifiers used in this runbook

| Concept | Identifier / where it lives |
|---|---|
| Redrive API | `POST /api/v1/ops/dlq/{dlqId}/redrive` → `200 DlqRedriveResult {dlqId, originalEventId, requeuedEventId, topic, consumerName, reason, operator, createdAt}` |
| Inspection API | `GET /api/v1/ops/dlq/{dlqId}` → `200 DlqMessageDetailDto` |
| Permission | `ops:dlq` — `Permission.OPS_DLQ`, `AccessMatrix` |
| Held message store | `dlq_messages` (owner: `messaging`) |
| Re-published envelope | `outbox_events` (fresh `event_id`) |
| Idempotency linkage | `dlq_redrive_attempts` (`dlq_id` PRIMARY KEY) |
| Audit record | `audit_events` — `action = 'READ'`, `resource_type = 'DLQ'`, tenant-scoped |
| Inbox source of truth | `inbox_messages` (status `PROCESSED`/`PENDING` refuses `409`) |
| Metric label hygiene | No `tenantId`/`runId`/`dlqId` in metric labels (invariant 9) — identify by id in traces/logs/tables only |
