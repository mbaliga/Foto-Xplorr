#!/usr/bin/env bash
# Print the UDID of the first available iPhone simulator (device names change between runner images,
# so the workflow never hard-codes one).
set -euo pipefail
udid=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
d = json.load(sys.stdin)["devices"]
for runtime in sorted(d, reverse=True):
    if "iOS" not in runtime: continue
    for dev in d[runtime]:
        if dev["name"].startswith("iPhone") and dev.get("isAvailable", True):
            print(dev["udid"]); sys.exit(0)
sys.exit(1)')
echo "$udid"
