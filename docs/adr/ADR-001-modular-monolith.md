# ADR-001. Modular monolith first

- **Status:** Accepted
- **Date:** 2026-10-20
- **Task:** S02-05 (Sprint 2 — bounded contexts and automated architecture constraints)
- **Supersedes:** none. This is the first of the core ADRs (`ADR-001`…`ADR-007`, reserved in
  the plan §3). The formal cross-referencing ADR register is completed in S02-16; the ADR
  register in this folder is expanded as more decisions are added.

## Context

AgentFlow's core deliverable is a small durable workflow engine: it must survive a worker
restart, tolerate duplicate message delivery, enforce tenant isolation, cap the cost of AI
calls, and explain execution through traces, metrics and an audit trail. The plan §3 lists
twelve bounded contexts (`identity`, `agentcatalog`, `workflowdefinition`, `workflowexecution`,
`toolexecution`, `knowledgebase`, `ingestion`, `ai`, `usage`, `audit`, `messaging`,
`observability`) plus one optional external service (`agentflow-mcp-lab`), and §4.1 describes
three runtime roles the system has to run:

1. the REST API (auth, validation, agent/Workflow/document catalogs, status queries);
2. the workflow worker (Kafka consumer that advances run/step state);
3. the document ingestion worker (extract → chunk → embed → index).

For a startup-stage project the architecture has to serve two goals at once. First, the domain
is complex enough that it **cannot** be a single flat codebase: unstructured code and silent
cross-module calls would make tenant isolation, recovery and security impossible to reason
about. Second, splitting into independently deployed services **now** would multiply failure
modes — network boundaries, distributed transactions, per-service migrations and deployment
orchestration — before the domain model has stabilised and before any measurement shows that a
given role needs to scale on its own.

The acceptance criterion for this decision is that the code is genuinely modular — with explicit
public APIs between contexts, enforced automatically — while the deployment topology stays as
simple as possible and only splits physically when a measured need appears.

## Decision

Use a **modular monolith first**: all twelve bounded contexts live in a single Spring Boot
artifact (`apps/agentflow-app`) and a single repository, but each context has an explicit public
API and must not touch another context's internal packages (`persistence`, `web`, `worker`,
infra). Boundaries are enforced by automated architecture tests (Spring Modulith, or, as a
fallback while compatibility is verified, ArchUnit) that run as part of `./gradlew check`
(S02-08, S02-09, S02-13). The module map (`docs/architecture/module-map.md`, S02-03) and the
per-module public-API map (`docs/architecture/module-api.md`, S02-04) are the ground truth those
tests check.

Deployment follows the same rule: **one image, several roles, split only when a measured need
demands it.** In the local production-like stack (`kind`/`k3d`) and in Kubernetes the API and the
workflow worker run as **separate Deployments from the same image** with different profiles and
service identities — not separate code, not separate images. The ingestion worker runs the same
image in a worker role in the first release (S01-20) and is physically separated only when §4.1's
third deployable genuinely needs independent resources.

Physical separation of a worker into its own deployment/service is justified when at least one of
these conditions is met:

- **Divergent resource profile.** A role is heavy in a way the others are not — the ingestion
  worker's long-running Tika/PDFBox extract + embed batches versus the API's short REST
  requests.
- **Divergent scaling.** Load measurement (S16, k6) shows one role must scale independently; a
  Horizontal Pod Autoscaler is added per role only after measurement, not before (plan §12).
- **A distinct security boundary.** A role gets its own service identity and least privilege — for
  example the optional read-only tool service runs with one or two allowlisted tools and no direct
  database access to the app's credentials.
- **Independent failure domain.** A crash or restart in one role must not take the others down.

The hard constraint (plan §4.1, §12): the API Deployment must not run Kafka consumers, and worker
Deployments must not expose controllers as a public entrypoint. A role's identity is a runtime
profile, not a separate codebase.

## Alternatives considered

1. **Microservices first.** Deploy each bounded context as its own service. Pro: clear physical
   ownership and independent scaling out of the box. Con: forces distributed-systems problems —
   network-failure handling, distributed transactions, cross-service event contracts, per-service
   migrations and deployment orchestration — before the domain model is stable and before any
   evidence that a service needs to scale on its own. It directly conflicts with the "smallest
   complete change" and "no microservices for their own sake" principles, and with the invariant
   that PostgreSQL is the single source of truth (ADR-002): a cross-service call is not a local
   transaction.
2. **Monolith without modules.** A single package tree with no enforced boundaries. Con: silent
   cross-module calls make tenant isolation, recovery and security impossible to audit, and it
   fails the S02 goal of documented ownership and the module-ownership invariant. Rejected.
3. **Modular monolith now, physical split later (chosen).** Keep strong module boundaries today
   and express the topology as one image and several roles; add a separate Deployment/service only
   when a measured condition in *Decision* is met.

## Consequences

- **Positive:**
  - Genuine module ownership and an acyclic dependency graph from day one, enforced
    automatically by the architecture gate — the S02 gate ("modules have owners, boundaries are
    protected by tests") is satisfied by code, not by a diagram.
  - Deployment stays maximally simple for a startup stack: one image, one image build/CI path, a
    single Compose/Helm path, no distributed transactions.
  - The path to physical separation is already open: the §4.1 deployables are expressed as
    **roles** (profiles/roles of one image), so splitting the worker or the ingestion worker later
    is a deployment/config change, not a domain-model or API change. This keeps the
    "smaller than a microservice split" option available until measurement justifies it, and allows
    independent API/worker scaling via separate Deployments before a full split is needed.
  - Consistent with ADR-002 (PostgreSQL as source of truth) and ADR-003 (at-least-once +
    idempotency): the simplicity is safe because losing one process must not lose a run.
- **Negative / operational:**
  - A single process still starts all contexts together, so a bug in one module can take down the
    others locally. Acceptable because PostgreSQL is the source of truth (ADR-002) and because the
    first release targets a local/production-like stack, not a large production fleet.
  - One image that grows slowly or heavy in start-up if roles pile in. Mitigate by keeping the API
    image lean (no consumer/worker profiles in the API Deployment) and by measuring start-up and
    heap under load.
  - The "when to split" bar is a judgement call; document the triggering measurement (S16) so the
    split is evidence-driven, not fashion-driven.
- **Follow-ups:**
  - The architecture gate (S02-08, S02-09, S02-13) must actually fail when a module boundary is
    crossed, or the modular guarantee is only on paper.
  - Physical split of the ingestion worker (plan §4.1, third deployable) and the optional
    read-only tool service (no direct DB access) should be tracked as follow-ups tied to a measured
    condition, not done preemptively.
  - The deployment guide (S15) must record the one-image / multi-role pattern and the
    "API-does-not-consume / worker-does-not-expose-controller" constraint.
