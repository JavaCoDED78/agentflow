# `docs/` — documentation index

This is the **index** for the project documentation. It points to where each
topic lives and marks what exists today versus what is planned.

- It is **not** the project entry point — that is the root
  [`README.md`](../README.md).
- It is **not** the rulebook — conventions (how to write, link, version, and
  verify documentation) live in [`AGENTS.md`](AGENTS.md).
- Do **not** copy content here. Point to the source document so there is a
  single source of truth (see the *One source of truth* rule in
  [`AGENTS.md`](AGENTS.md)).

It shows where every topic lives (the tree below), links the documents that
exist today, and marks future sections with a `⚠ planned` badge so the index
stays truthful while the backlog is being filled out. The documents that exist
now are: [`docs/AGENTS.md`](AGENTS.md) (conventions),
[`docs/product-scope.md`](product-scope.md) (MVP boundaries),
[`adr/`](adr/README.md) (architecture decision records),
[`architecture/`](architecture/README.md) (internals),
[`runbooks/`](runbooks/README.md) (operational recovery),
[`threat-model/`](threat-model/README.md) (threat model) and the
[`contracts/`](../contracts/) family (machine-readable API/event contracts).

## Where things live

```text
docs/
├── README.md                          ← you are here (this index)
├── AGENTS.md                          ← documentation rules & conventions
├── product-scope.md                   ← MVP boundaries, v1 persona, in/out of scope
├── domain-glossary.md                 ← shared terms, one concept one name
├── usage-and-costs.md                 ⚠ planned — tokens, prices, budgets, audit catalogue
├── adr/                               ← architecture decision records
│   ├── README.md                      ← ADR register
│   ├── ADR-001-modular-monolith.md    ← modular monolith first (S02-05)
│   ├── ADR-002-postgresql-source-of-truth.md   ← workflow source of truth (S02-06)
│   ├── ADR-003-at-least-once-idempotency.md    ← at-least-once + idempotency (S02-06)
│   ├── ADR-004-transactional-outbox.md         ← transactional outbox (S02-06)
│   └── s3-object-storage-garage.md    ← first ADR (S01-15)
├── architecture/
│   ├── system-context.md              ← C4 context diagram: externals, trust boundaries, flows (S02-02)
│   ├── dependency-management.md       ← version catalog / BOM policy (S01-06, S01-08)
│   ├── dependency-insight.txt         ← resolved dependency graph (S01-08)
│   ├── module-map.md                  ← bounded contexts, owners, dependency direction (S02-03)
│   ├── module-api.md                  ← public API per module: services, event types, permitted DTOs, internal packages (S02-04)
│   ├── durable-execution.md           ⚠ planned (S07-17) — state machine, lease, recovery
│   ├── error-taxonomy.md                ← error classes & retryability (S02-10)
│   ├── engineering-conventions.md       ← UUID, UTC/timestamptz, injectable Clock, identifier naming (S02-11)
│   ├── api-conventions.md               ← API, pagination, Problem Details, optimistic locking, tenant lookup (S03-17)
│   ├── kafka-topics.md                ⚠ planned (S06) — topics & partition keys
│   ├── events/README.md               ⚠ planned (S06) — event catalogue
│   ├── database-migrations.md         ← migration rules: forward-only, expand/contract, forward-fix (S03-02)
│   └── database-conventions.md        ← tables, columns, indexes, versioning conventions (S03-06)
├── security/                          permissions/permissions.md ← S04-04 catalogue; tenant-claim.md ← S04-05 tenant claim contract; response-policy.md ← S04-09 403/404 masking policy; rest ⚠ planned (S11) — authn/authz, secrets policy
├── threat-model/
│   └── README.md                      ← scaffold (S01-03); full model added S11
├── workflows/
│   └── dsl-v1.md                      ⚠ planned (S05) — workflow DSL + examples
├── tools/, ai/, rag/, evaluation/     ⚠ planned — tool registry, providers, RAG, eval sets
├── observability/                     ⚠ planned (S13) — standards, metric catalogue, SLOs
└── load-tests/                        ⚠ planned — k6 scenarios, reports, environment description
```

Machine-readable contracts are **not** in `docs/`; they live in
[`../contracts/`](../contracts/) and are referenced from here only:

