#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Hard gate around `clickable review` (click-review) with a minimal, owner-decided allowlist.

  ut/scripts/click-review-gate.py [--summary FILE] -- <clickable review args...>
  ut/scripts/click-review-gate.py --self-test

Runs `clickable review <args>`, prints its output IN FULL, parses the findings and exits 0 only when
every finding is on ALLOWLIST (exact rule id, in the Errors section) AND carries exactly the expected
"(NEEDS REVIEW)" text. Anything else fails: another error, any warning, a known rule id with different
text, output that does not parse, a missing clickable, or an exit code that contradicts the verdict.

OWNER DECISION 2026-11-18 (Option 2, "narrow allowance"): the two findings below are INHERENT to the app
(ut/app/fotoz.apparmor.in, ADR-013: reserved policy groups for reading Pictures/Videos) and only OpenStore
reviewers can clear them, so CI tolerates exactly these and nothing else. Keep this list MINIMAL; the
OpenStore review must still clear them before any store submission (docs/adr/ADR-013-linux-strategy.md,
ut/OWNER_VERIFY.md).

LAYOUT. The parser reads what click-review really prints (REAL_FOTOZ_ARM64 below is a real CI log; the
sibling Typewright and Liftoff logs have the same shape for other rules and for Warnings):

  Errors                      a bare section title, 'Errors' or 'Warnings' ...
  ------                      ... underlined by dashes of the same length
   - <rule id>                one entry per finding
  <TAB><text>                 one or more tab-indented lines (the text, then an optional link)
  <path>.click: FAIL          exactly one verdict line, 'pass' or 'FAIL'; it ends the sections

