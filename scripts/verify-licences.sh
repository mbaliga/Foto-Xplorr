#!/usr/bin/env bash
# WP1.9: licence scan of the RESOLVED release runtime classpath of both flavors.
#
#   scripts/verify-licences.sh [report-dir]   apply the policy to the files written by
#                                             `./gradlew :app:reportRuntimeLicences`
#                                             (default app/build/reports/licences/<flavor>.tsv)
#   scripts/verify-licences.sh --self-test    exercise the policy on built-in fixtures (no Gradle)
#
# The Gradle task reads each component's POM (and its parent POMs) with Gradle's own artifact
# resolution query -- no plugin, no dependency -- and writes "group:name:version<TAB>licence names".
# This script holds the POLICY, so the self-test can prove it without a build:
#
#   FAIL  a component whose every declared licence is GPL or AGPL (a dual-licensed component with
#         at least one permissive choice is not a failure: you may take the other licence).
#   FAIL  a component whose licence is not known because its POM could not be fetched ("?") or
#         named no licence ("-"; the Gradle task also writes "-" when a POM fails to parse), UNLESS
#         an allowlist entry covers it. Otherwise a GPL library whose POM fetch timed out or hit
#         HTTP 429 would pass the gate; an unknown licence is treated as a failure, not a shrug.
#   WARN  LGPL, CDDL, EPL, MPL, GPL-with-classpath-exception, a dual licence that offers GPL, and a
#         licence name this script does not recognise.
#   OK    a recognised permissive licence (Apache, MIT, BSD, ISC, zlib, public domain, CC0 ...),
#         or any warning covered by an entry in scripts/licence-allowlist.txt.
#
# Allowlist lines: "<group>:<name>[:<version>] | <reason>" ; a trailing * is a prefix match;
# '#' starts a comment. An entry that matches nothing is reported as stale (warning), so the list
# cannot rot. An allowlist entry never excuses a GPL/AGPL failure; it does clear an unknown ("?" or
# "-") licence, because that is exactly the reviewed exception the list exists for.
set -euo pipefail
cd "$(dirname "$0")/.."

ALLOWLIST="${LICENCE_ALLOWLIST:-scripts/licence-allowlist.txt}"

# check_file <tsv> <allowlist> <label>: prints findings, returns 1 on a failure. Warnings go to
# stdout with a "WARN" prefix; the summary line is always printed.
check_file() {
  local tsv="$1" allow="$2" label="$3"
  awk -F'\t' -v label="$label" -v allowfile="$allow" '
    function lc(s) { return tolower(s) }
    # classify one licence name: returns P (permissive), G (GPL/AGPL strong copyleft),
    # W (weak copyleft or GPL with classpath exception), U (unrecognised)
    function classify(n,   l) {
      l = lc(n)
      if (l ~ /classpath exception/ && l ~ /gpl/) return "W"
      if (l ~ /lgpl|lesser general public|library general public/) return "W"
      if (l ~ /agpl|affero/) return "G"
      if (l ~ /(^|[^a-z])gpl|general public licen/) return "G"
      if (l ~ /cddl|common development and distribution|eclipse public|(^|[^a-z])epl|mozilla public|(^|[^a-z])mpl/) return "W"
      if (l ~ /apache|(^|[^a-z])mit([^a-z]|$)|bsd|(^|[^a-z])isc([^a-z]|$)|zlib|public domain|cc0|unlicense|eclipse distribution|(^|[^a-z])edl|boost/) return "P"
      return "U"
    }
    function allowed(id,   i, p) {
      for (i = 1; i <= nallow; i++) {
        p = pat[i]
        if (substr(p, length(p)) == "*") { if (index(id, substr(p, 1, length(p) - 1)) == 1) { used[i] = 1; return reason[i] } }
        else if (id == p || index(id, p ":") == 1) { used[i] = 1; return reason[i] }
      }
      return ""
    }
    BEGIN {
      while ((getline line < allowfile) > 0) {
        sub(/^[ \t]+/, "", line)
        if (line == "" || substr(line, 1, 1) == "#") continue
        k = index(line, "|")
        nallow++
        pat[nallow] = (k ? substr(line, 1, k - 1) : line); gsub(/[ \t]+$/, "", pat[nallow])
        reason[nallow] = (k ? substr(line, k + 1) : ""); sub(/^[ \t]+/, "", reason[nallow])
      }
    }
    NF >= 2 && $1 != "" {
      total++
      id = $1; names = $2
      n = split(names, parts, / \| /)
      strong = 0; weak = 0; perm = 0; unk = 0
      if (names == "-" || names == "?") { unk = 1 }
      else for (i = 1; i <= n; i++) {
        c = classify(parts[i])
        if (c == "G") strong++; else if (c == "W") weak++; else if (c == "P") perm++; else unk++
      }
      if (strong > 0 && perm == 0 && weak == 0 && unk == 0) {
        printf "FAIL %s: %s  (%s)\n", label, id, names; fails++; next
      }
      if (names == "-" || names == "?") {
        if (allowed(id) != "") { allowlisted++; next }
        printf "FAIL %s: %s  [%s; add a reviewed allowlist entry if this is known to be fine]  (%s)\n", label, id, (names == "?" ? "POM could not be fetched, licence unknown" : "no licence declared in any POM (or the POM did not parse)"), names
        fails++; next
      }
      why = ""
      if (strong > 0) why = "offers GPL/AGPL as one of several licences"
      else if (weak > 0) why = "weak copyleft (LGPL/CDDL/EPL/MPL/classpath exception)"
      else if (unk > 0) why = "unrecognised licence name"
      if (why == "") { ok++; next }
      r = allowed(id)
      if (r != "") { allowlisted++; next }
      printf "WARN %s: %s  [%s]  (%s)\n", label, id, why, names; warns++
    }
    END {
      for (i = 1; i <= nallow; i++) if (!used[i]) { printf "WARN %s: stale allowlist entry (matches nothing): %s\n", label, pat[i]; stale++ }
      printf "SUMMARY %s: %d components, %d permissive, %d allowlisted, %d warnings, %d stale allowlist entries, %d FAILURES\n", label, total, ok, allowlisted, warns + 0, stale + 0, fails + 0
      exit (fails > 0 ? 1 : 0)
    }
  ' "$tsv"
}

