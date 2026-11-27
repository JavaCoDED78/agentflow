# AGENTS.md — `storage`

> **Owner:** `storage` — provider-neutral object storage contract and the Garage adapter that
> implements it.
> **Applies:** `S02-18` (ownership), `S02-19` (contract, [`object-storage.md`](../../../../../../../../../docs/architecture/object-storage.md)), `S07-06B` (contract in code), `S07-06C` (adapter `put` in code).
> **Authority:** `docs/architecture/object-storage.md` (the contract), `docs/adr/ADR-024-object-storage.md`
> (consistency/security/lifecycle policy), `docs/architecture/module-map.md` (owners + dependency
> direction) and `docs/architecture/module-api.md` (public-API boundary). They are the single source
> of truth; this file restates the essentials only.

## Responsibility

move bytes and report what happened: the provider-neutral `ObjectStoragePort` contract and the Garage
adapter that implements it. `storage` owns no metadata row, no state machine, no tenant scope and no
document knowledge — the consuming application service (`knowledgebase`, `workflowexecution`)
authorizes against its PostgreSQL row and generates the key before the port is called.

## The contract (landed — `S07-06B`)

The root package is the public API boundary. The complete public surface is exactly these nine types:

- [`ObjectStoragePort`](ObjectStoragePort.java) — `put(CreateObjectRequest)` / `get(String)` / `head(String)` /
  `list(String prefix, String cursor, int limit)` / `delete(String)` — exactly five operations; a
  new operation requires a contract change (ADR), never a silent addition to the interface.
- [`ObjectReference`](ObjectReference.java) — the persistable handle returned by `put`/`list`
  (bucket, key, contentType, size >= 0, `sha256`); persisted alongside the metadata row.
- [`ObjectMetadata`](ObjectMetadata.java) — presence/metadata view; [`ObjectListPage`](ObjectListPage.java) —
  bounded immutable page + opaque cursor (`hasMore`/`nextCursor` consistent, `nextCursor` non-null
  iff `hasMore`).
- [`CreateObjectRequest`](CreateObjectRequest.java) — the put input (caller stream, never a byte array);
  [`OpenedObject`](OpenedObject.java) — the get output (metadata + caller-owned stream, released on close);
  [`DeleteResult`](DeleteResult.java) — `DELETED` / `ALREADY_ABSENT` (the idempotent miss is never a failure).
- [`ObjectStorageException`](ObjectStorageException.java) + [`ObjectStorageErrorCode`](ObjectStorageErrorCode.java) —
  the provider-neutral failure taxonomy. Codes are stable and provider-independent: `INVALID_KEY` /
  `INVALID_REQUEST` (deterministic caller-side rejections), `NOT_FOUND` (typed miss), `ACCESS_DENIED`,
  `UNAVAILABLE` / `TIMEOUT` (transient, `retryable() == true`), `PERMANENT_FAILURE`, and a stable code
  for every structural violation — never a raw provider exception. `toString()` never echoes message
  detail, key or credential material.

All validation lives in the package-private [`StorageValidation`](StorageValidation.java) — one home
for the key / bucket / content-type / size / sha256 / etag / metadata / cursor rules; a value is
structurally valid or a deterministic reject with a stable code. The stream rules are contract
semantics: the caller transfers stream ownership on `put`, the returned stream on `get` is caller-owned,
or the object never becomes heap-loaded (no `readAllBytes()` in the production path).

## Data ownership

`storage` owns no tables, no repositories, no state machine and no metadata rows. Bytes live in Garage;
metadata (`documents.object_key`/`sha256`, `workflow_step_runs.output_ref`) lives in the owning module's
PostgreSQL rows.

## Package layout (internal vs public)

```text
com.example.agentflow.storage/               ← public API boundary (this file)
├── (root)           contract: port, value types, error taxonomy, StorageValidation (internal)
└── adapter/         GarageS3Adapter — the one adapter, internal — `put` landed (S07-06C); `get`/`head`/`list`/`delete` pending (S07-06D…S07-06E)
```

The root package is public: another module may import only the nine types above. `adapter`
(`storage.adapter.*`) and any other subpackage are internal and may not be imported by another module
(enforced by `StorageBoundaryTest`). No `domain`/`application`/`web`/`persistence`/`worker` layout —
`storage` is a contract, not a domain module; the adapter will live in the `adapter` subpackage.

## Rules

- Provider-neutral: no AWS SDK, Garage or provider-specific class in any public type or signature
  (enforced by `StorageBoundaryTest` and by reflection in the contract suite). The adapter is the only
  place the SDK may appear.
- `storage` never derives or checks tenant scope, never parses a document, never persists metadata —
  consumers (`knowledgebase`, `workflowexecution`) authorize and generate keys against their PostgreSQL
  rows. Only those two modules (arrows 22/23) may depend on `storage`; `ingestion` never does — it reads
  bytes through `knowledgebase`'s `DocumentPayloadStore`.
- Tenant isolation: no tenant filtering here — the consuming service filters before calling the port;
  the port never sees or checks `tenantId`.
- No secrets, prompts or document text in object metadata, logs, metrics or traces (contract §5.1 /
  §6; invariant 8); structural secret-shaped metadata is rejected.
- Failure codes are a contract, not a convention: a change to a code, a `retryable()` value or a
  constraint requires a contract revision (ADR), not a silent edit.
- The adapter is wired by `config.StorageAdapterConfiguration` (the same `@ConditionalOnProperty` as
  the shared client) and consumes this contract and the opt-in S3 client foundation from `S07-06A`
  (`config.StorageClientConfiguration`). `put` landed with `S07-06C` — streaming, bounded-memory,
  checksum-verified, deterministic overwrite on the same key, bound to the configured bucket; the
  remaining operations land with `S07-06D`…`S07-06H`.
- A new `module → module` edge requires an ADR (root `AGENTS.md`).
