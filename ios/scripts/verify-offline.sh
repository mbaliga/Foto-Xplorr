#!/usr/bin/env bash
# Offline-identity gate for the iOS port (docs/handoff/IOS-PORT.md, plan Q8: "Windows, macOS and iOS
# have no OS-level proof of offline, so their gate is the classpath plus source checks alone").
# iOS has no INTERNET permission to delete, so this is the only proof: no networking API in any
# Swift source, no URL literal, no ATS/network keys in the plist spec, no network library in the
# Kotlin facade. Runs on any host; this is a negative grep, not a proof of absence at runtime.
set -euo pipefail
cd "$(dirname "$0")/.."   # ios/
fail=0
note() { echo "verify-offline: $*" >&2; fail=1; }

# 1. Swift sources: no networking frameworks / APIs, no web views, no URL literals.
SWIFT_FILES=$(find Fotoz FotozKit/Sources FotozTests -name '*.swift')
if grep -nE '^\s*(@_exported\s+)?import\s+(Network|WebKit|SafariServices|CFNetwork|MultipeerConnectivity|CloudKit|FirebaseCore|Sentry)\b' $SWIFT_FILES; then note "networking/analytics import found"; fi
if grep -nE '\b(URLSession|URLRequest|NWConnection|NWListener|CFSocket|CFHTTP|NSURLConnection|WKWebView|SFSafariViewController)\b' $SWIFT_FILES; then note "networking API found"; fi
# URL literals: only the Settings deep-link constant is allowed.
if grep -nE 'https?://' $SWIFT_FILES | grep -v 'UIApplication.openSettingsURLString'; then note "URL literal found in Swift"; fi

# 2. Plist spec: no ATS exceptions, no background-network modes.
if grep -nE 'NSAppTransportSecurity|NSAllowsArbitraryLoads|UIBackgroundModes|NSLocalNetworkUsageDescription|NSBonjourServices' project.yml Fotoz/*.xcprivacy 2>/dev/null; then note "network-related plist key found"; fi

# 3. Kotlin facade: no network library on its classpath (checked in the build script text).
if grep -nEi 'ktor|okhttp|retrofit|firebase|sentry|analytics|crashlytics' core/build.gradle.kts; then note "network/analytics dependency in ios/core"; fi

# 4. Every PhotoKit request must keep iCloud off.
if grep -n 'isNetworkAccessAllowed *= *true' $SWIFT_FILES; then note "isNetworkAccessAllowed = true found"; fi
for f in Fotoz/GridView.swift Fotoz/ViewerView.swift; do
  grep -q 'isNetworkAccessAllowed = false' "$f" || note "$f makes PhotoKit requests without isNetworkAccessAllowed = false"
done

[ "$fail" = 0 ] && echo "verify-offline OK: no networking API, URL literal, ATS key or network dependency in the iOS sources"
exit $fail
