# AGENTS.md — `knowledgebase`

> **Owner:** `knowledgebase` — document metadata, access policy, tenant/ACL-filtered chunk search and citations.
> **Applies:** `S02-07` — package-by-feature skeleton for this module.
> **Authority:** `docs/architecture/module-map.md` (owners + dependency direction) and
> `docs/architecture/module-api.md` (public-API boundary). They are the single source of truth;
> this file restates the essentials only.

## Responsibility

document metadata, access policy, tenant/ACL-filtered chunk search and citations.

## Document upload validation (`S10-03`)

`knowledgebase` owns the accept/bound checks the upload API (`POST /v1/documents`) runs before a single
byte is written to object storage and before a single `documents` row is committed. It is DB-free (no
`DataSource` / storage dependency) and safe to call from an early API:

- [`application.DocumentUploadValidator`](application/DocumentUploadValidator.java) drives the
  reject-before-persist checks — filename → content type → size → magic bytes → media duration →
  SHA-256 checksum — and every rejection is one `DocumentUploadValidationException` (the HTTP layer maps
  it to a `400`); the payload bytes are never written by this call.
- [`domain.DocumentContentType`](domain/DocumentContentType.java) is the **closed** allowlist (rejects
  anything not on it — an untrusted header cannot expand the surface) and verifies the real leading bytes
  (`matches`). [`domain.FileNameSanitizer`](domain/FileNameSanitizer.java) strips any path/traversal to a
  bare base name so the filename can never become an object path. [`domain.MediaDuration`](domain/MediaDuration.java)
  decodes the WAV duration. [`domain.DocumentUploadLimits`](domain/DocumentUploadLimits.java) is the
  immutable domain view of the three configured limits. [`domain.DocumentUploadStatus`](domain/DocumentUploadStatus.java)
  is the upload lifecycle state.
- The limits bind from `agentflow.upload.*` (`config.AgentFlowProperties.Upload`); `knowledgebase.application.config.DocumentUploadConfiguration`
  wires the validator, following `ToolExecutionConfiguration`.

The upload API, the storage client, the `documents` schema (`S10-05`) and the ingestion worker are
separate; this layer only validates, it owns no row.

## Data ownership

`knowledgebase` owns exactly one table (plus related) and no other module may change its migration or schema: `knowledge_bases`, `documents`, `document_chunks`.

## Package layout (internal vs public)

```text
com.example.agentflow.knowledgebase/               ← public API boundary
├── domain/          pure model + invariants        internal
├── application/     use cases, transactions         public services + DTOs
├── web/             controllers + API DTOs          internal
├── persistence/     repositories, mappers, SQL       internal
└── worker/          Kafka listeners                 internal
```

`persistence`, `web`, `worker` and `domain` are internal: another module must not import them
(enforced by the architecture test, `S02-08` / `S02-09`).

## Rules

- Business rules stay in `application` (transactions) or `domain` (invariants); never in `web` or
  infrastructure.
- A new `module → module` edge requires an ADR (root `AGENTS.md`).
