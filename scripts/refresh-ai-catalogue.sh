#!/usr/bin/env bash
# Refreshes the BUNDLED fallback snapshot of the shared AI catalogue (the offline/first-run copy).
#
# Usage: scripts/refresh-ai-catalogue.sh /path/to/nooz-checkout
# Copies <nooz>/ai-catalogue/{models,free-tiers}.json into app/src/main/assets/ai-catalogue/.
# The app itself never fetches these on its own: a fresh copy comes only from an explicit
# "Update catalogue" tap in the Connect build (see services/catalogue/CatalogueUpdater).
# Review the diff and re-run the services tests before committing: the parser test reads these files.
set -euo pipefail
src="${1:?usage: $0 /path/to/nooz-checkout}/ai-catalogue"
dst="$(dirname "$0")/../app/src/main/assets/ai-catalogue"
for f in models.json free-tiers.json; do
  [ -f "$src/$f" ] || { echo "missing $src/$f" >&2; exit 1; }
done
mkdir -p "$dst"
cp "$src/models.json" "$src/free-tiers.json" "$dst/"
echo "Copied. models.json lastUpdated: $(grep -m1 '"lastUpdated"' "$dst/models.json")"
