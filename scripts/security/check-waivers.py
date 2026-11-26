#!/usr/bin/env python3
"""Waiver gate for the AgentFlow security scan (S14-11).

Reads a Trivy JSON report (fs or image mode) together with the waiver
registry and fails (exit 1) unless every finding at or above the blocking
severity is covered by a valid, unexpired waiver. The registry itself is
validated strictly and fails closed: a malformed or stale registry is a
hard failure, never a silent pass.

Waivers cover *vulnerability* findings only. Secret findings are never
waived — they exit non-zero regardless of any waiver.

Usage:
    python3 scripts/security/check-waivers.py --report trivy-vulns.json
    python3 scripts/security/check-waivers.py --report trivy-image.json \
        --waivers security/waivers.json --fail-severity CRITICAL,HIGH

Exit codes:
    0 -- every blocking finding is covered by a valid, unexpired waiver
    1 -- one or more findings are unwaived or expired
    2 -- the report or the registry cannot be read / is structurally invalid
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WAIVERS = REPO_ROOT / "security" / "waivers.json"
DEFAULT_REPORT = REPO_ROOT / "trivy-vulns.json"
SUPPORTED_SEVERITIES = {"CRITICAL", "HIGH", "MEDIUM", "LOW", "NEGLIGIBLE", "UNKNOWN"}


class GateError(Exception):
    """A structural problem with the input — fail closed."""


def load_waivers(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise GateError(f"waiver registry not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise GateError(f"waiver registry is not valid JSON ({path}): {exc}") from exc
    if not isinstance(raw, dict):
        raise GateError(f"waiver registry must be a JSON object ({path})")
    entries = raw.get("waivers")
    if not isinstance(entries, list):
        raise GateError(f"waiver registry must carry a list under 'waivers' ({path})")
    waivers: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    for i, entry in enumerate(entries):
        label = f"waivers[{i}]"
        if not isinstance(entry, dict):
            raise GateError(f"{label}: must be an object ({path})")
        missing = [k for k in ("id", "reason", "approver", "expires_at") if not entry.get(k)]
        if missing:
            raise GateError(f"{label}: missing or empty field(s): {', '.join(missing)} ({path})")
        wid = str(entry["id"]).strip()
        if not wid:
            raise GateError(f"{label}: 'id' must not be blank ({path})")
        if wid in seen_ids:
            raise GateError(f"duplicate waiver id: {wid} ({path})")
        seen_ids.add(wid)
        try:
            expires = date.fromisoformat(str(entry["expires_at"]).strip())
        except ValueError as exc:
            raise GateError(
                f"{label}: 'expires_at' must be an ISO date (YYYY-MM-DD): {entry['expires_at']!r} ({path})"
            ) from exc
        waivers.append({
            "id": wid,
            "reason": str(entry["reason"]).strip(),
            "approver": str(entry["approver"]).strip(),
            "expires_at": expires,
            "note": str(entry.get("note", "")).strip(),
        })
    return waivers


def load_report(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        raise GateError(f"trivy report not found: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise GateError(f"trivy report is not valid JSON ({path}): {exc}") from exc
    if not isinstance(raw, dict):
        raise GateError(f"trivy report must be a JSON object ({path})")
    results = raw.get("Results")
    if not isinstance(results, list):
        raise GateError(f"trivy report is missing a 'Results' list ({path})")
    findings: list[dict[str, Any]] = []
    for res in results:
        if not isinstance(res, dict):
            continue
        target = res.get("Target", "?")
        vulns = res.get("Vulnerabilities")
        if not isinstance(vulns, list):
            continue
        for v in vulns:
            if not isinstance(v, dict):
                continue
            findings.append({
                "id": str(v.get("VulnerabilityID", "")).strip(),
                "severity": str(v.get("Severity", "UNKNOWN")).strip().upper(),
                "pkg": str(v.get("PkgName", "?")),
                "installed": str(v.get("InstalledVersion", "")),
                "fixed": str(v.get("FixedVersion", "") or ""),
                "target": target,
            })
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else "Waiver gate")
    parser.add_argument("--waivers", type=Path, default=DEFAULT_WAIVERS,
                        help="waiver registry (default: security/waivers.json)")
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT,
                        help="trivy JSON report (fs or image mode)")
    parser.add_argument("--fail-severity", default="CRITICAL,HIGH", metavar="CSV",
                        help="blocking severities, comma-separated (default: CRITICAL,HIGH)")
    parser.add_argument("--today", default=None, metavar="YYYY-MM-DD",
                        help="override 'today' (testing aid, ISO date)")
    args = parser.parse_args()

    severities = {s.strip().upper() for s in args.fail_severity.split(",") if s.strip()}
    if not severities:
        print("ERROR: --fail-severity must name at least one severity", file=sys.stderr)
        return 2
    unknown = severities - SUPPORTED_SEVERITIES
    if unknown:
        print(f"ERROR: unsupported severity/severities: {', '.join(sorted(unknown))}", file=sys.stderr)
        return 2

    today = date.today()
    if args.today:
        try:
            today = date.fromisoformat(args.today)
        except ValueError:
            print(f"ERROR: --today must be an ISO date (YYYY-MM-DD), got {args.today!r}", file=sys.stderr)
            return 2

    try:
        waivers = load_waivers(args.waivers)
        findings = load_report(args.report)
    except GateError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2

    expired = [w for w in waivers if w["expires_at"] < today]
    active = [w for w in waivers if w["expires_at"] >= today]
    active_ids = {w["id"] for w in active}
    expired_ids = {w["id"] for w in expired}

    blocking = [f for f in findings if f["severity"] in severities]
    unwaived = [f for f in blocking if f["id"] not in active_ids]
    waived = [f for f in blocking if f["id"] in active_ids]

    print(f"Waiver gate — {len(findings)} findings scanned, "
          f"{len(blocking)} at or above blocking severities ({', '.join(sorted(severities))})")
    print(f"  waivers in registry: {len(waivers)} ({len(active)} active, {len(expired)} expired)")

    for w in expired:
        print(f"  EXPIRED waiver: {w['id']} (approver {w['approver']}, expired {w['expires_at'].isoformat()})")

    if waived:
        print(f"  waived: {len(waived)} finding(s)")
        for f in waived:
            print(f"    ok  {f['id']}  {f['severity']}  {f['pkg']} {f['installed']} (target: {f['target']})")

    stale = [w for w in active if w["id"] not in {f["id"] for f in blocking}]
    for w in stale:
        print(f"  note  {w['id']}: waiver covers no current blocking finding "
              f"(approver {w['approver']}, expires {w['expires_at'].isoformat()})")

    if unwaived:
        print(f"  UNWAIVED: {len(unwaived)} finding(s)", file=sys.stderr)
        for f in unwaived:
            fixed = f" (fixed in {f['fixed']})" if f["fixed"] else " (no fix available)"
            print(f"    FAIL  {f['id']}  {f['severity']}  {f['pkg']} {f['installed']}{fixed}"
                  f" — target: {f['target']}", file=sys.stderr)
        try:
            shown = args.waivers.resolve().relative_to(REPO_ROOT)
        except ValueError:
            shown = args.waivers
        print(f"\nBLOCKED: critical/high findings are unwaived. "
              "Fix the dependency, or add a waiver to",
              file=sys.stderr)
        print(f"         {shown} with a justified reason, an approver and", file=sys.stderr)
        print(f"         an expiry date — waivers cover CVE ids, nothing else.", file=sys.stderr)
        return 1

    if expired:
        print("\nBLOCKED: expired waivers must be removed or renewed before this gate can pass.",
              file=sys.stderr)
        return 1

    try:
        shown_waivers = args.waivers.resolve().relative_to(REPO_ROOT)
    except ValueError:
        shown_waivers = args.waivers
    print(f"PASS — {len(waived)} blocking finding(s) covered by valid waivers in {shown_waivers}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
