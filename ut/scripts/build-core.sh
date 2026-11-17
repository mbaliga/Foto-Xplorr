#!/usr/bin/env bash
# Builds libfotozcore.so (Kotlin/Native, :ut:bridge) for one click architecture and stages it where
# ut/app/clickable.yaml (-DFOTOZ_CORE_LIB=${ROOT}/prebuilt/${ARCH}/...) expects it.
#   ut/scripts/build-core.sh amd64   # linuxX64   (desktop + amd64 click; CAN be run and tested)
#   ut/scripts/build-core.sh arm64   # linuxArm64 (phone click; links here, runs only on a device)
set -euo pipefail
cd "$(dirname "$0")/../.."
arch="${1:?usage: build-core.sh amd64|arm64}"
case "$arch" in
  amd64) task=linkReleaseSharedLinuxX64;  dir=linuxX64   ;;
  arm64) task=linkReleaseSharedLinuxArm64; dir=linuxArm64 ;;
  *) echo "unknown arch $arch" >&2; exit 2 ;;
esac
export LANG=C.UTF-8 LC_ALL=C.UTF-8
# Maven Central / the plugin portal answer shared CI egress IPs with HTTP 429 now and then (seen on run 44155266105:
# "Could not GET ... gson-2.10.1.pom ... 429 Too Many Requests", 37 s in). Retry ONLY when the log shows a transport-level
# fetch failure; any compile or link error fails on the first attempt.
log="$(mktemp)"
for attempt in 1 2 3 4; do
  if ./gradlew --no-daemon -Pfotoz.native=true ":ut:bridge:$task" 2>&1 | tee "$log"; then break; fi
  if [ "$attempt" -lt 4 ] && grep -qE 'Received status code (429|5[0-9][0-9])|Could not GET|Read timed out|Connection reset' "$log"; then
    echo "transient dependency fetch failure; retry $attempt/3 after $((attempt * 30))s" >&2
    sleep $((attempt * 30))
  else
    exit 1
  fi
done
src="ut/bridge/build/bin/$dir/releaseShared/libfotozcore.so"
[ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
mkdir -p "ut/app/prebuilt/$arch"
cp "$src" "ut/app/prebuilt/$arch/libfotozcore.so"
echo "staged ut/app/prebuilt/$arch/libfotozcore.so ($(stat -c %s "ut/app/prebuilt/$arch/libfotozcore.so") bytes)"
file "ut/app/prebuilt/$arch/libfotozcore.so" || true
