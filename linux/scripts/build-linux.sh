#!/usr/bin/env bash
# Local/CI recipe for the Linux desktop build: core -> Qt shell -> tests -> deb + tarball.
#   linux/scripts/build-linux.sh [5|6]      (default 6; both majors are tested in CI, 6 is what ships)
# Needs: JDK 17 + the Android SDK path Gradle insists on (see ut.yml), Qt dev packages, cmake, ninja.
set -euo pipefail
cd "$(dirname "$0")/../.."
qt="${1:-6}"
./ut/scripts/build-core.sh amd64
core="$PWD/ut/app/prebuilt/amd64/libfotozcore.so"
b="build/linux-qt$qt"
cmake -S ut/app -B "$b" -G Ninja -DFOTOZ_QT_MAJOR="$qt" -DFOTOZ_DESKTOP=ON -DFOTOZ_BUILD_TESTS=ON \
      -DFOTOZ_CORE_LIB="$core" -DCMAKE_BUILD_TYPE=Release
cmake --build "$b"
QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software ctest --test-dir "$b" --output-on-failure
(cd "$b" && cpack -G "DEB;TGZ")
ls -l "$b"/*.deb "$b"/*.tar.gz
