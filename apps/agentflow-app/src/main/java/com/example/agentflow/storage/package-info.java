/**
 * <b>Owner:</b> {@code storage} — provider-neutral object storage contract and the Garage adapter
 * that implements it (S02-18, S02-19, `S07-06A`…`S07-06H`).
 *
 * <p>This is the module's public API boundary (the contract is the root package): the five {@link
 * com.example.agentflow.storage.ObjectStoragePort} operations, the request/response value types,
 * the {@link com.example.agentflow.storage.ObjectReference} persistable handle and the {@link
 * com.example.agentflow.storage.ObjectStorageException} / {@link
 * com.example.agentflow.storage.ObjectStorageErrorCode} failure taxonomy. No AWS SDK or Garage
 * class may appear in any public type or method signature — the Garage adapter lives in the
 * internal {@code adapter} subpackage and is never imported outside this module. The module moves
 * bytes and reports what happened; it owns no tables, no repositories, no status machine and no
 * tenant scope — the owning module (`knowledgebase`, `workflowexecution`) authorizes against its
 * PostgreSQL row and generates the key before the port is called.
 *
 * <p>Authority: {@code docs/architecture/object-storage.md} (contract), {@code
 * docs/adr/ADR-024-object-storage.md} (consistency/security/lifecycle policy) and {@code
 * docs/architecture/module-api.md} (public-API boundary).
 */
package com.example.agentflow.storage;
