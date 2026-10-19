# AGENTS.md — `infra/`

Infrastructure as code: local run, images, Kubernetes, GitOps and the observability stack. General rules are in the root `AGENTS.md`.

## Contents

- `compose/` — local environment (Compose profiles: `core`, `messaging`, `identity`, `ai-local`, `observability`, `full`).
- `docker/` — the application Dockerfile and auxiliary images.
- `helm/` — chart and values (`local`, `staging`/`demo`).
- `argocd/` — GitOps manifests (P1).
- `observability/` — local observability stack as one `grafana/otel-lgtm` image (OTel Collector + Prometheus + Loki + Tempo + Grafana). The standalone Collector/Prometheus/Loki/Tempo split is the S13 production target; the lgtm rationale is in `observability/README.md`.

## Rules

- **Everything from Git.** Dashboards, alerts and datasources are provisioning files; manual edits in the Grafana UI do not count as a change. The observability stack must come up from a clean state with no manual setup.
- **Pinned versions:** stable tags or digests, never `latest`. Upgrades are separate PRs with verification, not automatic edits of production manifests.
- **Secrets** never in Git, in `values`, in build args or in logs. The repository holds only `.env.example` with placeholders; in Kubernetes — references to a Secret/external secret store. Default credentials are allowed only in `local` and are clearly marked.
- **Compose:** a healthcheck on every service; `core` starts without the other profiles; `docker compose config` passes; the PostgreSQL image with pgvector is the same one Testcontainers uses. The Keycloak realm is a declarative import from Git.
- **Docker image:** multi-stage, non-root, correct SIGTERM handling (graceful shutdown); image security scan and SBOM in CI; the artifact is built from the same commit that passed the tests.
- **Helm / Kubernetes:** separate Deployments for API, workflow-worker and ingestion-worker from one image; the API does not start consumers, workers are not exposed through ingress; only the API is reachable from outside. Startup/readiness/liveness probes, requests/limits, least-privilege ServiceAccount. Migrations run as one controlled Job, not from every replica. HPA only after measurements; NetworkPolicy is P1.
- **Actuator and ops endpoints** (metrics, DLQ) are not published externally without authorization and network restrictions.
- **Observability configs:** Collector with a memory limit, batching and attribute filtering (prompts, documents and secrets are not exported); alerts with `for`, severity and a runbook link in `docs/runbooks/`; metric and Loki labels are bounded (`service`, `environment`), no IDs. Keep metric names consistent with the `observability` module.
- Argo CD (P1): verify Helm 4 compatibility before GitOps becomes a blocker; the rollback / forward-fix procedure is described in `docs/`.

## Verifying changes

- Run from a clean environment; `scripts/smoke-local.sh` passes; for Kubernetes — a smoke run on a local cluster (rollout, rollback, worker scale-out).
- A new dependency or port → update the deployment guide, configuration reference and `CHANGELOG.md`.
- An alert/dashboard change → check that a runbook exists and that the metric exists in `observability.metrics`.