# The allowlist may legitimately match components of only one flavor (e.g. ML Kit Translate is
# Connect-only), so "stale" is judged across both flavors: run each flavor with the allowlist and
# only report an entry as stale when NEITHER flavor used it.
scan() {
  local dir="$1" rc=0 f out
  [ -f "$ALLOWLIST" ] || { echo "verify-licences: $ALLOWLIST missing" >&2; return 1; }
  for f in offline connect; do
    [ -f "$dir/$f.tsv" ] || { echo "verify-licences: $dir/$f.tsv missing -- run ./gradlew :app:reportRuntimeLicences first" >&2; return 1; }
  done
  # Concatenate to judge staleness once: ids are prefixed with the flavor for the report only.
  local merged; merged="$(mktemp)"
  cat "$dir/offline.tsv" "$dir/connect.tsv" | sort -u > "$merged"
  # per-flavor findings (without the stale check) ...
  for f in offline connect; do
    out="$(check_file "$dir/$f.tsv" "$ALLOWLIST" "$f" || true)"
    echo "$out" | grep -v "stale allowlist entry" || true
    if echo "$out" | grep -q '^FAIL'; then rc=1; fi
  done
  # ... and the stale check over the union.
  check_file "$merged" "$ALLOWLIST" "union" | grep "stale allowlist entry" || true
  rm -f "$merged"
  return "$rc"
}

