# AGENTS.md — `observability`

> **Owner:** `observability` — meters, trace attributes, correlation, structured JSON logging.
> **Applies:** `S02-07` (consolidates the S01-19 structured-logging code that previously lived in
> the {@code logging} package).
> **Authority:** `docs/architecture/module-map.md` ("Cross-cutting packages").

## Responsibility

Structured JSON logging + secret masking, domain meters, trace/correlation attributes. Owns the
metric catalogue and the "no high-cardinality labels" rule. Cross-cutting: it offers helpers
consumed by every module, not a domain module.

## Rules

- No business logic; only cross-cutting telemetry helpers.
- Never put {@code tenantId}/{@code runId}/{@code userId}/document IDs or prompts in metric labels
  (high cardinality / leak).
