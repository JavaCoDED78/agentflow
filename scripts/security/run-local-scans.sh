#!/usr/bin/env bash
# Local equivalent of the security CI gate (S14-11).
#
# Scans the repository for secrets (gitleaks, full history) and dependency
# vulnerabilities (trivy fs), then applies the waiver gate. Exits non-zero if
# any blocking (CRITICAL/HIGH) finding is unwaived, or if the waiver registry
# is invalid. The container image scan is CI-only (no image yet — S15-01).
#
# Requirements: trivy and gitleaks, either in PATH or via:
#   TRIVY_BIN=/path/to/trivy GITLEAKS_BIN=/path/to/gitleaks
# Run from anywhere: the script cd's to the repository root.
set -euo pipefail
cd "$(dirname "$0")/../.."

TRIVY="${TRIVY_BIN:-trivy}"
GITLEAKS="${GITLEAKS_BIN:-gitleaks}"
REPORT="${TRIVY_REPORT:-trivy-vulns.json}"

echo "==> secret scan (gitleaks, full history, redacted)"
"$GITLEAKS" git --redact --no-banner --config .gitleaks.toml .

echo "==> dependency vulnerability scan (trivy fs)"
# --exit-code 0: the single gate is the waiver check below, not trivy's own
# exit code — a failing trivy must still reach the waiver gate.
"$TRIVY" fs --scanners vuln --no-progress --exit-code 0 --format json --output "$REPORT" .

echo "==> waiver gate (blocking severities: CRITICAL,HIGH)"
python3 scripts/security/check-waivers.py --report "$REPORT"

echo "==> all security checks passed"
