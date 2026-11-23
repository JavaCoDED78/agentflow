# AGENTS.md — `observability`

> **Owner:** `observability` — meters, trace attributes, correlation, structured JSON logging.
> **Applies:** `S02-07` (consolidates the S01-19 structured-logging code that previously lived in
> the {@code logging} package).
> **Authority:** `docs/architecture/module-map.md` ("Cross-cutting packages").

## Responsibility

Structured JSON logging + secret masking, domain meters, trace/correlation attributes. Owns the
metric catalogue and the "no high-cardinality labels" rule. Cross-cutting: it offers helpers
consumed by every module, not a domain module.

The conventions every signal follows (naming/units, bounded labels, trace attributes, redaction,
what never goes in any signal) are fixed in [`docs/observability/standards.md`](../../../../../../../../../docs/observability/standards.md) (S13-01); the catalogue itself lands with S13-11…S13-13/S13-22 and the handbook with S13-21.

## Structured JSON logging (S13-09)

`StructuredJsonLogstashEncoder` owns the JSON log line: `@timestamp`, `severity` (not `level`; the
numeric `level_value` dropped), `message`, `logger_name`, `thread_name` and `exception`, with the
MDC written inline and whitelisted to exactly `service` / `environment` / `traceId` / `spanId` /
`correlationId` — any other MDC key is dropped, never written. `CorrelationIdFilter` seeds a
per-request `correlationId` (the active `traceId` when tracing is wired, a fresh UUID otherwise) and
removes it in a `finally` block. `logback-spring.xml` points at this encoder — the single place the
field contract lives; do not configure per-module encoders.

## Secret masking (S12-09)

Masking is centralized in `common.SecretRedactor` (S12-09; ADR-020): `observability.SecretRedactingValueMasker`
adapts that single shared utility to the logback `ValueMasker` contract it is given in
`logback-spring.xml`, so the log path and the `audit` / `messaging` storage paths redact identically
and can never drift apart. `observability` does not own the redaction rules — it only wires the
shared utility into the encoder.

## Trace-context builder (S13-10)

The one trace-context *builder* consumed outside the messaging boundary — `MessagingTracing` (`parentContext` / `startSpan` / `currentTraceparent`) — lives in `messaging.application`, where the producer (`KafkaMessageDeliverer`) and the consumer workers meet; it is a messaging-boundary helper, not an `observability` export. The `observability` module owns the conventions (`docs/observability/standards.md`, S13-01) and the future meter catalogue, not the span-building code.

## Rules

- No business logic; only cross-cutting telemetry helpers.
- Never put {@code tenantId}/{@code runId}/{@code userId}/document IDs or prompts in metric labels
  (high cardinality / leak).
