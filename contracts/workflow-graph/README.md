# Workflow Graph JSON Schema (`S05-03`)

The machine-readable contract for the declarative, typed workflow graph.

- **Schema:** [`workflow-graph.json`](workflow-graph.json) — JSON Schema, draft `2020-12`.
- **Valid examples:** [`valid/`](valid/) — graphs that must pass the schema.
- **Invalid examples:** [`invalid/`](invalid/) — graphs that must fail the schema.
- **Topology cases:** [`topology/`](topology/) — structurally valid graphs rejected by the
  `workflowdefinition` DSL validator (`S05-05`, `S05-06`, `S05-07`), which is not a JSON
  Schema concern.

This is the contract for `graph_json`, the payload persisted as
`workflow_definition_versions.graph_json` (`ADR-005`). It is authored here (this is the
one place the schema lives — see [`contracts/AGENTS.md`](../AGENTS.md)); the
`workflowdefinition` module round-trips the same shape through JSONB.

## What the schema covers

A v1 graph is:

```jsonc
{
  "schemaVersion": "1.0.0",   // fixed to this file's version — any other value is rejected
  "nodes": [ /* 1..200 typed steps */ ],
  "edges": [ /* 0..1000 execution-order edges {from, to} */ ]
}
```

- **`schemaVersion`** — `const: "1.0.0"`. Unknown versions fail validation, so `S05-04` gets a
  controlled error for free.
- **`nodes`** — ordered list of `{id, type, config?, input?, output?}`.
  - `id` — `^[a-zA-Z][a-zA-Z0-9_-]*$`, ≤ 128 chars. Uniqueness is enforced by the DSL validator,
    not by this schema (JSON Schema cannot express cross-item uniqueness portably).
  - `type` — the closed set from the domain glossary / `ADR-005`:
    `RETRIEVE_CONTEXT`, `LLM_PLAN`, `TOOL_CALL`, `HUMAN_APPROVAL`, `LLM_EVALUATE`, `FINALIZE`.
  - `config` — free-form, per-step parameters. Treated as untrusted **data**, never executed
    (invariant 7).
  - `input` / `output` — optional data mapping. An `input` value is a literal string or a
    `{node, output}` reference to another node's output.
- **`edges`** — ordered `{from, to}` pairs that express **execution order** (distinct from the
  optional `input`/`output` data wiring).

### Limits and why they exist

| Limit | Value | Reason |
|---|---|---|
| `nodes.minItems` | 1 | An empty graph is not a runnable workflow. |
| `nodes.maxItems` | 200 | Bounds graph size and the cost/latency of a run (`S05-06`). |
| `edges.maxItems` | 1000 | Bounds graph size. |
| `node.id` / edge `from` / `to` / reference `node` (maxLength) | 128 | Bounds identifiers; the pattern keeps them stable and parseable. |
| `schemaVersion` | `const: "1.0.0"` | This file defines exactly one version. |

## What the JSON Schema does **not** enforce

These rules are not expressible in portable JSON Schema and belong to the `workflowdefinition`
DSL validator (`S05-05`, `S05-06`, `S05-07`):

- unique node ids, no dangling edge endpoints, no self-referencing edges, no duplicate edges;
- the graph is a DAG (no cycles);
- an input reference names a node that actually exists and exposes that output.

The fixtures in [`topology/`](topology/) document these cases: each one **passes** this schema
(structurally valid) but is rejected by the DSL validator when it lands.

## Verifying

The acceptance criterion (`S05-03`) is that a graph can be checked with an **external schema
validator**. This repo uses the offline Python `jsonschema` package (Draft `2020-12`):

```bash
python - <<'PY'
import json, pathlib
from jsonschema import Draft202012Validator

root = pathlib.Path("contracts/workflow-graph")
schema = json.loads((root / "workflow-graph.json").read_text())
validator = Draft202012Validator(schema)

for d in ("valid", "invalid"):
    for f in sorted((root / d).glob("*.json")):
        try:
            validator.validate(json.loads(f.read_text()))
            print(d, f.name, "->", "PASS")
        except Exception:
            print(d, f.name, "->", "FAIL")
PY
```

Expected outcome:

- every file in `valid/` → **PASS**;
- every file in `invalid/` → **FAIL**;
- every file in `topology/` → **PASS** this schema (rejected by the DSL validator, not shown here).

No network, model or database is required — the check is a pure schema evaluation.
