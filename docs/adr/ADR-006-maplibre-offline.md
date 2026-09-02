# ADR-006 — MapLibre and the offline flavor (FX-013)

**Status:** interim measure shipped; end state is an **owner decision** — see below.
**Date:** 10 August 2026

## Context

Recon (FX-000 #2) confirmed the photo map is fully remote: the style comes from
`https://tiles.openfreemap.org/styles/liberty`, hillshade DEM tiles from
`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png`, and vector
tiles/sprites/glyphs from the OpenFreeMap host — fetched *while the user pans*. Nothing
is bundled. MapLibre also carries its own HTTP stack, so its mere presence on the offline
classpath would fail the WP1 runtime-classpath gate and quietly falsify the "no network
capability" claim the offline flavor exists to make.

The rest of Places is unaffected: the compass exploration, coordinate map and elevation
views are custom local rendering over embedded EXIF metadata, and the viewer's place
plate draws its graticule locally by design.

## Options

1. **Bundle a minimal offline style + tile pack.** Real offline maps. Cost: tile packs
   for any useful coverage are hundreds of MB to GB, someone must curate coverage, and
   the app's README currently *promises* "no map-tile download" for the offline
   coordinate map — bundling street tiles changes the product's privacy story and size
   dramatically. Also licensing review for redistributing tiles.
2. **Ship the street map only in `connect`.** The map remains exactly as it is, in the
   build whose identity includes network use. Offline users keep every local view.
3. **Show the map entry in offline with an explicit "needs the Connect build" state.**
   As (2), but the destination stays visible and explains itself instead of vanishing —
   no silent feature disappearance between the two builds.

## Interim measure shipped in WP1 (reversible)

Options 2+3 combined, because the WP1 exit gate requires the offline gates green and a
build that fails its own gate is not shippable: MapLibre moved to `connectImplementation`,
`RichPhotoMapScreen` moved to `src/connect`, and `src/offline` renders an honest
explanation in its place (`PhotoMapEntry.kt`) that names what still works locally. This
is the least destructive green state and every part of it is reversible.

**This ADR does not close the question.** The owner picks the end state:

- **Recommendation: keep the interim (option 3).** It matches the app's documented
  privacy model ("the offline map is a coordinate visualisation … not downloaded map
  packs"), costs nothing, and leaves option 1 open as a deliberate future feature with
  its own storage/licensing work (the blueprint's post-v2 list already names offline map
  packs as future work).
- Choosing option 1 instead means: a tile source and licence, a coverage/size decision,
  a download-manager UX — a work package of its own, not a flag flip.

## Consequences

- The offline APK sheds MapLibre's native libraries (smaller, and one less C++ attack
  surface in the build whose users chose it for privacy).
- `PhotoMapExperience` is the seam: same fully-qualified composable in both source sets,
  so `PlacesScreen` knows nothing about flavors.
- The ML Kit recognition stack needed no decision: it is bundled and offline-safe
  (RECON #3). The similarity embedder's *download* moved behind `RemoteAiBridge`
  (FX-012); offline users side-load a `.tflite` via SAF, which was always supported.
