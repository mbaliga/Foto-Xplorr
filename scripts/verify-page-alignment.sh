#!/usr/bin/env bash
# TRAPS #16 / WP1.9: `zipalign -P 16`, never `-p` -- at target 36 the app must support 16 KB
# page sizes, and `-p` silently downgrades correct 16 KB alignment to 4 KB, which stops native
# libraries loading at all on a 16 KB device. `-c` (check) never re-aligns anything -- this
# script is a read-only regression guard, not a build step: AGP 8.9.1's own packaging already
# produces 16 KB-aligned output for every APK this app builds (confirmed by hand against both a
# debug and a release build before this check was added), so a failure here means something
# about the build changed, not that this script needs to go fix it with `-p`.
#
# Takes one or more APK paths as arguments. Requires `zipalign` from the Android SDK's
# build-tools on PATH or via $ANDROID_HOME/build-tools/*/zipalign.
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: verify-page-alignment.sh <apk> [apk...]" >&2
  exit 1
fi

zipalign_bin="$(command -v zipalign || true)"
if [ -z "$zipalign_bin" ]; then
  zipalign_bin="$(find "${ANDROID_HOME:-/opt/android-sdk}/build-tools" -maxdepth 2 -name zipalign 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "$zipalign_bin" ]; then
  echo "verify-page-alignment: zipalign not found (checked PATH and \$ANDROID_HOME/build-tools)" >&2
  exit 1
fi

status=0
for apk in "$@"; do
  echo "verify-page-alignment: checking $apk"
  if ! "$zipalign_bin" -c -P 16 4 "$apk"; then
    echo "verify-page-alignment: $apk is NOT 16 KB page-aligned (TRAPS #16)" >&2
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  echo "page-alignment OK: every checked APK is 16 KB page-aligned"
fi
exit "$status"
