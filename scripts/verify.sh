#!/usr/bin/env bash
# The ONLY definition of "builds clean" (FX-002, plan FX-IMP-002 §1).
#
# Do not declare a ticket done on `assembleDebug` alone — a previous round broke
# exactly that way (lintDebug was absent from CI, a lint error sat unnoticed on a
# branch, and "lint passes" became a claim rather than a gate). CI runs this same
# script, so the local gate and the CI gate cannot drift apart.
set -euo pipefail
cd "$(dirname "$0")/.."

# ADR-009 (WP1.1): local use stays exactly as it was -- `--native` is opt-in, additive, and
# absent by default so this script's normal behaviour and runtime don't change for anyone who
# doesn't pass it. Kotlin/Native compilers don't run on ARM64 Linux hosts (the owner's own
# Termux/proot environment), so leaving native compilation out of the default run is load-bearing,
# not laziness -- see ADR-009 decision 3.
NATIVE=0
for arg in "$@"; do
  case "$arg" in
    --native) NATIVE=1 ;;
    *)
      echo "verify.sh: unknown argument '$arg' (only --native is recognised)" >&2
      exit 1
      ;;
  esac
done

# Composite builds need sdk.dir in EACH included build, not just the root.
# Create only if absent — never clobber an existing local.properties, which may
# hold ndk.dir or keystore properties. Fail loudly rather than writing "sdk.dir=".
for d in . hyle-design-system shared-libraries; do
  [ -f "$d/local.properties" ] || echo "sdk.dir=${ANDROID_HOME:?ANDROID_HOME not set}" > "$d/local.properties"
done

# The post-WP1 matrix: both flavors build, test and lint, and the offline flavor passes
# its three enforcement gates — merged manifest, resolved runtime classpath, and the
# targeted source scan. The gates are the offline claim; the rest is the build.
GRADLE_ARGS=(
  --no-daemon
  :app:testOfflineDebugUnitTest :app:lintOfflineDebug :app:assembleOfflineDebug
  :app:testConnectDebugUnitTest :app:lintConnectDebug :app:assembleConnectDebug
  :app:verifyOfflineManifest :app:verifyOfflineRuntimeClasspath :app:verifyOfflineSourceReferences
)

# NATIVE_TASKS is empty until WP1.2 lands the first native-ready :core:* module (ADR-009
# decision 5, the `core-native` CI job). Passing -Pfotoz.native=true with nothing to build
# against is a harmless no-op -- Gradle ignores an unread project property -- so this flag is
# safe to use starting now, even before there is anything native to compile.
NATIVE_TASKS=()

if [ "$NATIVE" -eq 1 ]; then
  GRADLE_ARGS+=("-Pfotoz.native=true" "${NATIVE_TASKS[@]}")
fi

./gradlew "${GRADLE_ARGS[@]}"

echo "VERIFY OK  $(git rev-parse --short HEAD)"
