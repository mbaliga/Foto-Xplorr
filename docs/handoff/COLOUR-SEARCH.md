# Colour palettes: persisted, searchable, tappable

**Owner's ask:** "I want each photo's details section to also show a colour palette as well, of the
colours in that image. I want the user to be able to search by colour palette as well."

**Before this work:** the details room already drew a segmented palette bar, but computed it on
demand by decoding the photo every time the room opened, persisted nothing, and the swatches did
nothing when tapped. There was no way to search by colour.

**After:** every recognised photo's palette is stored alongside its faces/labels/text; `colour:`
is a search field (hex or a colour word, perceptual matching); and `PaletteSwatchRow` is a drop-in
tappable version of the bar whose tap opens `colour:#rrggbb`. The viewer wiring is left for the
orchestrator (the viewer is being restructured in parallel) -- the exact snippet is in §6.

Branch: `claude/fotoz-p1-foundation` worktree, on top of `53f7939`. No new Gradle dependency, so
`docs/DEPENDENCIES.md` is unchanged; no ADR, because nothing here changes a module boundary or a
persistence choice already recorded (ADR-010/011) -- the palette lives in the app-side
`RecognitionStore` the live indexer already writes, not in the Room `FotozDatabase`.

---

## 1. What was built

| Piece | Where | What |
|---|---|---|
| Persisted palette | `app/.../recognition/RecognitionModels.kt` (`AssetRecognition.palette`), `RecognitionStore.kt` (table `asset_palette`, `DATABASE_VERSION` 4 → 5) | Up to five `PaletteSwatch(argb, proportion)` per photo, widest first. |
| Palette in the detector pass | `app/.../recognition/RecognitionIndexer.kt` (`analyse`), `app/.../palette/PaletteSampling.kt` | From the 640px analysis bitmap the detectors already saw, downscaled to a 100px long edge and quantised by the existing `PaletteExtractor`. |
| Backfill for pre-v5 rows | `RecognitionIndexer.backfillPalettes`, `RecognitionStore.pendingPaletteOnly` / `upsertPalettes` | A palette-only pass: one 100px decode per legacy photo, no detector. Runs after the detector pass on every `index()`. |
| Published to readers | `app/.../recognition/FaceClustering.kt` (`RecognitionIndex.palettesByMedia`, `paletteFor(id)`, `searchPalettesByMedia`, `searchPaletteFor(id)`) | The index is recognition's only published view; the viewer reads the palette from it instead of decoding. |
| Colour search | `core/search/.../ColourSearch.kt` (new), `SearchQuery.kt` (`SearchField.COLOUR`), `SearchDocument.kt` (`palette: PaletteSample`), `SearchSuggestions.kt` | `colour:` / `color:` / `hue:` / `palette:` with `#rrggbb`, `#rgb` or a colour word; CIE76 ΔE in Lab; negation and `or` as for every other field; chip alternatives offer the nearest colour words; a bare colour word offers "Only photos coloured red". |
| Gallery hookup | `app/.../gallery/GalleryScreen.kt` (the `SearchDocument(` construction only) | `palette = recognition.searchPaletteFor(id)` -- one map lookup per asset, nothing converted on the main thread. |
| Tappable swatches | `app/.../palette/PaletteSwatchRow.kt` (new) | Stateless composable: the same 14dp strip + hex line, each segment a 48dp-tall button with content description "Search photos coloured #RRGGBB", reporting the segment's ARGB. |
| Chip | `app/.../search/SearchChips.kt` | A `colour:` chip shows a 10dp dot of the colour beside its label (beside, not instead of -- TRAPS #14). |

## 2. Data layout

### `asset_palette` (store v5)

```sql
CREATE TABLE IF NOT EXISTS asset_palette (
    media_id     INTEGER NOT NULL,
    swatch_index INTEGER NOT NULL,   -- 0 = widest share
    argb         INTEGER NOT NULL,   -- packed opaque colour, as Bitmap.getPixels returns it
    proportion   REAL    NOT NULL,   -- share of the sampled image, 0..1
    PRIMARY KEY (media_id, swatch_index)
)
```

- **Written** by `RecognitionOpenHelper.upsert` (every full recognition row, as part of the same
  transaction as its faces/text) and by `upsertPalettes` (the palette-only pass). Both replace
  the photo's palette rows wholesale.
- **"Sampled, nothing visible" marker.** A photo whose sample had no visible pixels (a fully
  transparent PNG) gets ONE row with `proportion = 0`. Without it, "no rows" would be
  indistinguishable from "never sampled" and every such photo would be decoded again on every
  pass, forever. `pendingPaletteOnly` therefore means exactly "recognition row at the current
  revision AND no row at all in `asset_palette`".
- **Reading never throws.** `readPalettes` drops any row whose `proportion` is NULL, NaN or above
  1.0001, and returns an EMPTY palette for that photo (never a half palette). Rows with
  `proportion <= 0` are skipped silently -- that is the marker row, and also what SQLite hands
  back for non-numeric text in a REAL column, which is the right answer for both. `argb` is
  forced opaque on read. Pinned by `RecognitionStoreTest` "malformed palette rows…".
- **Removed** with the rest of the photo's rows in `removeMissing` and `clear`.
- **Migration** (`onUpgrade`): `oldVersion < 3` still drops-and-recreates (and now drops the
  palette table too); any v3/v4 start runs `CREATE TABLE IF NOT EXISTS` for the failure table and
  the palette table and keeps every existing row. Pinned by `RecognitionStoreMigrationTest`
  (v3 → 5 and v4 → 5 fixtures built with raw SQL).

### In memory

- `AssetRecognition.palette: List<PaletteSwatch>` -- reuses the existing `PaletteSwatch` from
  `palette/PaletteExtractor.kt`. That file has no Android import (its own KDoc makes that a stated
  property so a JVM test can pin the quantiser), so reusing it keeps the model plain; a second
  near-identical type would have been duplication for its own sake.
- `RecognitionIndex.palettesByMedia: Map<MediaId, List<PaletteSwatch>>` (sparse; for display) and
  `RecognitionIndex.searchPalettesByMedia: Map<MediaId, PaletteSample>` (sparse; for search).
  Two fields for one fact, on purpose: the search shape has every colour's Lab coordinates
  precomputed in flat arrays. It is derived eagerly in `RecognitionIndex.from`, which runs on the
  IO thread in `RecognitionStore.reload`, so the main thread never converts a colour.
- `core/search` `PaletteSample` -- immutable, `IntArray argb` + `FloatArray proportions` +
  `FloatArray lab` (3 per colour), content equality so `SearchDocument` stays a plain data class.
  `SearchDocument.palette` is the LAST parameter, defaulted to `PaletteSample.EMPTY`, so every
  other constructor call compiles unchanged.

## 3. Matching: ΔE thresholds and why

Distance is **CIE76** (Euclidean in CIE L\*a\*b\*, D65, sRGB → XYZ → Lab), compared as squared
distance so the hot loop has no `sqrt`. sRGB companding is undone through a 256-entry table (the
input is always an 8-bit channel), so a conversion is three table reads, nine multiplies and three
`cbrt`. CIE94/CIEDE2000 were considered and not taken: they fix CIE76's over-sensitivity in
saturated regions at several more multiplies per comparison, and the thresholds below are loose
enough that the bias does not change what a person sees match.

| Target | ΔE | Why |
|---|---|---|
| `#rrggbb` / `#rgb` | **14** (`HEX_MAX_DELTA_E`) | A hex is a precise ask, almost always "more like THIS swatch" from a tap. 14 is about where two colours side by side stop reading as shades of one colour; a quantiser that averaged a slightly different sample of the same scene lands inside, a different colour stays out. Edge pinned: `#808080` vs `#a3a3a3` = 13.4 (in), vs `#a7a7a7` = 14.9 (out). |
| Chromatic word (`red`, `teal`, …) | **25** (`NAME_MAX_DELTA_E`) | A word covers a region. Each word has 3–5 anchor colours spread across it; 25 around each anchor fills the gaps between anchors without reaching the neighbouring word's anchors. |
| Achromatic word (`black`, `white`, `grey`/`gray`, `silver`) | **14** (`ACHROMATIC_NAME_MAX_DELTA_E`) | These differ on the L axis alone, where 25 is far too wide: at 25, `silver` swallows pure white (ΔE 18) and `grey` takes near-black `#222222` (ΔE 19.5). At 14 each keeps its band and `black` still catches shadow greys up to about `#525252`. |
| Minimum share | **0.08** (`MIN_PROPORTION`) | A 3% sliver of red in a green field is faithfully reported by the quantiser and is not what `colour:red` means. 8% is below "an accent" and above "noise". |

Measured separations behind the anchor choices (nearest anchor, CIE76):

| Pair | ΔE | Result |
|---|---|---|
| `red` anchors vs `#d32f2f` (Material red) | 5.8 | matches red |
| `red` anchors vs `#8b0000` (dark red) | 12.6 | matches red |
| `red` anchors vs coral `#ff7f50` | 25.3 | just outside -- coral is not red |
| `blue` anchors vs navy `#000080` | 36.2 | blue does not take navy |
| `navy` anchors vs `#0000ff` | 56.9 | navy does not take blue |
| `navy` anchors vs midnight blue `#191970` | 4.8 | matches navy |
| `tan` anchors vs skin tone `#e0ac69` | 6.7 | matches tan (and not orange: 32.9) |
| `pink` anchors vs `#ff00ff` | 60.8 | pink does not take magenta |
| `yellow` anchors vs `#ffd700` | 11.6 | gold also reads as yellow (acceptable overlap) |
| `white` anchors vs `#dddddd` | 9.1 | matches white |
| `silver` anchors vs `#ffffff` | 18.0 | silver does NOT take white at 14 |
| `grey` anchors vs `#222222` | 19.5 | grey does NOT take near-black at 14 |
| `black` farthest anchor vs `#525252` / `#585858` | 13.6 / 16.2 | the black edge, pinned in `SearchQueryTest` |

The word table (`ColourNames`): red, orange, yellow, gold, green, teal, cyan, blue, navy, purple,
violet, magenta, pink, brown, tan, beige, black, grey (alias gray), silver, white -- in that
(spectrum, then neutrals) order, which is the order suggestions use. Anchors are listed in
`ColourSearch.kt`. One lesson from the tests worth keeping: the leafy `green` anchors alone sat
ΔE ~60 from pure lime `#00ff00`, so `colour:green` missed every chroma-key backdrop and highlighter
stroke; `#32cd32` and `#00ff00` are anchors of their own for that reason. The other five
saturated primaries/secondaries (`#ff0000`, `#0000ff`, `#ffff00`, `#00ffff`, `#ff00ff`) were
already anchors of their words.

Semantics that match the rest of the language: an unknown value (`colour:nosuch`) matches nothing
and its negation matches everything, exactly like `label:nosuch`; a photo with no palette yet
matches no colour term; `-colour:red`, `not colour:red`, `colour:red or colour:blue`, and
`colour:red colour:blue` (AND) all behave as for any field.

**Hot-loop cost.** `matchesField` resolves the term's value through a bounded copy-on-write cache
(`ColourSearch.targetFor`), so the name lookup / hex parse / Lab conversion of the *term* happens
once per distinct value, not once per photo. Per photo the work is: up to 5 swatches × up to 5
anchors × (3 subtractions, 3 multiplies, 2 adds, 1 compare) over flat float arrays -- no
allocation, no `sqrt`, no `pow`.

## 4. Backfill behaviour and cost

- **Trigger:** every `RecognitionIndexer.index(assets)` call, AFTER the detector pass (new photos'
  faces and labels are worth more than old photos' colours on a device that has both). On a fresh
  install the backfill set is always empty because `analyse()` samples as it goes.
- **Set:** `RecognitionStore.pendingPaletteOnly(images)` -- images (not video, not trashed) whose
  `source_revision` matches the current `recognitionRevision()` and that have NO row in
  `asset_palette`, minus those with ≥ 3 recorded failures at that revision (P0-12's bounded
  retries, reused as-is).
- **Per photo:** `decodeUpright(uri, maxLongEdge = 100, maxPixels = 10 000)` → `getPixels` on at most
  10k pixels → `PaletteExtractor.extract`. No ML Kit client is created; `RecognitionIndexerPaletteTest`
  proves it by running `index()` under Robolectric (where creating an ML Kit client throws) and
  asserting success and untouched faces/labels.
- **Outcomes:** palette → stored; decoded-but-empty → marker row, never asked again; undecodable →
  `recordFailure`, retried on later passes up to 3 times, then dropped from the set.
- **Batching:** 24 photos per transaction (the detector pass's `BATCH_SIZE`); progress published at
  most every 250 ms through the same `RecognitionProgress` the destinations already show
  ("Looking through your photos on this device… N of M"); one unthrottled `reload()` at the end.
- **Cost, estimated (not measured -- no device in this session):** a 100px-long-edge decode of a
  12 MP JPEG is dominated by reading the file and the codec's 1/8 DCT scaling, roughly 5–15 ms on
  a mid-range phone; quantising ≤ 10k pixels is ~1 ms. Call it 10–20 ms per photo on
  `Dispatchers.Default` (the decode itself hops to IO). A 20k-photo library already indexed before
  v5 therefore costs 3–7 minutes of background work, once, spread across however many `index()`
  passes it takes, and nothing thereafter. The detector pass for the same photo is one to two
  orders of magnitude more (640px decode + three detectors), which is the whole reason this is a
  separate pass.
- **Per-asset cost added to the detector pass itself:** `Bitmap.createScaledBitmap` 640 → 100
  (sub-millisecond) plus the same ~1 ms quantise; negligible against the detectors.
- **Main thread:** nothing. The viewer reads `recognition.paletteFor(id)` (a map lookup); search
  reads `recognition.searchPaletteFor(id)` (a map lookup) and compares precomputed floats.

## 5. Tests added

- `core/search/.../SearchQueryTest.kt` -- 12 new cases: field + aliases; hex parsing (long/short/
  bare/case/invalid); hex match + near shade + ΔE edge either side of 14, with the arithmetic
  pinned; colour words as regions and neighbour separation (blue/navy, grey/gray); achromatic
  bands (white/silver/grey/black edge at 13.6 / 16.2); proportion floor; negation, `not`, `or`,
  AND; unknown value and unsampled photo; `colourQuery` exactness, alpha-independence and round
  trip; chip alternatives (nearest words) and the bare-word "Only photos coloured red"; rewrite
  round trip.
- `app/.../recognition/RecognitionStoreMigrationTest.kt` -- v3 → 5 (existing case, now also checks
  the palette table) and v4 → 5 (rows, text and failure bookkeeping survive; palette table exists,
  empty, version 5; the legacy row is pending for the palette pass and NOT for the detector pass;
  a palette written for it settles it).
- `app/.../recognition/RecognitionStoreTest.kt` -- palette round trip in both shapes; malformed
  rows (share 7.5, text) read as empty without a crash and still count as sampled;
  `pendingPaletteOnly` membership across legacy/unknown/sampled/blank/stale/video;
  `upsertPalettes` touches only palettes and honours the reload throttle; three palette failures
  stop retries; `removeMissing`/`clear` take palette rows along.
- `app/.../recognition/RecognitionIndexerPaletteTest.kt` (new, Robolectric NATIVE) -- the
  palette-only pass over a real PNG via `index()` with no detector; bounded retries on an
  undecodable file; `PaletteSampling.fromBitmap` downsamples a 640×480 and keeps proportions,
  never recycles the caller's bitmap.
- `app/.../palette/PaletteSwatchRowTest.kt` (new, Robolectric NATIVE + Compose test rule) -- a tap
  reports the segment's own ARGB; a 1% sliver is still tappable; hex labels present; empty list
  renders nothing and does not crash.

## 6. Wiring into the viewer (for the orchestrator, after the merge)

Read before writing this: `FotoXplorrActivity` already owns the plumbing a swatch tap needs.
`onSearchLibrary = { text -> pendingSearch = text; selectedAssetId = null; setViewerAssets(emptyList()); slideshowActive = false }`
(around line 1015) closes the viewer and seeds `GalleryUiState.pendingSearch`, which
`GalleryScreen`'s `LaunchedEffect(state.pendingSearch)` (around line 703) turns into
`query = seed; searchVisible = true` and acknowledges via `onPendingSearchConsumed`. So a swatch
tap is just `onSearchLibrary(ColourSearch.colourQuery(argb))` -- no new state, no new callback
shape, and the room's existing "Search inside this photo" pill and the swatches land on the same
grid the same way.

Three edits, all in files the viewer agent owns:

**(a) `viewer/PhotoDetailRoom.kt`** -- add a parameter and replace `PaletteRow`'s body.

```kotlin
// imports
import com.fotoxplorr.app.palette.PaletteSwatch
import com.fotoxplorr.app.palette.PaletteSwatchRow
import com.fotoxplorr.core.search.ColourSearch

@Composable
fun PhotoDetailRoom(
    …,
    recognizedText: String = "",
    onSearchLibrary: ((String) -> Unit)? = null,
    /**
     * This photo's stored palette, `recognition.paletteFor(asset.id)` off the index. Defaulted to
     * empty so an un-updated caller still compiles; empty falls back to the on-demand decode.
     */
    palette: List<PaletteSwatch> = emptyList(),
    …
) {
    …
        if (!asset.isVideo) PaletteRow(asset = asset, indexed = palette, onSearchLibrary = onSearchLibrary)
    …
}

@Composable
private fun PaletteRow(asset: MediaAsset, indexed: List<PaletteSwatch>, onSearchLibrary: ((String) -> Unit)?) {
    val context = LocalContext.current
    // Stored palette first; decode only for a photo the indexer has not reached yet. Keyed on
    // asset.id AND the indexed list so an index publish while the room is open updates it.
    var swatches by remember(asset.id, indexed) { mutableStateOf(indexed) }
    LaunchedEffect(asset.id, asset.contentUriString, indexed) {
        if (indexed.isEmpty()) swatches = readImagePalette(context, asset)
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = "Palette",
            color = MUTED_TEXT,
            style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp),
            modifier = Modifier.weight(0.36f).padding(top = 2.dp),
        )
        if (swatches.isEmpty()) {
            Text("—", color = MUTED_TEXT, style = TextStyle(fontFamily = HyleGrotesk, fontSize = 13.sp), modifier = Modifier.weight(0.64f))
        } else {
            PaletteSwatchRow(
                swatches = swatches,
                // Same path as the Search pill: the activity closes the viewer and seeds the grid's
                // search field. A null onSearchLibrary (no catalogue actions) leaves the bar as it
                // was -- visible, not tappable to anywhere.
                onSwatchTap = { argb -> onSearchLibrary?.invoke(ColourSearch.colourQuery(argb)) },
                modifier = Modifier.weight(0.64f),
                labelColor = SECONDARY_TEXT,
            )
        }
    }
}
```

Delete the old `MIN_SEGMENT_WEIGHT` constant and the old strip/hex code inside `PaletteRow`;
`readImagePalette`, `PALETTE_SAMPLE_DIMENSION` and `PALETTE_DECODE_LIMITS` can either stay as the
fallback or be replaced by `PaletteSampling.read(context, asset).orEmpty()` (same 100px sample, one
definition). Note the 0.64-weight value column is ~205dp on a 360dp phone, so the smallest
segment (8% minimum weight) is ~16dp wide × 48dp tall; if the viewer restructure gives the palette
a full-width block instead of the label/value split, the same composable gets ~26dp segments for
free.

**(b) `viewer/ViewerScreen.kt`** -- thread it through.

```kotlin
    onSearchLibrary: ((String) -> Unit)? = null,
    /** This photo's stored palette, for the details room. See [PhotoDetailRoom]. */
    palette: List<PaletteSwatch> = emptyList(),
    …
            PhotoDetailRoom(
                …,
                onSearchLibrary = onSearchLibrary,
                palette = palette,
```

**(c) `FotoXplorrActivity.kt`** -- next to `liveTextBlocks` (around line 980):

```kotlin
            liveTextBlocks = recognition.textByMedia[activeAsset.id].orEmpty(),
            // Stored colours, same index, same per-asset lookup as the text above.
            palette = recognition.paletteFor(activeAsset.id),
```

Nothing else: `onSearchLibrary` is already passed and already does the right thing with any query
string.

## 7. Left open / honest limits

- **Not wired into the viewer** (by instruction -- another agent owns `viewer/`). Until §6 lands,
  the room still decodes on demand and swatches are not tappable; search and persistence work
  regardless.
- **No device measurement.** The per-photo costs in §4 are estimates from the decode path's shape;
  the session has no device. Worth one line on the Phase device checklist: "colour backfill on a
  20k library finishes in the background without jank; `colour:red` keystrokes feel instant".
- **Hot loop still allocates one string per term per photo** -- `matchesField` lowercases the term
  value before dispatch, as it did for every field before this. Not new, not in the colour code,
  but it is the remaining per-photo allocation on the search path if anyone profiles it.
- **Colour words are English only**, like the rest of the query language (`last month`, `august`).
- **Lab per swatch is recomputed on every `reload()`** (≤ 5 conversions per photo, on IO). Fine at
  100k photos (~0.5 M conversions, tens of ms); mentioned so nobody is surprised by it in a profile.
- `CIE76` not `CIEDE2000` -- see §3. If users find `colour:blue` too generous in the saturated
  corner, the first thing to try is CIE94's chroma weighting, not a tighter threshold.
