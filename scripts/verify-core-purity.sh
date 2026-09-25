#!/usr/bin/env bash
# WP1.9 (ADR-010 §2): "No android.*, java.*, javax.* or org.w3c.* in commonMain." Every :core:*
# module's own build.gradle.kts declares native (linuxX64/linuxArm64) targets specifically so a
# platform import in commonMain fails to COMPILE there -- but that only bites when someone
# actually builds with -Pfotoz.native, which is opt-in and not every contributor's default. This
# script makes the rule a fast, always-on grep check instead of something only native compilation
# happens to catch.
#
# Scoped to commonMain only -- androidMain/jvmMain/desktopMain are exactly where a platform
# import belongs (:core:db's androidMain-scoped room-ktx, :core:metadata's jvmMain-only XmpPacket,
# both already documented exceptions to this same rule, live there on purpose).
set -euo pipefail
cd "$(dirname "$0")/.."

violations=$(grep -rnE '^\s*import\s+(android|java|javax|org\.w3c)\.' core/*/src/commonMain --include='*.kt' 2>/dev/null || true)

if [ -n "$violations" ]; then
  echo "verify-core-purity: commonMain must never import android.*/java.*/javax.*/org.w3c.* (ADR-010 §2):" >&2
  echo "$violations" >&2
  exit 1
fi

echo "core-purity OK: no android.*/java.*/javax.*/org.w3c.* import in any :core:* commonMain"
