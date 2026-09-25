#!/usr/bin/env bash
# WP1.9 (ADR-009 decision 5): the `core-native` CI job's own entry point, and what
# `scripts/verify.sh --native` calls into -- one definition of the native task list, not two
# that can drift apart (the same FX-002 reasoning verify.sh's own header states for itself).
#
# `linuxX64Test`, not just compile -- an x86 runner can actually EXECUTE a linuxX64 binary, so
# running the test suite there is real verification, not just a compile check.
# `compileKotlinLinuxArm64` only, since it can't execute an ARM64 binary at all (ADR-009 item 5's
# own exact wording). Listed by module rather than a wildcard task name so a module that stops
# declaring a native target fails this script loudly (an unknown task) instead of silently
# dropping out of the gate.
set -euo pipefail
cd "$(dirname "$0")/.."

for d in . hyle-design-system shared-libraries; do
  [ -f "$d/local.properties" ] || echo "sdk.dir=${ANDROID_HOME:?ANDROID_HOME not set}" > "$d/local.properties"
done

NATIVE_TASKS=(
  :core:model:linuxX64Test :core:model:compileKotlinLinuxArm64
  :core:formats:linuxX64Test :core:formats:compileKotlinLinuxArm64
  :core:metadata:linuxX64Test :core:metadata:compileKotlinLinuxArm64
  :core:search:linuxX64Test :core:search:compileKotlinLinuxArm64
  :core:organize:linuxX64Test :core:organize:compileKotlinLinuxArm64
  :core:db:linuxX64Test :core:db:compileKotlinLinuxArm64
  :core:index:linuxX64Test :core:index:compileKotlinLinuxArm64
)

./gradlew --no-daemon -Pfotoz.native=true "${NATIVE_TASKS[@]}"

echo "VERIFY-NATIVE OK  $(git rev-parse --short HEAD)"
