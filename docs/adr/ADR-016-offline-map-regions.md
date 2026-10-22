# ADR-016 — Offline map regions: source, limits and shape

**Status:** accepted for the Connect build; nothing in it has run on a device (see "What is not known").
**Date:** 22 Oct 2026
**Scope of this record:** where the downloaded map comes from, the limits that apply to it, the numbers the code uses and which of them are guesses, and the rules the UI keeps. How the provider's request limit is applied to the map library is ADR-015.

## Context

Owner feedback #23: *"Map should be like a one click download per region from a source like OSM."* The Connect build's Places map already draws the OpenFreeMap "Liberty" style with MapLibre; the Offline build has no network code at all.

## Decision 1 — The source is OpenFreeMap, through MapLibre's own offline manager

The region is the Liberty style that the Places map already shows (`https://tiles.openfreemap.org/styles/liberty`), downloaded by MapLibre's `OfflineManager`. No new Gradle dependency.

| Candidate | Verdict | Why |
|---|---|---|
| OpenStreetMap's own tile servers (`tile.openstreetmap.org`) | **Forbidden. Never use for this.** | The tile policy bans pre-emptive fetching of tiles a person is not actively viewing, bans offline use and bans offering prefetch features. |
| OpenFreeMap | **Used** | The only candidate whose written terms allow an app to download for offline use. |
| Protomaps (PMTiles) | Rejected | No regional extracts are hosted, the planet file is about 120 GB, hotlinking is discouraged; its schema differs from OpenMapTiles, so the style and 3D buildings would not carry over. |
| MapTiler | Rejected | Needs an account and a key; its free tier forbids redistribution and offline caching could not be confirmed. |
| Geofabrik `.osm.pbf` with tiles made on the device | Rejected | Adds a tile-generation engine and its own schema, and is too heavy for a phone. |

**OpenFreeMap's terms as read on 2026-10-22** (a paraphrase; the page limits quotation): automated collection is banned except for offline downloads; an offline download may be at most about 3,000 tiles or 50 MB; at most 4 requests may be in flight at once; the app must send its own User-Agent; a larger allowance needs an email to info@openfreemap.org. The service has no SLA and may be discontinued. **These terms can change: re-read them before every release.** A bigger allowance is a permission to ask for, never a limit to code around.

## Decision 2 — Limits (all in `OfflineMapLimits`, one object)

| Constant | Value | Status |
|---|---|---|
| `TERMS_MAX_TILES` / `TERMS_MAX_BYTES` / `TERMS_MAX_PARALLEL_REQUESTS` | 3,000 / 50 MB / 4 | What the terms say. Kept so a test proves our caps sit below them. |
| `MAX_TILES`, `MAX_BYTES` (per region) | **2,500 tiles, 45 MB** | Conservative against the terms; the byte side is an estimate. |
| `MAX_REGIONS`, `MAX_TOTAL_BYTES` | **6 areas, 300 MB** | Product choice. A seventh area, or one that would pass 300 MB, is refused; **nothing is ever deleted to make room** (TRAPS #64). The person is told to delete one. |
| `MIN_ZOOM`, `MAX_ZOOM` | 0, 14 | The source stops at zoom 14. |
| `MIN_USEFUL_MAX_ZOOM` | 12 | **Product choice, to confirm.** An area that only fits at top zoom 11 or lower is called too large, not offered as a blur. |
| `RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES`, the per-zoom tile-size table | 10 MB; 40 to 160 KB per tile | **Guesses.** From about a dozen sampled tiles in three cities (urban-skewed) and no measurement of style, sprite and glyph overhead. **Estimate, replace after the device spike.** Until then some rural areas are refused that would fit. |
| `ViewArea.TILE_PX` | 512 | From memory of the map library's zoom scale; unverified. |

Tile counts are exact arithmetic (`TileMath`) but the map library's own count may differ at tile edges; bytes are estimates. The library's progress figures (all resources, tiles included) are what `DownloadGuard` compares to the caps while a download runs, and it stops a run that passes 45 MB or 2,500 tiles. Whether the provider counts the same bytes is unknown.

## Decision 3 — City scale only

Two ways to pick an area: **This view** (the area the Places map shows, via a pill on the embedded map) and **Places in your photos** (boxes grown around clusters of the person's own browsable photos, each one that fits at full detail). No country presets. The owner's "one click per region" therefore means a city-sized region unless the provider grants more.

## Decision 4 — Rules the UI keeps

1. A download starts only from an explicit tap on Download, Resume, or the mobile-data question's Download (TRAPS #61, #71). Opening the pane, scrolling it, changing the map view and relaunching never start one. After a relaunch an unfinished area is listed as paused ("tap resume"), reconciled from the library's own list.
2. Download and Resume are disabled, with the reason beside them, unless the engine reports that the 4-request cap can be applied (`throttleInstalled`). A build where it cannot be applied offers nothing instead of breaking the terms (ADR-015).
3. No network code in `src/main` or `src/offline`. The Offline build shows an honest "Offline maps need the Connect build." from a same-name stub pane. The pure logic in `src/main` contains none of the dotted tokens `verifyOfflineSourceReferences` scans for, comments included.
4. Mobile data: the platform connectivity check (only in `src/connect`, and "metered" when it cannot tell) asks first.
5. One download at a time. Cancel means pause and keeps what was fetched; only Delete, after naming the area in a confirmation, removes data.
6. Privacy line: *Downloading shows the area and your IP address to the map provider.* Attribution line: *© OpenStreetMap contributors, OpenMapTiles, OpenFreeMap*. Both are on the pane.
7. Terrain shading and the dark map are loaded separately and are not part of a saved Liberty region; the pane says so.
8. Saved areas are not part of the encrypted backup (`android:allowBackup` is false and the backup sections do not list them).

## Decision 5 — OkHttp ownership

See ADR-015: the map library declares OkHttp runtime-only, so the one wrapper that hands it a client lives in `:feature:ai-remote` (which owns OkHttp) with `compileOnly` on the map library. This was checked by compiling.

## What is not known

Everything above that talks to the map library. No line of it has run on a device:
- whether the native downloader goes through the HTTP client hook at all (if it does not, the 4-request cap is not in force and the download must not ship; ask OpenFreeMap instead);
- the real byte and tile figures, the overhead of style, sprites and glyphs, and whether `completedResourceSize` is what the provider counts;
- whether `createOfflineRegion` fetches nothing, whether a region survives a force-stop and resumes rather than restarting, whether delete gives the space back;
- whether an area over the date line can be saved (refused for now, `MapLibreRegionEngine.ANTIMERIDIAN_VERIFIED`);
- how a flat-view box from `ViewArea` compares with the library's visible region at zero tilt.

## Consequences

- Update policy (offer a re-download after 90 days; download the new area then delete the old) is not built. At the 6-area or 300 MB limit the person deletes first.
- An area cannot be renamed after it is saved (the engine has no method to rewrite a region's stored record), and its size stays the estimate; `RegionRecord.bytesMeasured` is never set yet.
- Re-read the provider's terms before every release.