```text
../contracts/
├── AGENTS.md                          ← contract rules & conventions
├── openapi/                           ← REST API (/api/v1); spec added S03-12
├── events/
│   └── json-schema/                   ← envelope + event payloads (S06-01)
└── asyncapi/                          ⚠ planned — topics & producer/consumer roles
```

## Document type → location

Use this table to decide where a new document belongs. The owning
`AGENTS.md` (`docs/` or `contracts/`) is the authority; `docs/` holds human
documentation, `contracts/` holds machine-readable integration contracts.

| Document type | Lives in | Source |
|---|---|---|
| Project entry point, quickstart, ports, build/run | root `README.md` | `../README.md` |
| Documentation & code-authoring conventions | `docs/AGENTS.md` | `docs/AGENTS.md` |
| Product scope / MVP boundaries | `docs/product-scope.md` | `docs/product-scope.md` |
| Shared domain terms | `docs/domain-glossary.md` | `docs/domain-glossary.md` |
| Architecture decision records | `docs/adr/` | `docs/adr/README.md` |
| Architecture internals (modules, events, DB, etc.) | `docs/architecture/` | this index |
| Security & threat model | `docs/security/`, `docs/threat-model/` | `permissions.md` (S04-04 catalogue), `tenant-claim.md` (S04-05 tenant claim), `response-policy.md` (S04-09 masking policy); rest ⚠ planned (`S11`) |
| Workflow DSL & examples | `docs/workflows/` | ⚠ planned (`S05`) |
| Tools, AI, RAG, evaluation | `docs/tools/`, `docs/ai/`, `docs/rag/`, `docs/evaluation/` | ⚠ planned |
| Observability (metrics, dashboards, SLOs) | `docs/observability/` | ⚠ planned (`S13`) |
| Load tests | `docs/load-tests/` | ⚠ planned |
| Usage, costs, budgets, audit | `docs/usage-and-costs.md` | ⚠ planned |
| HTTP API (`/api/v1`) | `contracts/openapi/` | `../contracts/openapi/README.md` |
| Event envelope & payloads | `contracts/events/json-schema/` | `../contracts/events/README.md` |
| Kafka topics & roles | `contracts/asyncapi/` | ⚠ planned |
| Contribution & changelog rules | `CONTRIBUTING.md`, `CHANGELOG.md` | `../CHANGELOG.md` |

> ⚠ = planned. The marker marks a section that does not exist yet; remove it
> when the document is created. Do not leave ⚠ markers in implemented files.

## Conventions

The detailed rules are in [`AGENTS.md`](AGENTS.md); this section is the short
version so a new author knows the guardrails before writing.

- **One source of truth.** Link to a document instead of copying it. There is
  no "two truths" for the module map, topics, or permissions.
- **Documentation as code.** Code, docs and contracts change in one PR. A new
  module, package, table, metric, permission, event, or tool updates its owning
  doc, the matching `contracts/`, and the relevant `AGENTS.md`.
- **Examples are executable.** Commands are verified, workflow examples pass the
  validator, and `curl` matches the OpenAPI spec. A stale example is worse than
  none. Never put tokens, keys, real credentials, real data, full documents, or
  prompts in a doc or example.
- **Claims are backed by evidence.** Reliability, recovery, and performance are
  stated only when a test or a report confirms them. Exactly-once is never
  promised. A load report includes the environment description.
- **Diagrams are Mermaid inside Markdown.** See `docs/AGENTS.md` for the
  diagram rules; keep them small, annotated, and in sync with the code.
- **Links must not be broken.** Point to documents that exist; planned
  documents are marked with ⚠ above, not linked.

## Contribution conventions

- Read [`docs/AGENTS.md`](AGENTS.md) before adding or editing a document.
- Create the document where it belongs (the table above) or reuse an existing
  one; do not introduce a second document about the same topic.
- Change documentation in the same PR as the code or contract it describes.
- After creating a document, remove its ⚠ `planned` marker here.
- Read [`CONTRIBUTING.md`](../CONTRIBUTING.md) (branch, PR, test, and doc rules)
  before opening a pull request.

---

Scaffolded in **S01-21**. The `adr/` register (`S02-16`), the security and
threat-model documents (`S11`), the observability handbook (`S13`), and the final
README/CHANGELOG/link verification (`S16`) complete the documentation set.
