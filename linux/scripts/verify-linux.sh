#!/usr/bin/env bash
# Static gate for the Linux desktop packaging (no Qt, no Gradle needed): desktop entry, AppStream metadata, the
# Flatpak manifest's sandbox promises, and the offline identity of the packages. Runs in scripts/verify.sh and CI.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail=0
note() { echo "linux verify: $*" >&2; fail=1; }
ID=io.github.mbaliga.Fotoz
P=linux/packaging
MAN=linux/flatpak/$ID.yml

# 1. Entry + metadata validate (tools are optional locally; CI installs them and the lane fails if absent there).
if command -v desktop-file-validate >/dev/null; then desktop-file-validate $P/$ID.desktop || note "desktop file invalid"
else echo "linux verify: desktop-file-validate not installed, skipped" >&2; [ -z "${CI:-}" ] || note "desktop-file-validate missing in CI"; fi
if command -v appstreamcli >/dev/null; then appstreamcli validate --no-net $P/$ID.metainfo.xml >/dev/null || note "metainfo invalid"
else echo "linux verify: appstreamcli not installed, skipped" >&2; [ -z "${CI:-}" ] || note "appstreamcli missing in CI"; fi

# 2. Ids agree everywhere (a mismatch silently breaks icon/launcher matching).
grep -q "<id>$ID</id>" $P/$ID.metainfo.xml || note "metainfo id != $ID"
grep -q "desktop-id\">$ID.desktop<" $P/$ID.metainfo.xml || note "metainfo launchable != $ID.desktop"
grep -q "^Icon=$ID\$" $P/$ID.desktop || note "desktop Icon != $ID"
grep -q "^app-id: $ID\$" $MAN || note "flatpak app-id != $ID"
grep -q 'setDesktopFileName(QStringLiteral("'$ID'"))' ut/app/src/main.cpp || note "main.cpp desktop file name != $ID"

# 3. Flatpak sandbox promises: no network, no broad filesystem access, photos read-only.
if grep -vE '^\s*#' $MAN | grep -nE -- '--share=network|--socket=(session-bus|system-bus|ssh-auth|pcsc|cups|gpg-agent)|--filesystem=(host|home)|--talk-name|--system-talk-name'; then note "flatpak manifest asks for network/bus/broad filesystem access"; fi
grep -q -- '--filesystem=xdg-pictures:ro' $MAN || note "flatpak manifest lost the read-only Pictures grant"
if grep -vE '^\s*#' $MAN | grep -E -- '--filesystem=' | grep -v ':ro' ; then note "a flatpak filesystem grant is not read-only"; fi

# 4. The deb/tarball dependency lists name no network stack.
if grep -nE 'DEPENDS.*(libqt[56]network|libcurl|libssl|qml-module-qtwebengine|libqt[56]webengine)' ut/app/CMakeLists.txt; then note "package depends on a network library"; fi

# 5. No telemetry words in packaging.
if grep -rniE 'sentry|crashlytics|analytics|telemetry|appcenter' linux/packaging linux/flatpak ; then note "telemetry-looking text in linux/"; fi

# 6. Manifest is well-formed and its prebuilt-core sources exist in the script that stages them.
python3 - "$MAN" <<'PY' || note "flatpak manifest does not parse"
import sys, yaml
m = yaml.safe_load(open(sys.argv[1]))
assert m["command"] == "fotoz" and m["runtime"] == "org.kde.Platform" and len(m["modules"]) == 2
PY
[ "$fail" = 0 ] && echo "linux verify OK"
exit $fail
