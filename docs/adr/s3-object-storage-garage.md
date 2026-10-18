# S3-compatible object storage: Garage for the local stack

- **Status:** Accepted
- **Date:** 2026-10-18
- **Task:** S01-15 (Compose MinIO / Keycloak)
- **Supersedes:** the plan's reference to "MinIO (S3)" as the local object store
  (ADR-001..007 in the plan are reserved core ADRs; this infra decision predates
  the formal ADR register introduced in S02-16 and will be folded in then).

## Context

S01-15 adds an S3-compatible object store for large documents/artifacts kept
outside PostgreSQL (metadata + object key stay in the DB; the payload lives in
object storage). The plan, `AGENTS.md` and the Compose file referenced MinIO.

Since mid-2025 MinIO's pre-built images are no longer free: `quay.io/minio/
aistor/minio` is MinIO AIStor, the official successor, and it starts without a
license only in an offline mode where **all S3 operations are denied** until a
(single-node) SUBNET license is installed. A local `core` stack that cannot do
S3 is unusable for local development, the ingestion pipeline, and the RAG upload
journey.

We need an S3-compatible object store for the local Compose stack that:

- starts and serves S3 out of the box, with no license gate;
- is free / open source (no per-deployment licensing cost or vendor lock);
- runs as a single local Compose service in the `core` profile;
- stays transparent to the tenant-scoped object-key convention already required
  by the ingestion/RAG flow (tenant isolation is enforced at the app layer, not
  by the object store).

The alternatives below were evaluated; **Garage** (`dxflrs/garage`) was the only
free backend whose S3 API we verified end-to-end.

## Decision

Use **Garage** (`dxflrs/garage:v2.4.1`, AGPL-3.0) as the S3-compatible object
store for the local `core` stack.

- The Compose service runs `/garage server --single-node --default-bucket`.
  `--single-node` applies the cluster layout automatically (no manual
  `layout assign` / `layout apply`); `--default-bucket` creates the `agentflow`
  bucket and a default S3 access key from environment variables, which also
  implies the key. The layout, bucket and key persist in the metadata volume, so
  restarts reconcile idempotently instead of re-bootstrapping (verified).
- The config file (`infra/compose/garage/garage.toml`) carries no secrets; the
  RPC secret is injected via the `GARAGE_RPC_SECRET` environment variable.
- The app connects through the same S3 API contract (path-style SigV4,
  region `garage`); the root key used locally is set identically in the Compose
  file and in the root `.env` (`minio.access-key` / `minio.secret-key`).

Verified locally: auto-bootstrap (layout + bucket + key + grant), an S3
round-trip (list / put / get, payloads match by SHA-256), and S3 working again
after a container restart with the same credentials.

## Alternatives considered

1. **MinIO AIStor (`quay.io/minio/aistor/minio`).** The official successor, but
   license-gated for S3 (a single-node SUBNET license is required; without it,
   "All S3 operations are denied"). Rejected for the local stack: unusable
   without a license and it reintroduces a vendor/licensing dependency.
2. **Other free S3 backends.** Chainguard's MinIO image was not reachable from
   the local environment; LibreFS is an unproven fork with no verified S3
   round-trip. Garage was the only free backend verified end-to-end.
3. **Manual bootstrap** (`layout assign` → `layout apply` → `bucket create` →
   `key create` → `bucket allow` via a script). Works, but adds a shell step
   and moving parts. Replaced by the built-in `--single-node --default-bucket`
   auto-bootstrap: fewer steps, idempotent, no external CLI on start-up.

## Consequences

- **Positive:** no license gate, free/open source (AGPL-3.0), no per-deployment
  licensing or vendor lock, works out of the box; the single-node local store
  scales to a multi-node cluster later without changing the S3 contract.
- **Operational / negative:**
  - Single point of failure for the local `core` stack. Acceptable because
    PostgreSQL is the source of truth (losing Garage must not lose a run) —
    object storage holds payloads only; metadata stays in the DB.
  - Garage grants access at the key level (a key is granted read/write on
    buckets), not AWS-style bucket policies/ACLs. Local dev uses one root key
    with write on the `agentflow` bucket; production must assign scoped keys per
    bucket in line with tenant isolation.
  - Admin operations (create extra buckets/keys) use the `garage` CLI via
    `docker exec`; the app path is the S3 API.
- **Follow-ups:**
  - Kubernetes / production must set `GARAGE_RPC_SECRET` and the root S3 key
    from a Secret (never hardcoded) and pin the image tag.
  - S10-02 ("bucket and key convention") should define tenant-scoped
    buckets/keys against Garage instead of MinIO.
  - Consider naming Garage (or "S3-compatible object store") in `AGENTS.md` and
    the plan, which still say "MinIO".