self_test() {
  local t; t="$(mktemp -d)"; trap 'rm -rf "$t"' RETURN
  local fails=0
  expect() { # expect <name> <expected-exit> <grep-pattern-or-empty> <tsv> <allowlist>
    local name="$1" want="$2" pat="$3" tsv="$4" allow="$5" out rc=0
    out="$(check_file "$tsv" "$allow" fixture)" || rc=$?
    if [ "$rc" -ne "$want" ] || { [ -n "$pat" ] && ! echo "$out" | grep -q "$pat"; }; then
      echo "SELF-TEST FAILED: $name (exit $rc, wanted $want, pattern '$pat')"; echo "$out"; fails=$((fails + 1))
    else
      echo "self-test ok: $name"
    fi
  }
  : > "$t/empty.txt"
  printf 'a:ok:1\tThe Apache Software License, Version 2.0\nb:mit:1\tMIT License\nc:bsd:1\tBSD-3-Clause\n' > "$t/clean.tsv"
  expect "clean list passes with no findings" 0 'SUMMARY.*0 FAILURES' "$t/clean.tsv" "$t/empty.txt"

  # the plan's acceptance: a deliberately bad dependency makes the check fail
  printf 'a:ok:1\tApache-2.0\nbad:gpl-lib:2.0\tGNU General Public License, version 3\n' > "$t/gpl.tsv"
  expect "GPL dependency FAILS" 1 'FAIL fixture: bad:gpl-lib:2.0' "$t/gpl.tsv" "$t/empty.txt"
  printf 'bad:agpl-lib:1\tGNU Affero General Public License v3.0\n' > "$t/agpl.tsv"
  expect "AGPL dependency FAILS" 1 'FAIL' "$t/agpl.tsv" "$t/empty.txt"
  printf 'bad:gpl-lib:2.0\tGPLv2\n' > "$t/gplv2.tsv"
  expect "short GPLv2 name FAILS" 1 'FAIL' "$t/gplv2.tsv" "$t/empty.txt"
  printf 'bad:gpl-lib:2.0\tGNU General Public License, version 3\n' > "$t/gpl2.tsv"
  printf 'bad:gpl-lib | pretend this excuses it\n' > "$t/allow-gpl.txt"
  expect "allowlist never excuses GPL" 1 'FAIL' "$t/gpl2.tsv" "$t/allow-gpl.txt"

  printf 'l:lgpl:1\tGNU Lesser General Public License v2.1\n' > "$t/lgpl.tsv"
  expect "LGPL only warns" 0 'WARN fixture: l:lgpl:1' "$t/lgpl.tsv" "$t/empty.txt"
  printf 'c:cddl:1\tCDDL + GPLv2 with classpath exception\n' > "$t/cddl.tsv"
  expect "GPL with classpath exception only warns" 0 'WARN' "$t/cddl.tsv" "$t/empty.txt"
  printf 'd:dual:1\tGPL-2.0 | MIT\n' > "$t/dual.tsv"
  expect "dual GPL | MIT warns, does not fail" 0 'offers GPL' "$t/dual.tsv" "$t/empty.txt"
  printf 'v:odd:1\tML Kit Terms of Service\n' > "$t/odd.tsv"
  expect "unrecognised licence name warns" 0 'WARN fixture: v:odd:1' "$t/odd.tsv" "$t/empty.txt"
  printf 'u:none:1\t-\n' > "$t/none.tsv"
  expect "no licence declared FAILS" 1 'FAIL fixture: u:none:1' "$t/none.tsv" "$t/empty.txt"
  printf 'p:nopom:1\t?\n' > "$t/q.tsv"
  expect "unfetchable POM FAILS" 1 'FAIL fixture: p:nopom:1.*POM could not be fetched' "$t/q.tsv" "$t/empty.txt"
  printf 'p:nopom:1\t?\na:ok:1\tApache-2.0\n' > "$t/q2.tsv"
  printf 'p:nopom-extra | a longer name is not this component\np:no | nor is a shorter one\n' > "$t/allow-near.txt"
  expect "allowlist matches the coordinate exactly, not a near name" 1 'FAIL fixture: p:nopom:1' "$t/q2.tsv" "$t/allow-near.txt"
  printf 'p:nopom | reviewed: vendor artifact, terms read by hand\n' > "$t/allow-q.txt"
  expect "allowlisted unfetchable POM passes" 0 '1 allowlisted, 0 warnings.*0 FAILURES' "$t/q.tsv" "$t/allow-q.txt"
  printf 'u:none:1\t-\nv:odd:1\tML Kit Terms of Service\n' > "$t/unk.tsv"
  printf 'u:none | known vendor artifact with no POM licence\nv:odd:1 | proprietary terms, reviewed\n' > "$t/allow-ok.txt"
  expect "allowlisted components are quiet" 0 '2 allowlisted, 0 warnings' "$t/unk.tsv" "$t/allow-ok.txt"
  printf 'x:never:* | matches nothing\n' > "$t/allow-stale.txt"
  expect "stale allowlist entry is reported" 0 'stale allowlist entry' "$t/clean.tsv" "$t/allow-stale.txt"
  printf 'q:glob:1\tOdd Terms\nq:glob:2\tOdd Terms\nr:other:1\tOdd Terms\n' > "$t/glob.tsv"
  printf 'q:* | prefix match\n' > "$t/allow-glob.txt"
  expect "prefix glob covers q:* but not r:other" 0 'WARN fixture: r:other:1' "$t/glob.tsv" "$t/allow-glob.txt"

  if [ "$fails" -ne 0 ]; then echo "verify-licences self-test: $fails FAILED" >&2; return 1; fi
  echo "verify-licences self-test OK"
}

case "${1:-}" in
  --self-test) self_test ;;
  "") scan app/build/reports/licences ;;
  -*) echo "verify-licences: unknown option '$1'" >&2; exit 2 ;;
  *) scan "$1" ;;
esac
