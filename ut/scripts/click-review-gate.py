#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Hard gate around `clickable review` (click-review) with a minimal, owner-decided allowlist.

  ut/scripts/click-review-gate.py [--summary FILE] -- <clickable review args...>
  ut/scripts/click-review-gate.py --self-test

Runs `clickable review <args>`, prints its output IN FULL, parses the findings and exits 0 only when
every finding is on ALLOWLIST (exact rule id) AND is the expected "(NEEDS REVIEW)" kind.
Anything else fails: another error or warning, a known rule id with different text, unparsable
output, a missing clickable, or a non-zero exit with no parsable findings.

OWNER DECISION 2026-11-18 (Option 2, "narrow allowance"): the two findings below are INHERENT to the app
(ut/app/fotoz.apparmor.in, ADR-013: reserved policy groups for reading Pictures/Videos) and only OpenStore
reviewers can clear them, so CI tolerates exactly these and nothing else. Keep this list MINIMAL; the
OpenStore review must still clear them before any store submission (docs/adr/ADR-013-linux-strategy.md,
ut/OWNER_VERIFY.md).
"""
import os
import re
import subprocess
import sys

ALLOWLIST = {
    "security:policy_groups_safe:fotoz:picture_files_read",
    "security:policy_groups_safe:fotoz:video_files_read",
}
EXPECTED_TEXT = re.compile(r"\(NEEDS REVIEW\).*reserved policy group", re.I | re.S)

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
MARKER = re.compile(r"^\s*=+\s*Findings\s*=+\s*$", re.I)
HEADING = re.compile(r"^\s*(=+|-+)\s*[A-Za-z][A-Za-z ]*?\s*(=+|-+)\s*$")
FINDING = re.compile(r"^\s*(?:[-*]\s*)?([a-z][a-z0-9_]*:[a-z0-9_]+(?::[^\s:]+)+):?\s*(.*)$")


def parse(text):
    """-> (findings [(rule_id, text)], problems [str]). Problems are unparsable lines."""
    findings, problems, started = [], [], False
    cur = None
    for raw in ANSI.sub("", text).splitlines():
        if not started:
            started = bool(MARKER.match(raw))
            continue
        if not raw.strip():
            continue
        m = FINDING.match(raw)
        if m:
            cur = [m.group(1), m.group(2)]
            findings.append(cur)
        elif HEADING.match(raw):
            cur = None
        elif cur is not None:
            cur[1] += " " + raw.strip()
        else:
            problems.append(raw.strip())
    return [(a, b) for a, b in findings], problems, started


def judge(text, rc):
    """-> (ok, tolerated [rule ids], reasons [str])."""
    findings, problems, started = parse(text)
    reasons = []
    tolerated = []
    for rule, body in findings:
        if rule not in ALLOWLIST:
            reasons.append(f"not allowlisted: {rule}")
        elif not EXPECTED_TEXT.search(body):
            reasons.append(f"allowlisted rule with unexpected text: {rule}: {body!r}")
        else:
            tolerated.append(rule)
    for p in problems:
        reasons.append(f"unparsable line in findings: {p!r}")
    if rc != 0 and not findings:
        reasons.append(f"exit code {rc} but no parsable findings")
    if rc == 0 and not findings and re.search(r"\b(error|warning|traceback)\b", ANSI.sub("", text), re.I):
        reasons.append("exit 0, no findings parsed, but output mentions error/warning")
    if rc != 0 and findings and not started:
        reasons.append("findings without a '= Findings =' section")
    return (not reasons), tolerated, reasons


def run(argv, summary):
    try:
        p = subprocess.run(["clickable", "review", *argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, errors="replace")
    except FileNotFoundError:
        print("click-review-gate: FAIL: `clickable` not found on PATH")
        return 1
    out = p.stdout
    print(out)
    ok, tolerated, reasons = judge(out, p.returncode)
    if ok:
        if tolerated:
            line = ("click-review-gate: PASS. Tolerated by OWNER DECISION 2026-11-18 (narrow allowance, ADR-013; only "
                    "OpenStore review can clear them): " + ", ".join(sorted(set(tolerated))) +
                    ". Any other error or warning would have failed this step.")
        else:
            line = "click-review-gate: PASS. No findings (nothing needed the owner-decided allowance)."
    else:
        line = "click-review-gate: FAIL: " + "; ".join(reasons)
    print(line)
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### click review\n\n" + line + "\n\n<details><summary>click-review output</summary>\n\n```\n" +
                    ANSI.sub("", out) + "\n```\n</details>\n")
    return 0 if ok else 1


def self_test():
    ok_a = ("security:policy_groups_safe:fotoz:picture_files_read")
    ok_b = ("security:policy_groups_safe:fotoz:video_files_read")
    txt = "(NEEDS REVIEW) reserved policy group '%s' - vetted applications only"

    def doc(*items):
        s = "Running review on x.click\n= Findings =\n-- Errors --\n"
        for rid, body in items:
            s += f"  - {rid}\n      {body}\n"
        return s

    allowed = [(ok_a, txt % "picture_files_read"), (ok_b, txt % "video_files_read")]
    cases = [
        ("a allowed only", doc(*allowed), 2, True),
        ("b allowed + other error", doc(*allowed, ("security:template_valid:typewright.apparmor.json", "(ERROR) bad")), 2, False),
        ("c allowed + warning", doc(*allowed) + "-- Warnings --\n  - lint_x:y:z\n      (WARN) something\n", 3, False),
        ("d no findings", "Running review on x.click\n", 0, True),
        ("e different rule id same file", doc((ok_a.replace("fotoz:", "other:"), txt % "picture_files_read")), 2, False),
        ("f garbage", "\x00\x01 total nonsense\nblah\n", 2, False),
        ("g garbage exit 0 with error word", "Traceback (most recent call last)\n", 0, False),
        ("h allowed rule, wrong text", doc((ok_a, "(ERROR) something else")), 2, False),
        ("i nonzero, no findings section", "Running review\n", 1, False),
        ("j unparsable line in findings", "= Findings =\nwhat is this\n", 2, False),
    ]
    bad = 0
    for name, text, rc, want in cases:
        got, _, why = judge(text, rc)
        print(("ok   " if got == want else "FAIL ") + name + ("" if got == want else f" (got {got}, want {want}: {why})"))
        bad += got != want
    print("click-review-gate self-test: " + ("FAILED" if bad else "all passed"))
    return 1 if bad else 0


if __name__ == "__main__":
    a = sys.argv[1:]
    if a[:1] == ["--self-test"]:
        sys.exit(self_test())
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if a[:1] == ["--summary"]:
        summary, a = a[1], a[2:]
    if a[:1] == ["--"]:
        a = a[1:]
    sys.exit(run(a, summary))
