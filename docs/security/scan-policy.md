# Security scan policy (S14-11)

> Owner: the whole repository (security gate, CI). Governing invariant:
> invariant 8 (secrets never land in Git) plus the Sprint 14 acceptance gate —
> "critical findings break CI or have an explicit approved exception".

The platform ships a blocking security gate that runs on every pull request and
on pushes to `main`: a **secret scan** (gitleaks, full history), a **dependency
vulnerability scan** (Trivy, `fs` mode over the dependency lockfiles) and an
**OCI image scan** (Trivy, `image` mode — dormant until the application image
exists). Blocking severities are **CRITICAL and HIGH**; anything at or above
the blocking set fails the build unless it is covered by an unexpired,
justified waiver in `security/waivers.json`.

## Why this exists

The plan (§14.2 PR pipeline, supply-chain line) calls for CI scanning so that a
critical finding cannot merge silently. Without it, a transitive dependency
with a published CVE is only visible after a deployment. The gate is
deliberately **fail-closed**: a missing registry, a malformed registry, an
expired waiver or an unwaived blocking finding all fail the pipeline, never a
silently-green check.

## What runs, and where

| Job | Tool | Scans | Blocking severity | Gate |
|---|---|---|---|---|
| `secret-scan` | gitleaks 8.30.1 | full git history (every commit) | n/a — secret findings are never waived | hard fail on any finding |
| `dependency-scan` | Trivy 0.76.2 (`fs`) | the dependency lockfiles (`gradle.lockfile`) | CRITICAL, HIGH | the waiver gate (`scripts/security/check-waivers.py`) |
| `container-scan` | Trivy 0.76.2 (`image`) | the OCI image | CRITICAL, HIGH | the waiver gate — **dormant until the image exists** |

The container job is dormant on purpose: no `Dockerfile` exists yet (the
multi-stage image is `S15-01`). The job is wired with
`if: hashFiles('infra/docker/**/Dockerfile*') != ''` — it stays skipped until a
Dockerfile lands, and then it builds the image and scans it with the same
waiver gate. The fs dependency scan is the live blocking gate today.

Tool versions are pinned in the workflow (`TRIVY_VERSION` / `GITLEAKS_VERSION`
env at the top of `.github/workflows/security.yml`) and never float to
`latest`. Bump them deliberately, with a re-run of the local gate.

## The waiver registry

`security/waivers.json` is the single registry of accepted exceptions. It is
**empty by default**. Each entry:

```json
{
  "id": "CVE-2026-0000",
  "reason": "Why this finding may not be fixed yet — at least one sentence.",
  "approver": "<github-username>",
  "expires_at": "2027-01-01",
  "note": "Optional extra context."
}
```

Rules, enforced by the gate:

- **Required:** `id`, `reason`, `approver`, `expires_at`. A waiver missing any
  of them, with a duplicate `id`, a malformed `expires_at` or a structurally
  broken file is a hard failure (exit 2) — never a silent pass.
- `expires_at` must be an ISO date (`YYYY-MM-DD`) and must be **in the future**.
  An expired waiver fails the gate (exit 1): a waiver that quietly became
  permanent is worse than none.
- `id` must name the finding it covers (a CVE id from the report, e.g.
  `CVE-2025-66516`). Waivers match on the id only — a waiver for a CVE hides
  every finding with that id, so keep waivers few and exact.
- **Waivers cover vulnerability findings only.** Secret findings are never
  waived; the secret job fails regardless of any waiver.
- **Waivers require human approval.** A waiver is a deliberate, documented
  exception by a named approver — it is never added by an agent, by a bot, or
  silently. If a finding must be accepted for now, the human owns the waiver.
- The gate also reports waivers that cover **no current blocking finding**
  (stale waivers) as a note — remove them or they hide real findings later.

## Severity policy

- **CRITICAL and HIGH findings** block CI. They must be fixed (dependency bump,
  upgraded toolchain) or covered by an unexpired waiver.
- **MEDIUM and below** do not gate the build (they are still reported by the
  scan). The blocking set is a single constant — `--fail-severity` in
  `scripts/security/check-waivers.py` — and the workflow passes the default;
  tightening it is a one-line change with a re-run.
- **Secret findings** always fail — the secret job has no waiver mechanism, by
  design (invariant 8).

## Local verification

From the repository root, with `trivy` and `gitleaks` in PATH (or via
`TRIVY_BIN` / `GITLEAKS_BIN`):

```bash
bash scripts/security/run-local-scans.sh
```

That runs, in order:

1. **gitleaks** over the full git history (`--redact`, `--config .gitleaks.toml`) —
   fails on any leak;
2. **Trivy fs** over the lockfiles (`--exit-code 0` so the report always
   reaches the gate) — produces `trivy-vulns.json` (git-ignored);
3. **the waiver gate** (`scripts/security/check-waivers.py --report trivy-vulns.json`)
   — exit 0 only when every blocking finding is covered by an unexpired waiver.

The gate can also run directly:

```bash
trivy fs --scanners vuln --no-progress --exit-code 0 --format json --output trivy-vulns.json .
python3 scripts/security/check-waivers.py --report trivy-vulns.json
```

`check-waivers.py` is the only gate and is deterministic: it validates the
registry (fail-closed, exit 2), then evaluates the report (exit 1 on any
unwaived blocking finding), and prints exactly which findings and which
waivers were considered. It supports `--today YYYY-MM-DD` (testing aid),
`--fail-severity CRITICAL,HIGH` and `--waivers <path>`.

Exit codes: `0` pass · `1` blocking findings unwaived (or expired waivers) · `2`
unreadable input (report missing or malformed, registry broken).

The `.gitleaks.toml` exclusions are narrow and documented in the file: two
sanctioned test-fixture paths (clearly-fake dummies) and one historical commit
of `.env.example` (a local-dev-only minio placeholder, already removed from
the current file). A finding that could be a real secret is never silenced.

## Current state (as of this change)

The fs dependency scan finds 33 CRITICAL/HIGH findings today, all in the
`gradle.lockfile` dependency pins (jackson-core/databind, tomcat-embed-core,
tika-core / tika-parser-pdf-module, bcprov-jdk18on, lz4-java, snappy-java).
They are **not waived**: the registry is empty and the gate fails honestly.
The fix is a dependency-bump PR (bump the BOM-managed versions, re-lock, and
re-run); accepting any finding via waiver requires human approval of the
waiver itself. Container scanning activates with `S15-01`. SBOM (CycloneDX) is
not in this task's scope.
