#!/usr/bin/env bash
# The ONLY definition of "builds clean" (FX-002, plan FX-IMP-002 §1).
#
# Do not declare a ticket done on `assembleDebug` alone — a previous round broke
# exactly that way (lintDebug was absent from CI, a lint error sat unnoticed on a
# branch, and "lint passes" became a claim rather than a gate). CI runs this same
# script, so the local gate and the CI gate cannot drift apart.
set -euo pipefail
cd "$(dirname "$0")/.."

# Composite builds need sdk.dir in EACH included build, not just the root.
# Create only if absent — never clobber an existing local.properties, which may
# hold ndk.dir or keystore properties. Fail loudly rather than writing "sdk.dir=".
for d in . hyle-design-system shared-libraries; do
  [ -f "$d/local.properties" ] || echo "sdk.dir=${ANDROID_HOME:?ANDROID_HOME not set}" > "$d/local.properties"
done

# The post-WP1 matrix: both flavors build, test and lint, and the offline flavor passes
# its three enforcement gates — merged manifest, resolved runtime classpath, and the
# targeted source scan. The gates are the offline claim; the rest is the build.
./gradlew --no-daemon \
  :app:testOfflineDebugUnitTest :app:lintOfflineDebug :app:assembleOfflineDebug \
  :app:testConnectDebugUnitTest :app:lintConnectDebug :app:assembleConnectDebug \
  :app:verifyOfflineManifest :app:verifyOfflineRuntimeClasspath :app:verifyOfflineSourceReferences

echo "VERIFY OK  $(git rev-parse --short HEAD)"
