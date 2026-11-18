# ADR-020. Centralized secret redaction is a shared primitive in `common`, consumed by `observability`, `audit` and `messaging`

- **Status:** Accepted
- **Date:** 2026-11-18
- **Task:** S12-09 (Sprint 12 — budgets, append-only audit, DLQ)
- **Supersedes:** none. Follows the error-taxonomy precedent (`ADR-016`, `ADR-017`) that already
  sits in `common` and is consumed across modules.
- **Superseded by:** none

## Context

`S12-09` requires that bearer tokens, API keys, provider headers, raw document text and full
prompts never reach **logs, audit metadata or the DLQ description**. The three surfaces persist
and emit through different code paths:

- **Logs** — every field value is masked by the logback `ValueMasker`
  (`observability.SecretRedactingValueMasker`, registered in `logback-spring.xml`).
- **Audit metadata** — the free-text `metadataRedacted` column of `audit_events` is written
  directly to PostgreSQL by `audit.application.AuditService`.
- **DLQ description** — the `last_error` column of the DLQ is written by `messaging.application.DlqService`.

Before this change the redaction *rules* (the regexes for bearer tokens / AWS keys /
`key=value` secrets) lived **only** in the logback masker. The audit and DLQ paths had no shared
redaction: they trusted the caller to pass already-redacted text. That left two invariants
unprotected at their storage boundary — a caller could not scrub a value (for example a full prompt
or a provider key that appears in an error string) and it would reach a durable row or an operator
view. To satisfy the acceptance criterion with a single source of truth, the rules move into
`common.SecretRedactor` (a static utility, same shape as `common.ErrorClass` and `common.Fingerprint`)
and all three surfaces call it.

`common` already owns secret redaction by its stated responsibility ("the error taxonomy and
secret redaction" — `common/AGENTS.md`, `S02-07`). It owns no business table and the utility is
domain-neutral (bearer token, API key, authorization header, AWS access key, password, full prompt),
so this is the right home. The alternative homes are worse: putting it in `observability` would
make it a non-telemetry library; putting it in each module would let the three paths drift apart and
violate the "one owning module" rule.

The shared primitive pulls `common` into the dependency graph as an **allowed** compile dependency of
the consuming modules. `observability`, `audit` and `messaging` (and `workflowexecution`, which
already used `common.ErrorClass`/`Fingerprint`) must therefore have `common` in their allowed
set, which the architecture gate forbids without an ADR (root `AGENTS.md`).

## Decision

**Grant `common` a new allowed compile edge from `observability`, `audit`, `messaging` and
`workflowexecution`, and route all three redaction surfaces through `common.SecretRedactor`.**

- `common.SecretRedactor.redact(String)` is the single implementation of the redaction rules. It
  is a `final` class: private constructor, static methods, no Spring, no I/O.
- `observability.SecretRedactingValueMasker` stops owning the patterns and delegates to
  `SecretRedactor.redact`; it only adapts the utility to the logback `ValueMasker` contract.
- `audit.AuditService` and `messaging.DlqService` call `SecretRedactor.redact` on the value at the
  storage boundary, in front of the `INSERT`, so a caller-supplied value that still carries a secret
  or a full prompt is masked before it is written.
- `ArchitectureBoundaryTest` is updated so the four modules' allowed sets include `common`, and the
  gate still fails if any of them takes a *forbidden* dependency (`workflowdefinition`, `toolexecution`, …).

The reach is narrow: the consuming modules use only `common.SecretRedactor` (and, for
`workflowexecution`, the pre-existing `common.ErrorClass` / `common.Fingerprint` / `common.error`).
They never touch `common`'s error handler beans or each other.

## Alternatives considered

1. **Keep redaction only in the logback masker; rely on callers to pre-redact audit/DLQ text.**
   Rejected: the acceptance criterion covers audit metadata and DLQ description, and there are
   callers that cannot scrub a value (e.g. an error string that happens to embed a provider key).
   Masking at the storage boundary closes the gap without trusting every caller.
2. **Own redaction in `observability` and have `audit`/`messaging` depend on it.** Rejected:
   redaction is not telemetry, and `audit`/`messaging` depending on `observability` is a foreign
   responsibility on the wrong edge (it would itself need a new ADR) while coupling two unrelated
   modules.
3. **Copy the rules into each module.** Rejected: three copies of the same regexes will drift and
   each module would need its own module→module dependency; a single shared primitive is strictly
   simpler and matches the `common.ErrorClass` precedent.
4. **Add the edge without an ADR.** Rejected: root `AGENTS.md` requires an ADR for every new
   module-to-module dependency edge; the architecture gate is updated in the same change so the
   map and the gate never disagree (`ADR-018`).

## Consequences

- **Positive:**
  - One implementation of the redaction rules; the log, audit-metadata and DLQ-description paths
    cannot diverge (acceptance: secrets do not appear in logs, audit metadata or DLQ description).
  - Redaction is enforced at every durable/emit boundary, not only where a happens to scrub a value.
  - `common` gains a domain-neutral primitive that matches its stated ownership and the existing
    `ErrorClass` / `Fingerprint` idiom; every module can reuse it without a new dependency family.
- **Negative / operational:**
  - `observability`, `audit`, `messaging` (and `workflowexecution`) gain a compile dependency on
    `common`; the rule is unchanged: they may use `common`'s public primitives only, never its
    infrastructure.
  - `SecretRedactor` is a static utility with no constructor — tests call it directly (`S12-09`).
- **Follow-ups:**
  - `S13-09` refines the masking once correlation and tracing are wired (S13); it edits
    `SecretRedactor`, not a per-module copy.
  - The audit/DLQ redaction is belt-and-braces on top of the documented "redacted metadata /
    redacted error" contract; the contract stays in force.
