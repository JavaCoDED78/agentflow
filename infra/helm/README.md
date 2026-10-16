# `infra/helm/` — deployment manifests

Helm charts for `local`, `staging` and `demo` profiles, with Kubernetes
Deployments for the API and worker roles, probes, graceful shutdown and
controlled database migrations.

Values files never contain secrets; secrets come from Kubernetes
Secrets/external secret management. Argo CD applies state from Git (GitOps).

Scaffolded in **S01-03**. Charts are added in the deployment sprint
(**S15**), together with GitOps in `infra/argocd/`.
