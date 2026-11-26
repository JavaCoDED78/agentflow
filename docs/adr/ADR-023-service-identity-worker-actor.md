# ADR-023. The durable worker runs under a single stable service-principal actor

- **Status:** Accepted
- **Date:** 2026-11-26
- **Task:** S07-18 (Sprint 7 — durable execution; worker identity)
- **Supersedes:** none. Extends the audit-edge precedent (`ADR-018`) and the service-client decision
  (`S04-02`) to the worker.
- **Superseded by:** none

## Context

Every state change the durable worker performs — the claim, the run/step transitions
(`run.started`, `step.running`, …), the `workflow_step_runs` insert, the approval pause, and the
approval-expiry sweep's refusal completion — writes an audit/event record. Before `S07-18` the
actor those records carried was `agentflow.worker.instance-id` (a per-instance string), or
`agentflow-worker` only when no id was configured. That is two different shapes of the same thing:
a per-instance id that varies run to run, and a stable fallback — neither is an identity in the
sense the workers' callers should reason about ("which platform component acted?" rather than "which
host booted?").

The worker is a **platform service principal**, never a user session. It never presents a user
token: its actors on `workflow_events`, `workflow_step_runs`, `approvals` and `audit_events` — and
the `workflowexecution → audit` records it appends — are all attributed to the one stable subject,
deliberately not the per-instance lease id (which stays on the lease as `lease_owner`, ADR-011) and
never a user token nor an `agentflow-service` client secret.

Separately, the worker never called a protected API: the gap existed in that the decision was
implicit. If a future worker needs to call protected APIs or external services (e.g. an outbound
notifier), it must use the machine-to-machine `agentflow-service` client (`S04-02`) under the
minimum scope — and must fail loudly at startup when the secret is absent, never silently run
without it.

## Decision

**1. The stable service subject.** `ServiceIdentity` (`workflowexecution.application`) owns the one
stable service principal the worker runs under: `agentflow-worker`. `WorkflowWorker` derives its
`actor` from it (replacing the instance-id-or-fallback resolution), so every transition, row and
audit record the worker writes carries the same stable subject. The per-instance identity remains
on the lease (`lease_owner`) and never doubles as an actor — same value dimension, one job.

**2. The M2M client is explicit and validated.** `ServiceIdentity#worker(boolean, String)` — the
single factory the worker calls — accepts the operator's decision (`agentflow.worker.service-client-enabled`, default `false`) plus the secret (read from the `AGENTFLOW_SERVICE_CLIENT_SECRET` environment variable, never configuration or source, invariant 8). When the flag is `false` the worker issues no token and holds none — the deterministic handler is side-effect free today, so this is the honest default. When the flag is `true` and the secret is absent or blank, the factory throws `IllegalStateException` at worker construction: a clear startup failure, not a silent run that claims to use a credential it does not hold.

**3. The requester stays on the run.** The user who initiated the run is never the worker's actor. The run row keeps that requester in `workflow_runs.created_by`; the system-driven audit record — the approval-expiry `EXPIRE` row, the only system-transition audit record the worker appends today — carries both the stable service subject (`actor_subject`) and the requester, redacted into the metadata (`step=<key>,requester=<subject>`), omitting the requester segment when the run carries none.

**4. No user tokens or secrets in payloads, logs or metadata.** The `actor` is the stable subject; the audit metadata carries only structural identity (`step`, `requester`), never a token, credential or prompt (invariant 8). Kafka event payloads keep the same stable actor (the worker hands it to the transition service and the step/approval writers).

**5. System-actor audit rows carry the service principal.** The one system-actor audit record the worker appends — the `EXPIRE` row (`ApprovalService#expireOverdue`) — now names the stable service principal (`agentflow-worker`, replacing the per-scheduler `agentflow-approval-expiry`) so the attribution is uniform: the worker and its schedulers act under one stable identity, distinguishable from the requester by the metadata segment.

## Alternatives considered

1. **Keep the per-instance id as the actor.** Rejected: the actor is a fact about *who* acted, not *where* — an operator reading `workflow_events` or `audit_events` should see the platform service, not the hostname. The instance id stays on the lease, where its purpose is exactly the lease owner.
2. **Keep the per-scheduler actor (`agentflow-approval-expiry`) for the sweep.** Rejected: two stable subjects for one platform service would double the attribution vocabulary for no reason; the sweep is the worker's own machinery and should wear the same stable identity. The `step=<key>` metadata segment plus the requester segment preserve the per-item attribution that the per-scheduler subject used to give.
3. **Always require the M2M client at startup.** Rejected: the worker performs no outbound call today; requiring a secret would add a startup failure mode with no benefit and contradict the honest default (no token issued, none held).
4. **Read the secret from configuration or source.** Rejected: invariant 8 — secrets stay in the environment, never in Git, build args or configuration.
5. **Carry the approval's `requester` column as the audit metadata's requester.** Rejected: that column is the actor who triggered the gated side effect — in the current worker design, the same stable service subject — so it would be redundant with the actor and carry no new information. The run's `created_by` (the human who initiated the run) is the meaningful requester and is what the record carries.

## Consequences

- **Positive:**
  - Every system record the worker writes is uniformly attributable to the platform service under one stable subject — trivially auditable vs. the requester, which stays on the run.
  - The M2M decision is an explicit, validated config flag; the operator who enables it fails loudly at startup if the secret is absent.
  - One small change propagates to the whole worker: the single `actor` on `WorkflowWorker` reaches the transitions, the step/approval writers and the events.
- **Negative / operational:**
  - `agentflow.worker.service-client-enabled` exists only to guard the future outbound call; enabling it without actually calling a protected API remains an honest no-op (no token is issued — the flag is a guard, not a permission).
  - The `EXPIRE` audit metadata now carries the requester (`created_by`); a run begun without an actor omits the segment rather than fabricating one.
- **Follow-ups:**
  - If the worker (or a scheduler) ever performs an outbound call, the call path must read the M2M token from the `agentflow-service` client and reuse this decision — never a new ad-hoc identity.
  - The `START` / `CANCEL` audit rows (`ADR-018`, `START`/`CANCEL` CANON) reuse this identity when they are wired.

## When to write an ADR

A new trust/attribution edge or a change to an established identity contract — exactly this case
(root `AGENTS.md`; `docs/architecture/module-map.md`).
