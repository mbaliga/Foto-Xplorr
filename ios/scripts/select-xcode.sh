#!/usr/bin/env bash
# Select the newest installed Xcode 26.x (OQ-38: the first iOS proof runs on an explicitly selected
# Xcode 26.x), falling back to whatever the runner defaults to, and print the choice into the log.
set -euo pipefail
pick=$(ls -d /Applications/Xcode_26*.app 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$pick" ]; then
  sudo xcode-select -s "$pick/Contents/Developer"
else
  echo "select-xcode: no Xcode_26*.app on this runner, using the default" >&2
fi
xcodebuild -version
