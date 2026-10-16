# AGENTS.md — `docs/`

Documentation as code: current, verifiable, one owner per topic. General rules are in the root `AGENTS.md`.

## Where things live

| Path | Contents |
|---|---|
| `product-scope.md`, `domain-glossary.md` | MVP boundaries, shared terms (one concept — one name) |
| `adr/` | architecture decision records + the `adr/README.md` index |
| `architecture/` | module map, durable execution, error taxonomy, Kafka topics, events, database conventions and migrations |
| `security/` | authn/authz, agent actions, secrets policy, threat-model summary |
| `threat-model/` | threat model of agent execution (assets, trust boundaries, mitigations) |
| `workflows/` | `dsl-v1.md` and workflow examples |
| `tools/`, `ai/`, `rag/`, `evaluation/` | tool registry, provider configuration, RAG pipeline, eval sets |
| `observability/` | standards, metric catalogue, SLOs |
| `runbooks/` | operator actions for alerts and failures |
| `load-tests/` | k6 scenarios, reports and environment description |
| `usage-and-costs.md` | tokens, prices, budgets, audit event catalogue |

## Rules

- **One source of truth.** The README and other documents link to a topic instead of copying it. No "two truths" about the module map, topics or permissions.
- **Documentation as Code.** Code, docs and contracts change in one PR. A new module/package/table/metric/permission/event/tool → update the relevant doc, `contracts/` and the matching `AGENTS.md`.
- **Examples are executable:** commands are verified, workflow examples pass the validator, `curl` matches OpenAPI. A stale example is worse than none.
- **Claims are backed by evidence.** About reliability, recovery and performance — only what a test or report confirms (k6, integration). Never promise exactly-once. A load report always includes the environment description (CPU/RAM, versions, partitions, limits).
- **ADR:** context → decision → alternatives → consequences → status (`Proposed`, `Accepted`, `Superseded`, `Deprecated`). A superseded ADR is not deleted — it gets a link to its successor. An ADR is needed for a new dependency between modules, bypassing the BOM, changing the persistence/migration approach, a new node type or risk class, abandoning an invariant.
- **Runbook:** symptoms and impact → diagnostics (queries, metrics, logs) → likely causes → safe actions → recovery criterion. Do not suggest manual DB updates without a controlled path; redrive respects idempotency. Every P0 alert has a runbook.
- **Threat model:** assets, trust boundaries, attacker goals, mitigations linked to tests; prompt injection, tenant isolation, SSRF and log leaks are mandatory topics.
- **Secrets and data:** no tokens, keys, real credentials or personal data in documents and examples.
- `CHANGELOG.md` (`Unreleased`: Added / Changed / Fixed / Security) is kept for notable API, event, security and operational changes; for the rest — `N/A` with a reason in the PR.
- Diagrams are Mermaid in Markdown so they live in the diff. Links must not be broken (link check in CI, S16-19).
- Do not leave "planned" markers in the README after implementation, and do not describe as done what is not in the code.
