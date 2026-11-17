#!/usr/bin/env bash
# Offline-identity gate for the Ubuntu Touch / Linux shell (negative grep; same shape as
# ios/scripts/verify-offline.sh). Not a proof of absence at runtime: the proof on a phone is that the
# click declares no networking policy group (checked here) and Lomiri's AppArmor then denies sockets.
set -euo pipefail
cd "$(dirname "$0")/../app"
fail=0
note() { echo "ut verify-offline: $*" >&2; fail=1; }

SRC=$(find src qml tests -type f \( -name '*.cpp' -o -name '*.h' -o -name '*.qml' \))
if grep -nE 'QNetwork|QtNetwork|QTcp|QUdp|QSsl|QWebSocket|QWebEngine|QWebView|QtWebSockets|QDesktopServices::openUrl|XMLHttpRequest|WebSocket|Qt\.openUrlExternally|\bcurl\b|libcurl' $SRC; then note "networking API found in shell sources"; fi
if grep -nE 'https?://' $SRC | grep -vE 'schemas|www.w3.org'; then note "URL literal in shell sources"; fi
if grep -nE 'find_package\(.*(Network|WebEngine|WebSockets|Multimedia)' CMakeLists.txt; then note "network/media Qt module in CMake"; fi
if grep -nE '"networking"|"network"|"webview"|"audio"|"camera"|"location"|"microphone"|"push-notification' fotoz.apparmor.in; then note "networking/sensor policy group requested"; fi
# Kotlin side of the bridge: no network libs in the module that ends up in the click.
if grep -nEi 'ktor|okhttp|retrofit|firebase|sentry|analytics|crashlytics' ../bridge/build.gradle.kts; then note "network/analytics dependency in :ut:bridge"; fi
[ "$fail" = 0 ] && echo "ut verify-offline OK"
exit $fail
