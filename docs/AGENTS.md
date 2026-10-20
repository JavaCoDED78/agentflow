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

## Conventions

The rules above are the *what*. This section is the *how*.

### Diagrams (Mermaid)

- Write diagrams in fenced ```mermaid blocks inside Markdown so they live in the
  diff; the index (`docs/README.md`) links to them.
- Use only diagram types that render in common viewers — `flowchart`,
  `sequenceDiagram`, `classDiagram`, `stateDiagram`, and the C4 model. Avoid
  exotic or plugin-only diagram kinds.
- Keep each diagram small, annotated, and **in sync with the code**. A diagram
  that lies about the architecture is worse than none; a stale diagram must be
  updated or removed in the same change that moved the code.
- Add a plain-language caption above each block. Prefer a real diagram file
  (for example a context or context map) over an inline one when it grows past a
  dozen nodes.

### Examples and commands

- Every command in a document must be runnable and verified, or clearly marked
  as an illustrative example/pseudocode (never silently mistaken for a real
  command). Prefer the repository's own scripts over ad-hoc `docker`/`kubectl`
  one-liners.
- `curl` examples must match the OpenAPI spec; workflow-graph examples must pass
  the DSL validator; compose examples must pass `docker compose config`.
- Never put secrets, real credentials, real data, full documents, prompts, or
  `latest`/snapshot image tags in an example. Pin image versions to immutable
  tags.
- A stale example is worse than none: replace it in the same change, or delete
  it.

### Documentation currency in PR

- **Documentation as code:** code, docs and contracts change in one PR. A new
  module/package/table/metric/permission/event/tool updates its owning doc, the
  matching `contracts/`, and the relevant `AGENTS.md`.
- **One source of truth:** link to a document instead of copying it.
- **No false markers:** remove the ⚠ `planned` marker once a document is
  created, and never mark as done what is not in the code.
- The PR template requires a `Documentation` section (README / CHANGELOG /
  docs / contracts updated or `N/A`) and a `Verification` section with the
  real PASS / FAIL / not-run outcome — never a build reported as passing
  without running it.