Clickable's own lines (the "Running review on" banner, the podman command and traceback that --verbose
adds, "Command exited with ...") sit before the first section and after the verdict and are ignored THERE
only. FAIL-CLOSED: an unknown section title, a title without its underline, any line inside a section that
is neither an entry nor tab-indented text, an entry with no text, an entry outside a section, a section
after the verdict, a missing or repeated verdict, a verdict for another file, FAIL with no parsed findings,
'pass' together with findings or a non-zero exit, FAIL with an exit code other than 2 -- each fails.
(Clickable 8.10.0 exits 2 whenever click-review fails, 1 on its own config errors, 3 on an unknown error:
clickable/__init__.py main(). click-review itself returns 2 for errors and 3 for warnings.)
"""
import os
import re
import subprocess
import sys

# Rule id -> the exact text click-review prints for it. Both must match; nothing else is tolerated.
ALLOWLIST = {
    "security:policy_groups_safe:fotoz:picture_files_read":
        "(NEEDS REVIEW) reserved policy group 'picture_files_read': vetted applications only",
    "security:policy_groups_safe:fotoz:video_files_read":
        "(NEEDS REVIEW) reserved policy group 'video_files_read': vetted applications only",
}
SECTIONS = ("Errors", "Warnings")
CLICKABLE_FAIL_RC = 2

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
UNDERLINE = re.compile(r"^-+$")
ENTRY = re.compile(r"^ - (\S.*)$")
VERDICT = re.compile(r"^(\S.*\.click): (pass|FAIL)$")


def parse(text):
    """-> (findings [(section, rule_id, text)], verdicts [(path, 'pass'|'FAIL')], problems [str])."""
    lines = ANSI.sub("", text).splitlines()
    findings, verdicts, problems = [], [], []
    section, cur, seen, skip = None, None, set(), False
    for i, line in enumerate(lines):
        if skip:  # the underline of the title just read
            skip = False
            continue
        v = VERDICT.match(line)
        if v:
            verdicts.append((v.group(1), v.group(2)))
            section, cur = None, None
            continue
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        underlined = bool(line.strip()) and bool(UNDERLINE.match(nxt)) and len(nxt) == len(line)
        if underlined or line in SECTIONS:
            skip = underlined
            cur = None
            if not underlined:
                problems.append(f"section title without its underline: {line!r}")
            if line not in SECTIONS:
                problems.append(f"unknown section: {line!r}")
            elif line in seen:
                problems.append(f"repeated section: {line!r}")
            if verdicts:
                problems.append(f"section after the verdict line: {line!r}")
            seen.add(line)
            section = line
            continue
        e = ENTRY.match(line)
        if section is None:
            if e:
                problems.append(f"finding outside a section: {line!r}")
            continue  # Clickable's own lines around the review
        if e:
            cur = [section, e.group(1), []]
            findings.append(cur)
        elif cur is not None and line.startswith("\t") and line.strip():
            cur[2].append(line[1:])
        else:
            problems.append(f"unparsable line in section {section}: {line!r}")
    for _, rule, body in findings:
        if not body:
            problems.append(f"finding without text: {rule}")
    return [(s, r, "\n".join(b)) for s, r, b in findings], verdicts, problems


def judge(text, rc, click=None):
    """-> (ok, tolerated [rule ids], reasons [str]). `click` is the reviewed file, when known."""
    findings, verdicts, reasons = parse(text)
    tolerated = []
    for section, rule, body in findings:
        if section != "Errors" or rule not in ALLOWLIST:
            reasons.append(f"not allowlisted ({section}): {rule}")
        elif body != ALLOWLIST[rule]:
            reasons.append(f"allowlisted rule with unexpected text: {rule}: {body!r}")
        elif rule in tolerated:
            reasons.append(f"finding reported twice: {rule}")
        else:
            tolerated.append(rule)
    if len(verdicts) != 1:
        reasons.append(f"expected exactly one '<path>.click: pass|FAIL' line, found {len(verdicts)}")
    else:
        path, verdict = verdicts[0]
        if click and os.path.basename(path) != os.path.basename(click):
            reasons.append(f"verdict is for {path!r}, not for {click!r}")
        if verdict == "FAIL":
            if not findings:
                reasons.append("verdict FAIL but no parsable findings")
            if rc != CLICKABLE_FAIL_RC:
                reasons.append(f"verdict FAIL but exit code {rc} (clickable exits {CLICKABLE_FAIL_RC} on a failed review)")
        else:
            if findings:
                reasons.append("verdict pass but findings were reported")
            if rc != 0:
                reasons.append(f"verdict pass but exit code {rc}")
    return (not reasons), tolerated, reasons


def run(argv, summary):
    clicks = [a for a in argv if a.endswith(".click")]
    try:
        p = subprocess.run(["clickable", "review", *argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, errors="replace")
    except FileNotFoundError:
        print("click-review-gate: FAIL: `clickable` not found on PATH")
        return 1
    out = p.stdout
    print(out)
    ok, tolerated, reasons = judge(out, p.returncode, clicks[0] if len(clicks) == 1 else None)
    if ok:
        if tolerated:
            line = ("click-review-gate: PASS. Tolerated by OWNER DECISION 2026-11-18 (narrow allowance, ADR-013; only "
                    "OpenStore review can clear them): " + ", ".join(sorted(tolerated)) +
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


# --- self-test fixture: REAL output -----------------------------------------------------------------------------------
# Run 44164703226, job 134331562267 "Click (arm64, unsigned)", 2026-11-17: the review `clickable build --verbose` ran on
# the Fotoz click (Clickable 8.10.0, exit code 2). Log timestamps removed, nothing else: colour codes, the tab-indented
# text and Clickable's --verbose lines are as logged. The podman argv appears three times in that log (as a shell
# line, as a Python list, inside the CalledProcessError message); it is written once here and formatted three ways,
# which gives the same bytes. A standalone `clickable review` prints the same lines minus the build.py frames.
_CLICK = "/home/runner/work/Foto-Xplorr/Foto-Xplorr/dist/fotoz.mbaliga_0.1.0_arm64.click"
_APP = "/home/runner/work/Foto-Xplorr/Foto-Xplorr/ut/app"
_DIST = "/home/runner/work/Foto-Xplorr/Foto-Xplorr/dist"
_BUILD = _APP + "/build/aarch64-linux-gnu/app"
_ENV = [
    ("GOPATH", "/home/runner/.clickable/go"), ("SDK_FRAMEWORK", "ubuntu-touch-24.04-1.x"),
    ("CLICK_FRAMEWORK", "ubuntu-touch-24.04-1.x"), ("CLICK_FRAMEWORK_BASE", "24.04-1.x"), ("APPARMOR_POLICY", "2404.1"),
    ("QT_VERSION", "5.12"), ("ARCH", "arm64"), ("ARCH_TRIPLET", "aarch64-linux-gnu"),
    ("ARCH_RUST", "aarch64-unknown-linux-gnu"), ("NUM_PROCS", "4"), ("ROOT", _APP), ("BUILD_DIR", _BUILD),
    ("SRC_DIR", _APP), ("INSTALL_DIR", _BUILD + "/install"),
    ("CLICK_LD_LIBRARY_PATH", _BUILD + "/install/lib/aarch64-linux-gnu"),
    ("CLICK_PATH", _BUILD + "/install/lib/aarch64-linux-gnu/bin"),
    ("CLICK_QML2_IMPORT_PATH", _BUILD + "/install/lib/aarch64-linux-gnu"), ("HOME", _BUILD + "/.clickable/home"),
]
_IMAGE = "docker.io/clickable/amd64-ut24.04-1.x-arm64"
_SCRIPT = ("set -Eeou pipefail\ntrap exit SIGINT\ntrap exit SIGTERM\nexecute() {\nclick-review " + _CLICK +
           "\n}\nexecute &\nbg_pid=$!\nwait $bg_pid\nexit $?")
_ARGV = (["podman", "run", "-v", f"{_DIST}:{_DIST}:Z", "-v", f"{_APP}:{_APP}:Z"] +
         [x for k, v in _ENV for x in ("-e", f"{k}={v}")] +
         ["--user", "1001", "--uidmap", "1001:0:1", "--uidmap", "0:1:1001", "--gidmap", "1001:0:1", "--gidmap",
          "0:1:1001", "-w", _DIST, "--rm", "-i", _IMAGE, "bash", "-c", _SCRIPT])
_PKG = "/home/runner/work/_temp/venv/lib/python3.12/site-packages/clickable"
_REVIEW = (
    "Errors\n"
    "------\n"
    " - security:policy_groups_safe:fotoz:picture_files_read\n"
    "\t(NEEDS REVIEW) reserved policy group 'picture_files_read': vetted applications only\n"
    " - security:policy_groups_safe:fotoz:video_files_read\n"
    "\t(NEEDS REVIEW) reserved policy group 'video_files_read': vetted applications only\n"
    + _CLICK + ": FAIL\n"
)
REAL_FOTOZ_ARM64 = (
    "\x1b[92mRunning review on " + _CLICK + "\x1b[0m\n"
    "\x1b[94mSkipping docker check as podman is used instead.\x1b[0m\n"
    "\x1b[94mComplete command to run:\n"
    f"podman run -v {_DIST}:{_DIST}:Z -v {_APP}:{_APP}:Z " + "".join(f'-e {k}="{v}" ' for k, v in _ENV) + "\n"
    "                --user 1001 --uidmap 1001:0:1 --uidmap 0:1:1001 --gidmap 1001:0:1 --gidmap 0:1:1001 -w " + _DIST +
    " --rm  \n"
    "                -i " + _IMAGE + ' bash -c "set -Eeou pipefail\n'
    'trap "exit" SIGINT\n'
    'trap "exit" SIGTERM\n'
    "execute() {\n"
    "click-review " + _CLICK + "\n"
    "}\n"
    "execute &\n"
    "bg_pid=$!\n"
    "wait $bg_pid\n"
    'exit $?"\x1b[0m\n'
    + _REVIEW +
    "\x1b[94mCommand exited with an error:" + str(_ARGV) + "\x1b[0m\n"
    "Traceback (most recent call last):\n"
    f'  File "{_PKG}/__init__.py", line 70, in main\n'
    "    clickable.run()\n"
    f'  File "{_PKG}/__init__.py", line 58, in run\n'
    "    args.func(args)\n"
    f'  File "{_PKG}/commands/base.py", line 62, in start\n'
    "    self.run()\n"
    f'  File "{_PKG}/commands/build.py", line 193, in run\n'
    "    self.build_app()\n"
    f'  File "{_PKG}/commands/build.py", line 236, in build_app\n'
    "    review.check(\n"
    f'  File "{_PKG}/commands/review.py", line 67, in check\n'
    "    raise e\n"
    f'  File "{_PKG}/commands/review.py", line 56, in check\n'
    "    self.container.run_command(\n"
    f'  File "{_PKG}/container.py", line 382, in run_command\n'
    "    subprocess.check_call(shlex.split(wrapped_command), **kwargs)\n"
    '  File "/usr/lib/python3.12/subprocess.py", line 413, in check_call\n'
    "    raise CalledProcessError(retcode, cmd)\n"
    "subprocess.CalledProcessError: Command '" + str(_ARGV) + "' returned non-zero exit status 2.\n"
    "\x1b[91mCommand exited with non-zero exit status 2, see above for details. "
    "This is most likely not a problem with Clickable.\x1b[0m\n"
)


def self_test():
    ok_a, ok_b = sorted(ALLOWLIST)
    allowed = [(ok_a, ALLOWLIST[ok_a]), (ok_b, ALLOWLIST[ok_b])]
    click = "/w/dist/fotoz.mbaliga_0.1.0_arm64.click"
    tail = ("Command exited with non-zero exit status 2, see above for details. "
            "This is most likely not a problem with Clickable.\n")

    def sec(title, *items):
        return title + "\n" + "-" * len(title) + "\n" + "".join(f" - {rid}\n\t{body}\n" for rid, body in items)

    def doc(*sections, verdict="FAIL"):
        return f"Running review on {click}\n" + "".join(sections) + f"{click}: {verdict}\n" + (tail if verdict == "FAIL" else "")

    real = REAL_FOTOZ_ARM64
    other = ("security:template_valid:fotoz.apparmor", "(NEEDS REVIEW) 'unconfined' not allowed\n\thttp://askubuntu.com/q/671403")
    warn = sec("Warnings", ("desktop:Exec:fotoz", "(WARN) something"))
    cases = [
        ("real: Fotoz arm64 CI log (allowed only)", real, 2, _CLICK, True),
        ("real: same, other error added", real.replace(" - " + ok_b, " - %s\n\t%s\n - %s" % (other + (ok_b,))), 2, _CLICK, False),
        ("real: same, Warnings section added", real.replace(_CLICK + ": FAIL", warn + _CLICK + ": FAIL"), 2, _CLICK, False),
        ("real: same, verdict for another click", real, 2, "/w/dist/other_1.0_arm64.click", False),
        ("real: same, exit 0", real, 0, _CLICK, False),
        ("real: same, exit 1 (clickable's own error)", real, 1, _CLICK, False),
        ("real: same, verdict line missing", real.replace(_CLICK + ": FAIL\n", ""), 2, _CLICK, False),
        ("real: same, verdict line twice", real.replace(_CLICK + ": FAIL\n", (_CLICK + ": FAIL\n") * 2), 2, _CLICK, False),
        ("real: same, text of one rule changed", real.replace("'video_files_read': vetted", "'video_files_read': anyone"), 2, _CLICK, False),
        ("real: same, a link line under an allowed rule", real.replace("only\n" + _CLICK, "only\n\thttp://x\n" + _CLICK), 2, _CLICK, False),
        ("real: same, stray line inside the section", real.replace(" - " + ok_b, "surprise\n - " + ok_b), 2, _CLICK, False),
        ("real: same, review printed twice", real.replace(_REVIEW, _REVIEW * 2), 2, _CLICK, False),
        ("a allowed only", doc(sec("Errors", *allowed)), 2, click, True),
        ("b allowed + other error", doc(sec("Errors", *allowed, other)), 2, click, False),
        ("c allowed + warning", doc(sec("Errors", *allowed), warn), 2, click, False),
        ("c2 warning + allowed (other order)", doc(warn, sec("Errors", *allowed)), 2, click, False),
        ("c3 warning only", doc(warn), 2, click, False),
        ("d no findings, pass", doc(verdict="pass"), 0, click, True),
        ("d2 pass verdict but exit 2", doc(verdict="pass"), 2, click, False),
        ("d3 pass verdict with allowed findings", doc(sec("Errors", *allowed), verdict="pass"), 0, click, False),
        ("e different rule id, same text", doc(sec("Errors", (ok_a.replace("fotoz:", "other:"), ALLOWLIST[ok_a]))), 2, click, False),
        ("e2 allowed rule under Warnings", doc(sec("Warnings", *allowed)), 2, click, False),
        ("f garbage", "\x00\x01 total nonsense\nblah\n", 2, click, False),
        ("f2 garbage, exit 0", "Traceback (most recent call last)\n", 0, click, False),
        ("f3 empty output, exit 0", "", 0, click, False),
        ("g FAIL verdict, no findings", doc(), 2, click, False),
        ("h allowed rule, wrong text", doc(sec("Errors", (ok_a, "(ERROR) something else"))), 2, click, False),
        ("i unknown section", doc(sec("Errors", *allowed), sec("Notes", ("x:y:z", "hm"))), 2, click, False),
        ("j title without underline", doc("Errors\n" + sec("Errors", *allowed).split("\n", 2)[2]), 2, click, False),
        ("k underline of the wrong length", doc(sec("Errors", *allowed).replace("------", "----")), 2, click, False),
        ("l entry without text", doc(sec("Errors", *allowed) + " - " + ok_a + "\n"), 2, click, False),
        ("m entry outside a section", doc(" - " + ok_a + "\n\t" + ALLOWLIST[ok_a] + "\n"), 2, click, False),
        ("n same allowed rule twice", doc(sec("Errors", allowed[0], allowed[0])), 2, click, False),
        ("o old assumed '= Findings =' layout", "Running review on x.click\n= Findings =\n-- Errors --\n  - " + ok_a + "\n      " + ALLOWLIST[ok_a] + "\n", 2, click, False),
        ("p section after the verdict", doc(sec("Errors", *allowed)) + sec("Warnings", ("x:y:z", "late")), 2, click, False),
        ("q blank line inside a section", doc(sec("Errors", *allowed) + "\n"), 2, click, False),
    ]
    bad = 0
    for name, text, rc, clk, want in cases:
        got, tol, why = judge(text, rc, clk)
        if got and want and name[0] in "ar" and sorted(tol) != [ok_a, ok_b]:
            got, why = False, [f"tolerated {tol}"]
        print(("ok   " if got == want else "FAIL ") + name + ("" if got == want else f" (got {got}, want {want}: {why})"))
        bad += got != want
    if len(ALLOWLIST) != 2:
        print("FAIL the allowlist must hold exactly the two owner-approved rule ids")
        bad += 1
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
