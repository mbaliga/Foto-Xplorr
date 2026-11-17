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
./gradlew --no-daemon -Pfotoz.native=true ":ut:bridge:$task"
src="ut/bridge/build/bin/$dir/releaseShared/libfotozcore.so"
[ -f "$src" ] || { echo "missing $src" >&2; exit 1; }
mkdir -p "ut/app/prebuilt/$arch"
cp "$src" "ut/app/prebuilt/$arch/libfotozcore.so"
echo "staged ut/app/prebuilt/$arch/libfotozcore.so ($(stat -c %s "ut/app/prebuilt/$arch/libfotozcore.so") bytes)"
file "ut/app/prebuilt/$arch/libfotozcore.so" || true
