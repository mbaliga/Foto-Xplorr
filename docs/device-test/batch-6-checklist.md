# Batch 6 device test checklist

Every device-only claim from the sixth owner-feedback batch: the **Experimental** section (items
**#20** the aquarium and **#22** the wall mock-up) and **offline map areas** (**#23**), in the same shape
as `batch-3-checklist.md` and `batch-5-checklist.md`. See `docs/handoff/MASTER-PROGRESS.md` (Batch 6).
This sandbox has no device or emulator, so **none of these is claimed as verified**: Robolectric proved
composition, state and file effects, and Roborazzi runs in record mode, so no screenshot test proves how
anything looks. The boxes are for what those cannot see. Check each on a real phone, on **both flavours**
where a line says so.

Suggested devices: a current phone on Android 15 or 16, one on Android 13 or 14, one **low-end phone**
(for the frame-rate and memory lines), a tablet or a wide-pinned rail (the 260 dp rail), and, if you can get
one, Android 8.x (minSdk 26; see the decode note in section 0). Printing needs a printer, or "Save as PDF"
plus real paper for the real-size lines. Prepare a library with some photos of a room wall (one with a
focal-length tag, one without such as a screenshot), a 48 MP photo, a Display-P3 photo, a 12 MP photo, a
3 MP photo, a photo in a protected folder, a photo marked sensitive, and photos with GPS.

## 0. Known before you start

- [ ] Android 8.0 / 8.1: `decodeUpright` returns null on API 26 and 27 (PRODUCT-AND-ARCHITECTURE, Known
      limits), so the wall's photos and the print file cannot load there. Expect "This picture can't be
      shown." and treat the wall as unverified on those releases.
- [ ] Every figure in the offline-map sections is a guess until measured here; section 4 lists which
      constants to replace.

## 1. The Experimental page (host)

Sandbox coverage: `ExperimentalHostTest`, `ExperimentalScreenTest`, `ShellEdgesTest`,
`ExperimentalRootPathInkTest`.

- [ ] **Rail word.** The word between Map and Protected reads "Experimental" at the default and at a large
      font; it is the longest word on the rail. It does NOT fit at the rail's 44 sp: `DestinationWordRail`
      measures it and shrinks that one row to fit (`railFontSpFor`, 2 percent margin, floor 28 sp), so on
      the **260 dp pinned rail** (tablet or wide window) and in the 288 dp pull-out room it shows whole at a
      smaller size than the other words, which stay at 44 sp. Only below the floor (a large font scale) is it
      ellipsised rather than overlapping its neighbours. Selecting it opens the hub; the Map disc and Map
      word still fall back to Photos from it. Robolectric measured the real font; no device has seen it.
- [ ] The hub lists **Make** (Draw an aquarium, See it on your wall), then **Explore** (Organise by
      similarity, 3D photo wall, Gallery preview, Compass view), then **Setup** (AI and provider keys last),
      then the privacy sentence. The header is "Optional extras the gallery works without." and the privacy
      sentence reads, in the Offline build, "Nothing here uploads your photos, and printing uses the printer
      you choose." (the Connect build's is one longer sentence naming the model download and AI services).
      Each is ONE sentence, and the longer Connect one fits a 360 dp phone without wrapping awkwardly.
      The hub title, "Privacy boundaries" and the group titles are announced as headings.
- [ ] Discover > Experimental card (Science icon, no count) opens the same page; it is announced once as a
      button, with no "long press" action and no stray number; Back returns to where it went before (there
      is no "Back to Discover" row any more).
- [ ] Back: open Organise by similarity, then AI and provider keys (neither had a Back handler) and press
      the system Back and the Back gesture: each returns to the hub. Compass view, the 3D wall and Gallery
      preview still step back inside themselves first. Back on the hub follows the destination's own order.
- [ ] Back with a left or top room opened while an experiment is open: the room closes first, then the
      experiment. `RoomFirstBackHandler` re-registers the gallery's Back handler one frame after a room
      opens so it is asked before the host's close and any inner step; before it, Back closed the
      experiment under a room that stayed open. Only a Robolectric stand-in handler has run this: the real
      shell's room over the real browser has no test, so this line is **unverified on a device**.
- [ ] Rotate or toggle dark mode with an experiment open: it stays open (the compass too). Then kill the
      process from recents: restorable ones (all but the compass today) reopen to the same experiment,
      the compass comes back as the hub; nothing inside an experiment is restored by the host.
- [ ] Compass view > tap a photo > Back from the viewer: you are in the compass again, not the hub.
- [ ] Open an experiment (the aquarium with a sketch in progress, the wall mock-up with a draft), open
      Settings from the rail or tap the Map word, then come back to Experimental: the same experiment and
      its draft are still there (`KeepExperimentalState`); for the compass you are still in the compass.
      Repeat with a rail scrub that rests on another word for 140 ms.
- [ ] Compass view: opening it on a never-indexed library starts the location read once (badge
      "Indexing..." then "N located"); closing and reopening does not start a second pass; Map mode in either
      flavour still starts its own.
- [ ] Light theme: the hub header, group headings, privacy paragraph and every screen opened from it are
      readable on the black pane.
- [ ] TalkBack: each card is one button announcing its title and blurb, read once and with no "long press"
      action; the hub title, "Privacy boundaries" and the group titles are announced as headings; a card
      marked unavailable (none today) announces disabled and "Not available on this device."
- [ ] The card layout on a phone looks right (cover pictures, chips, spacing); Roborazzi only recorded it.

## 2. Edge swipes over a drag-taking area (`clearOfShellEdges`)

Never tried on a device. Applies to the aquarium drawing board and the wall editor's touch layer.

- [ ] On each, with **3-button** and **gesture** navigation: a drag starting about 40 dp from the left,
      right, top and bottom edge opens the shell's room and draws or moves nothing; one starting about 70 dp
      in belongs to the content.
- [ ] The 60 dp margin plus the system-bar insets leaves enough board on a small phone (about 240 x 180 dp of
      paper on a 360 dp portrait phone; in the wall's Hang step the photo's touch area loses 120 dp of width,
      but in the Scale step the tap layer covers the whole canvas, so a reference end can be marked at the
      photo's border). Decide whether to keep the inset or ignore only the first touch inside the zone
      (`inShellEdgeZone`). Whether the shell still reads a tap in its edge zone on the Scale step is
      unverified.
- [ ] The status, navigation and gesture insets do not overlap the paper, the wall photo or the bottom bars.
- [ ] A tap at the very screen edge of the tank still feeds the fish (the tank is not inset on purpose).
- [ ] The wall's vertical panels and photo grid can lose a drag that starts in the bottom 56 dp to the actions
      room: confirm it is tolerable.

## 3. Draw an aquarium

Sandbox coverage: 191 unit and Compose tests across the pure core, the store, the saver, the rasteriser,
the screens and the frame loop.

### Speed and look

- [ ] **`drawBitmapMesh` frame rate.** 12 creatures (8 x 4 mesh, alpha paint) run at a steady 60 fps on the
      low-end phone, in portrait and landscape. If not, swap `MeshCreatureRenderer` behind the
      `CreatureRenderer` interface (per-segment `drawBitmap` with matrices, or `drawVertices` with a bitmap
      shader); the fallback is NOT written.
- [ ] The sprite edge is clean (no seam or fringe at the 2 px clear border), and the colour of fill versus
      ink edge shows no pale rim.
- [ ] The numbers feel right: fish are not huge in a portrait tank (about 0.46 tank-heights wide) or frantic
      (speed 0.085-0.135 per second, tail 1.2-1.9 Hz); they are arithmetic, not tuned.
- [ ] A head-left drawing ("Head faces left") swims head first with its tail, not its head, flapping.
- [ ] Mid-turn the sprite goes edge-on briefly: it reads as a quick turn, not a flicker. Nose-up / nose-down
      tilt has the right sign for both facings.
- [ ] Far creatures (70 to 100 percent alpha) still read on the blue gradient; a blue fish is visible on blue
      water (the palette has Blue).

### Drawing

- [ ] A finger stroke feels right (3 dp spacing, round caps, the live stroke as a polyline); the saved sprite
      matches the board. The preview fills with non-zero winding and the saved sprite with the flood fill, so
      compare a self-crossing outline.
- [ ] Auto-fill thresholds with a real finger: a wobbly hand-drawn fish outline closes and fills, a wide C
      does not.
- [ ] The board is big enough to draw a fish; a stroke starting at the paper edge is not taken by the shell
      (section 2).
- [ ] Ready shapes (Round fish, Long fish, Tadpole) can be added and recoloured, and Swim works with no
      drawing at all. With "Head faces left" ticked a ready shape comes out facing left. A shape with
      fewer than 3 strokes of room is refused with "No room for more strokes." and adds nothing.
- [ ] While Swim is saving, the board, colour, brush, shape and head chips do nothing and look disabled
      (readable on black, announced as disabled); the note reads "Making your creature." A stroke in
      progress is cancelled when the save starts.

### Tank and lifecycle

- [ ] Tank touch: a tap drops food (short, still); a hold or drag scares fish near the finger; the 250 ms
      hold feels right and the slop is not too tight for a thumb.
- [ ] Pause / Play: the control, the "Paused." label. With Settings > Developer options > Animator duration
      scale **off (0)** the tank opens paused with calm motion, and Play then keeps calm (0.25x) motion.
      Verify `Settings.Global.ANIMATOR_DURATION_SCALE` reads 0 for "Animation off", and whether
      Accessibility "Remove animations" changes it on this device (unverified).
- [ ] Lifecycle: the loop stops when the app is paused or stopped and when the lock screen covers it (CPU goes
      quiet, no animation behind the lock); coming back does not jump the fish.
- [ ] Process death: draw something, background, `adb shell am kill`, reopen: the board returns with a
      slightly smoother drawing, its colour and brush; the tank returns with the saved creatures placed at
      random. Redo, a half-drawn stroke and positions are not restored (expected).
- [ ] Storage: `filesDir/aquarium` holds a few KB per creature; nothing is left in `.tmp` after killing the
      app mid-save (the next open sweeps leftovers). A PNG that will not decode (cut off or empty) is
      deleted on the next open, with its JSON, and does not count toward the 12. Robolectric's decoder
      refuses a PNG cut after its header; Android's may return a partial or blank bitmap for one cut
      before its pixels, which would then be listed (and deletable) rather than swept: cut one at about
      100 bytes with `run-as` and see. A full tank says "The tank is full. Remove a creature
      first." and Swim is disabled.

### Access

- [ ] TalkBack: "Aquarium, N creatures" on the tank, "Drawing board, N strokes" on the board, swatches announce
      their name and selected state; Feed, Creatures and Shapes are the non-touch ways.
- [ ] Large fonts (200 percent) on a 360 dp phone: the Draw bar chips and the three tank buttons (Feed,
      Creatures, Draw another) wrap, not clip. All targets are 48 dp.

## 4. See it on your wall

Sandbox coverage: 205 tests across the maths, draft, canvas, steps, export, system print and Enlarge.
Nothing here has run on a device.

### Gestures and look

- [ ] Drag a frame with one finger on a real wall photo: it follows the finger 1:1; a frame a few dp wide is
      grabbed (24 dp slop); with overlap, the frame under the finger is the one that moves.
- [ ] Pinch and twist with two fingers: size and tilt feel right, a standard size (6 percent) and level (3
      degrees) are landed on when you let go, and the snap does not surprise. The snap happens only after a
      gesture that moved, sized or turned a frame: a plain tap on a frame tilted 2 degrees with the Tilt
      buttons, or sized within 6 percent of a standard size with Bigger / Smaller, must change nothing, and
      a finger that wobbles a few dp in a tap must not visibly move it.
- [ ] Edge zones (section 2) over the wall photo and a frame wholly inside a zone moved with the buttons.
      Move down / up many times on a real wall photo: the frame stops at the photo's bottom and top edges
      and can still be grabbed.
- [ ] Look: black ground and white ink in LIGHT theme; chips, fields and buttons; the dashed selection outline
      and corner squares on a light and a dark wall; shadow, mat and the three mouldings; the picture cropped
      to cover the frame; a picture whose preview failed (grey box).
- [ ] RTL: move-left / right and tilt icons mirror while actions stay literal; RTL is not supported app-wide
      and was not tried.

### Scale accuracy

- [ ] Photograph a real wall with a known thing on it (an A4 sheet, a door), mark it with two taps, and compare
      a hung 30 x 40 cm print on screen with the real print. Then try the typed-distance route and compare.
      Expect perspective error when the reference is not in the plane or at the height where the picture hangs.
- [ ] A photo with no focal-length tag (a screenshot, an edited copy) shows "No lens details, so a common
      lens is assumed."
- [ ] The distance formula (image share of the 43.27 mm diagonal) reads about right against a measured wall
      (199.7 cm at 2 m, 26 mm, 4:3 portrait).

### Print file (real size)

- [ ] Save a print file from a real 12 MP and a 48 MP photo at A4 and at 40 x 50 cm. Open the JPEG in Gallery,
      a desktop viewer (macOS Preview, Windows Properties, exiftool) and a photo-lab uploader: the size in cm
      equals the frame (pixels / dpi), the dpi reads the same in Exif and JFIF, there is **no GPS**, and it is
      a NEW file under `Pictures/Fotoz/Prints` (Android 10 and later; below that, in the default Pictures
      folder) that appears in the gallery with the photo's date. For the 48 MP photo at 40 x 50 cm, the
      density stamp is written in place or streamed, so it adds no heap use; check dpi reads the same in
      Exif and JFIF (exiftool). Proved only on synthetic and small Skia-encoded JPEGs, never on a device.
- [ ] **Print at real size on paper.** With a real printer (or Save as PDF then print at 100 percent) choose A4,
      Letter, A3, portrait and landscape: a 10 x 15 cm picture measures 10 x 15 cm with a ruler and is centred
      when it fits; one that does not fit is shrunk to stay inside the paper less the margins the print
      dialog reports (`PrintAttributes.minMargins`; 1 cm only when it reports none). A4 on A4 therefore
      shrinks to about 90 percent inside the printer's edge, not edge to edge; 10 x 15 cm on 4x6 photo
      paper or a borderless printer keeps real size (confirm the dialog really reports 0 there, and a
      printer-reported margin on photo paper shrinks it slightly, correctly). The PDF is one page and
      follows the paper chosen in the dialog.
- [ ] Cancel the print dialog during the preview; leave the screen with the dialog open (the adapter fails
      with a sentence, no crash); a device with no printing service shows no Print button.
- [ ] Colour: export a Display-P3 photo (a recent Pixel or Galaxy default) and an sRGB one; the file is sRGB
      (Skia embeds a profile) and saturation matches in a colour-managed viewer; same for an Enlarged file.
- [ ] Crop: "Hang them" hangs a landscape photo lying down (portrait presets turned) and a mixed set in
      square cells without overlap; a photo with EXIF rotation hangs the right way up even if you press
      "Hang them" the instant after choosing it (until its decode finishes the library's listed size is
      used, which can be the un-rotated grid: "Switch portrait and landscape" fixes it). To see the
      portrait-frame crop press "Switch portrait and landscape" first: a landscape photo in a portrait
      frame keeps the middle, exactly as the wall preview shows; check a 50 x 70 frame from a 16:9 photo.
- [ ] Memory and time: a 48 MP photo to 40 x 50 cm at 300 dpi on a low-RAM phone (does the ladder step down,
      what does the notice say, does it OOM anyway); how long the progress bar takes. Cancel in the app and in
      the notification stops it and leaves no file in Pictures and nothing in `cache/print-staging`.
- [ ] Protected photos: start an export, lock the photo's folder (or hide a sensitive photo) before it finishes:
      it fails with "This photo can't be exported right now." and nothing appears in Pictures. Leaving
      Experimental while an export runs cancels it.
- [ ] API below 29, if a device is available: the MediaStore insert without `RELATIVE_PATH`, the
      `WRITE_EXTERNAL_STORAGE` grant and the folder the file lands in (the default Pictures folder, not
      Fotoz/Prints); the notice "Saved to your pictures." is true there.
- [ ] Notification: the job shows "Making a print file" (or "Enlarging a print file") with progress and
      Cancel in the shade and the foreground notification, and "Saved to your pictures." follows.
- [ ] On Android 15: the notification is declared with the right foreground-service type for an export job.

### Enlarge

- [ ] Enlarge a real 12 MP photo to A4 and 30 x 40, and a 3 MP photo to 40 x 50: the file is 300 dpi in Exif
      and JFIF (or the lower figure the line above Save promised if 8192 px or 64 MP stopped it), no GPS, a new
      file, and the notice reads "Saved, enlarged with no new detail."
- [ ] Look: compare with the same photo stretched by a lab or a desktop bilinear at 100 percent. Check hard
      edges (text, wires, roof lines) for ringing from Lanczos3 plus the unsharp mask (`enlargeSharpen` 0.1 at
      2x, capped 0.25), and smooth skies and skin for banding. The amounts were chosen by reasoning, not by
      looking: tune from this check.
- [ ] Time and memory: the JVM took 1.6 s for 4000x3000 to 8192x6144 without the mask and 4.8 s with it; time a
      12 MP to 40 x 50 enlargement on a mid-range and a low-RAM phone (progress, no ANR, ladder).
- [ ] Cancel during the bands stops within about one band, leaves nothing, and the chip reappears.
- [ ] The chip is announced as a toggle with its state, reachable at 48 dp, shows a tick when selected, and
      "This size has fewer pixels than a lab likes." does not truncate at 360 dp or a large font. It appears
      only below 200 dpi. Its tick survives rotation and process death on the Hang step.

### Lifecycle and access

- [ ] Scale by distance: type a distance that gives no width (for example 5 cm) after marking a reference:
      the wall width empties and Next is disabled with "Set the scale to go on."; typing '300' digit by
      digit flickers the width but ends set. Choose a different wall photo after calibrating the first by
      distance: the distance field starts empty.
- [ ] Cancel (and leave the screen) at the very end of Save print file, while the copy into the library
      runs: either nothing is in the library and the job reads as cancelled, or the print is there and the
      notice says it was saved; never a silent cancel with a file in the library.
- [ ] Memory with six chosen pictures: they are decoded (1024 px each) on the Pictures step as soon as they
      are chosen; no stutter or out-of-memory on a low-RAM phone.
- [ ] Process death: set a scale, pick three pictures, hang them, move one, kill the app and reopen into
      Experimental > See it on your wall: the same step, frames and calibration return and the pictures
      decode again. Lock a folder holding a hung picture while the screen is open: it disappears from the wall.
- [ ] Memory and speed: a 48 MP wall photo and six large hung pictures decode to 1600 / 1024 px; check the
      first-open delay and that dragging stays smooth on a low-end phone.
- [ ] TalkBack: the four step chips, layout chips, the photo grid (selected state and number), the "Picture n"
      chips and the whole Hang panel (size, move, tilt, mat, style, units) are reachable and announced at 48
      dp; the canvas announces "Your wall with n pictures"; the Print it heading, progress bar, Cancel, Save
      and Print buttons are announced; white-on-black buttons are readable in light theme.

## 5. Offline map areas (Connect build)

Sandbox coverage: the pure budget, tile, suggestion and guard logic; the engine seam against a fake; the
job and pane against a fake backend. `MapLibreOfflineBackend` and `MapRequestCap.install` have no
unit test (they need the native library) and were written from the library's bytecode. **Do the spike first;
if the cap does not hold, do not ship the download** (email info@openfreemap.org instead; ADR-015).

### The spike (replace constants with measured numbers)

- [ ] **The 4-parallel cap takes effect.** Install the cap, start one small region behind a **logging proxy**
      and count concurrent requests (never more than 4 across all hosts) and read the User-Agent (ends with
      " Fotoz"). This decides whether `throttleInstalled` may ever be true. If the native downloader does not
      go through `HttpRequestUtil.setOkHttpClient`, `MapLibreRequestCap` does not work.
- [ ] **Glyph and sprite overhead.** Read `completedResourceSize` minus `completedTileSize` after a real
      download and replace `RegionBudget.ESTIMATED_FIXED_OVERHEAD_BYTES` (guess 10 MB).
- [ ] **`requiredResourceCount` against the tile maths.** For one small box compare the downloader's count with
      `TileMath.tileCount` (a box that only touches an edge does not take the next tile here; the downloader
      may differ), and whether MapLibre's 512 px tile indices equal slippy xyz at the same zoom.
- [ ] **Per-zoom tile sizes.** Replace the byte table (estimated from 12 sampled, urban-skewed tiles) with
      measured averages, urban and rural; Greater London and Switzerland-sized boxes currently come out
      Reduced and TooLarge (bytes), and some rural areas are refused that would fit.
- [ ] `completedResourceSize` counts what the provider counts against 50 MB, is monotonic and includes glyphs
      and sprites, as `DownloadGuard` (stops at 45 MB or 2,500 tiles) assumes.
- [ ] **Airplane-mode panning reads the region.** Pan inside the saved area (tiles show), outside (blank). The
      dark style and terrain shading are not available offline, as the copy says.
- [ ] Opening the pane makes **no** network request (watch the proxy while opening Settings > Data > Offline
      maps and the pane from the pill); Download is the first request. `createOfflineRegion` leaves the
      region inactive and fetches nothing.
- [ ] Pause keeps what was fetched and stops requests at once; a status after pause is dropped; a complete
      region goes inactive by itself and the throttled client is gone (live panning is full speed again
      after a download); delete removes the region and `mbgl-offline.db` shrinks. The cap stays in force
      until the downloader has reported the region stopped, or 5 s after it was told to stop
      (`MapLibreRegionEngine.SETTLE_MILLIS`, a guess): behind the logging proxy never more than 4 requests
      in flight, including in the second after Pause or Delete. The stopped reading only arrives because
      `MapLibreOfflineBackend` sets `setDeliverInactiveMessages(true)` (read from bytecode, not run):
      confirm the default client comes back within about a second of a Pause and not only at the 5 s
      timer, and that Resume within 5 s of a Pause keeps the cap and continues.
- [ ] After force-stop, `list` returns the region with its stored metadata, no running download, progress where
      it stopped; Resume continues rather than restarting. A download that finishes while the pane is closed
      is listed as saved when it is opened.
- [ ] OkHttp 5.3.0 (resolved for the app) satisfies the library's calls compiled against 4.12; the existing live
      map loads tiles at all in the Connect build.
- [ ] CJK place names (`includeIdeographs = false`) are missing in a saved area: decide if the byte budget
      allows turning it on. Sprites render offline at the screen density clamped to 1..2.
- [ ] A box over the date line is refused ("Areas over the date line cannot be saved yet."); to enable it,
      create one with east > 180 and compare `requiredResourceCount` with the two-range tile count.

### Pane, pill and job

- [ ] Connection: mobile data asks first, Wi-Fi does not, and in airplane mode a tap on Download or Resume
      says "No connection right now." and creates no area, job or question (`PlatformConnectionCheck`;
      `ACCESS_NETWORK_STATE` is declared in the Connect manifest; a SecurityException or a platform that
      cannot be asked answers "metered"); declining starts nothing. The check ignores captive portals and
      VPN-only networks.
- [ ] The privacy sentence sits once under the first Download button (This view, else the first place)
      and again in the mobile-data question, at a large font and with TalkBack. A second place's Download
      and a Resume on Wi-Fi show it nowhere near the button: decide if that is enough.
- [ ] The pane in its Dialog: black room, title and Back, cards, buttons, progress bar, dialogs; insets in a
      Dialog window under a cutout and 3-button navigation; light and dark theme; large font.
- [ ] TalkBack reads Download (label and the reason as state description when disabled) and Resume / Pause /
      Delete with the area's name.
- [ ] The "Save this area offline" pill (top left of the embedded Map) does not overlap the compass (top
      right), the browser's own top chrome, the attribution or the bottom pill and discs; it appears after the
      camera settles and disappears on a country-sized view.
- [ ] `ViewArea.boxOf` against the library's visible region at tilt 0: confirm 512 logical px per tile and
      that dp is the right unit; on the default pitched map the pill's box is a sensible "what I am looking at".
      Turn the map by about 45 and 90 degrees, take "This view", download, go offline: the screen's corners
      have tiles at the saved zoom range (the box is the bounding box of the turned screen; pitch is still
      ignored), and a very turned view of a city can read as too big sooner.
- [ ] The estimate on the Download button ("about 12 MB") against `completedResourceSize` after a real download.
- [ ] Pausing from the pane, the shade or the notification Cancel leaves the area "Paused."; force-stop then
      reopen shows "Paused, tap resume to continue." and resumes only from the tap; reopening mid-download
      shows live percent, and Pause from that reopened pane stops the job. Pause works for an area whose
      name has an emoji at character 80 or a line break (the job is found by the area's id, not its
      title). The saved list shows the newest saved area first.
- [ ] The delete confirmation names the area and size; "frees about N MB" is true only if the database shrinks.
- [ ] A typed name in Rename survives rotation and process death; a changed set of suggestions can shift keys so
      a name lands on another place.
- [ ] Foreground service: "Downloading map" appears with the pink-red accent and the sync glyph (a download arrow
      would be better) and the platform download glyph on the notification; on Android 15 it is declared data
      sync and the six-hour budget's `onTimeout` stops the notification without cancelling the job.
- [ ] Storage: nothing outside the map library's database and the stored region records is written.

## 6. Both flavours

- [ ] Offline build: Settings > Data > Offline maps says "Offline maps need the Connect build."; no map
      pill; Experimental shows no map card; nothing in either experiment needs a permission.
- [ ] Connect build: the photo details room's expanded map (header, close, refresh, footer) still works after
      the search and chip rows were removed; Map mode's embedded street map is unchanged.

## 7. After the audit fixes

Added by the final verifier for the fixes made after the first audit. Each is unverified on a device.

### Experimental host, rail, compass and flagged photos

- [ ] Open an experiment, drag from a screen edge to open the nav or options room, press Back: the room
      closes and the experiment stays; a second Back closes the experiment. Repeat inside the aquarium
      drawing step, the wall steps and the compass. Unverified on a device: only a Robolectric stand-in
      handler was tested.
- [ ] Rail: look at "Experimental" on the 260 dp pinned rail and in the pull-out room on a 360 dp phone. It
      should be whole at a smaller size than the other words. Also check at font scale 1.3 (it may still be
      cut at the 28 sp floor), that no word flickers when the wheel scrolls, and that the 44 sp words did not
      move.
- [ ] Fresh install or library with no location data: open Experimental > Compass view. After the read
      finishes, the compass should leave the "Read embedded coordinates first" panel without leaving the
      screen, and the map should stop saying "not read yet".
- [ ] Flag a newest photo as sensitive with Blur sensitive on: it should not appear as a hub card cover, in
      the wall picker, in the 3D wall, similarity or compass. Turn Blur sensitive off and it should come back.

### Aquarium

- [ ] Open the aquarium with Remove animations on (animator scale 0) and at least one saved creature.
      Creatures should be visible at once without pressing Play.
- [ ] While paused, draw a creature and press Swim. It should be visible in the tank on return, without
      pressing Play. Delete one from the list while paused. It should disappear at once.
- [ ] Draw a tall narrow loop (about a third of the board high, a finger-width wide) and lift the finger a
      little short of the start. The board preview should show it filled, and the saved creature should be
      filled and have a closed outline.

### Wall

- [ ] On a phone: mark a door's two ends with the Door chip, tap the Hang chip, then Set scale again. The
      wall width and the mock-up size should not change, and the Door chip should still be selected.
- [ ] Type a wall width by hand after marking, go to Hang and back, and check the typed width is still there.
- [ ] Change the reference chip or its length after marking and check the mock-up resizes at once. The tests
      inject taps, so where marks land on a real touch screen is unverified.

### Offline map areas

- [ ] Cold start (force-stop, open Settings > Data > Offline maps before ever opening Map mode): the pane
      must not say downloads are unavailable in a debug build. Then open Map mode and confirm tiles and style
      load normally. This checks that the cap probe no longer breaks the live map.
- [ ] With a debug build behind a logging proxy, download an area. Confirm no more than 4 requests are ever
      in flight and the User-Agent ends in "Fotoz". Only then set
      `MapLibreRegionEngine.CAP_VERIFIED_ON_DEVICE` to true. Until then confirm a release build shows the
      pane as unavailable with Download disabled.
- [ ] Unlock a private folder that has geotagged photos, open Settings > Offline maps and the map pill's
      sheet: "Places in your photos" must not list that folder's locations. Then lock it again and confirm no
      such area is listed under SAVED AREAS.
- [ ] Make an area hit the size limit (a dense city at the top zoom if one beats the estimate). Confirm the
      row says "Stopped at the size limit.", shows only Delete, and that a smaller download of the same place
      is allowed. Force-stop and reopen: the row must still show the limit state and no Resume.
- [ ] Look at the pane at large font size and with TalkBack. The new line "Saved areas are not locked with
      your private folders." sits under the privacy note, and the stopped row has no Resume button. Nothing in
      this batch has run on a device.

## 8. After the polish

Added after the second audit pass (the LOW findings and the reviews of their fixes). Every line is
**unverified until run on a device**: the tests behind them are Robolectric and fakes, and Roborazzi only
records. Some lines repeat a check above in the exact shape the fix needs; tick both.

### Experimental host

- [ ] Open the aquarium with a sketch in progress, then Settings from the rail, then back to Experimental: the
      sketch and the open experiment are still there. Same with a wall draft, with the Map word, and with a
      rail scrub that rests on another word for 140 ms. (unverified)
- [ ] Compass view > tap a photo > Back from the viewer: you are in the compass, not the hub. Compass open >
      Settings or another rail word > back to Experimental: still in the compass. (unverified)
- [ ] Rotate or toggle dark mode with the compass open: it stays open. Then kill the process from recents:
      it comes back as the hub, and no location read or permission prompt starts by itself. With the
      aquarium or the wall mock-up open it reopens there. (unverified)
- [ ] TalkBack: the hub title, "Privacy boundaries" and the group titles are headings; a hub card and the
      Discover Experimental card are each announced once as a button with no "long press" action; the Discover
      card reads no stray number. (unverified)
- [ ] Read the hub header, the privacy sentence and the location sentence on a 360 dp phone in both
      flavours: each block is one sentence and wraps well. The Connect privacy sentence (154 characters, a
      semicolon and a "and" clause) is the longest and arguably not "short": decide if it should be cut.
      (unverified)
- [ ] Print from the wall with a network printer and with a local one: "printing uses the printer you
      choose" is accurate, and Fotoz itself makes no network call (the Offline build has none by
      construction). (unverified)
- [ ] The hub covers with tens of thousands of photos, during a location pass: scrolling does not jank (the
      cover choice is O(1) per card now; not measured). (unverified)
- [ ] Process death with several experiment drafts parked in the holder (a big sketch, a wall draft): no
      `TransactionTooLargeException` on a real device. (unverified)
- [ ] With Blur sensitive on, flagged photos are simply absent from the experiments (not blurred): confirm
      that is what you want. (unverified; owner decision, see MASTER-PROGRESS)

### Draw an aquarium

- [ ] Cut a PNG in `filesDir/aquarium` short (or empty it) with `run-as`, open the aquarium: the creature
      is not shown, both files are gone, and Swim is not blocked by a full tank. With 12 files, one of them cut
      off, the 12th creature can be saved. Also cut one at about 100 bytes, after the header: Android may
      return a partial bitmap there and keep it (listed, deletable), which is not the Robolectric result.
      (unverified)
- [ ] Press Swim on a slow phone and try to draw, change colour or brush, or tap a shape in the first
      moments: nothing is added, the controls look disabled (including the colour swatches, which have no
      dimmed look of their own: check they do not look live), and the note says "Making your creature."
      TalkBack announces them as disabled. (unverified)
- [ ] Tick "Head faces left", tap each ready shape and press Swim: the creature swims head first with the eye
      on the left. (unverified)
- [ ] Draw until 58 or more strokes, then tap a shape: nothing is added, "No room for more strokes." shows
      and goes on the next stroke or Undo. After a failed Swim that line can hide the save-failed sentence:
      note it if you see it. (unverified)
- [ ] Open the aquarium with Remove animations on and a saved creature: it is visible at once. While paused,
      draw and Swim, and delete one from the list: the tank updates without pressing Play. (unverified)

### See it on your wall

- [ ] Tap a frame tilted 2 degrees and one sized within 6 percent of a standard size: nothing changes. Drag
      or pinch near a standard size and level: it snaps on release. (unverified)
- [ ] Scale step, with 3-button and gesture navigation: tap the left and right border of a landscape wall
      photo that fills the width: the mark lands and the shell does not open a room. The Hang step still
      ignores drags that start in the edge zone. (unverified)
- [ ] "Hang them" with a landscape photo, a portrait photo and a mix (pair, trio, gallery wall): landscape
      ones lie down with little crop, mixed sets do not overlap and look acceptable. (unverified)
- [ ] Select a frame and press Move down many times: it stops at the photo's bottom edge and can still be
      grabbed; the same for Move up. (unverified)
- [ ] Type a distance that gives no width, then Hang with frames already hung and press the Move buttons:
      nothing moves to a wrong place. Known gap: with the wall width at 0 the Hang step is still reachable
      from the step chips and the Move buttons can compute an infinite or NaN position; check what happens.
      (unverified)
- [ ] Save a print file at the end of a cancel race, and a 48 MP photo at 40 x 50 cm on a low-RAM phone: the
      density stamp adds no heap use, and the JPEG's dpi reads the same in Gallery, a desktop viewer and
      exiftool. (unverified)
- [ ] Print A4 on A4 on an ordinary printer: nothing is clipped at the sheet edge. Print 10 x 15 cm to a 4x6
      photo-paper profile and a borderless printer: the dialog reports a margin of 0 and the print is full
      size, measured with a ruler. The preview and the PDF page size follow the paper chosen. (unverified)
- [ ] On API 26 to 28, if a device exists: "Saved to your pictures." is true and the file is in the default
      Pictures folder; on Android 10 and later it is in Pictures/Fotoz/Prints. (unverified)

### Offline map areas (Connect build)

- [ ] Cap order: behind a logging proxy start a download, then Pause: never more than 4 requests in flight,
      including the second after the tap. Repeat with Delete on a paused-then-resumed area and for a download
      that finishes by itself. A region that finishes by itself may hold the cap for the full 5 s if its last
      reading already says stopped: note how long the live map stays throttled after a finish. (unverified)
- [ ] Resume right after Pause (within 5 s): the download continues, the cap stays on and nothing is
      unlistened by the old timer. (unverified)
- [ ] Airplane mode: tap Download on "This view" and on a place, and Resume on a paused area. Each shows "No
      connection right now."; no area is listed, no notification or job appears, no mobile-data question
      shows. On mobile data the question appears and shows the privacy sentence; on Wi-Fi it starts. In the
      merged Connect manifest the check still gets its answer (no SecurityException). (unverified)
- [ ] The privacy sentence sits under the first Download button once, and in the mobile-data question, at a
      large font and with TalkBack. (unverified)
- [ ] Rotate the map to about 45 and 90 degrees, take "This view", download, go offline: the screen's corners
      have tiles at the saved zoom range. (unverified)
- [ ] Pause an area whose name has an emoji at character 80 or a line break: the notification goes and the row
      shows Paused. Also Pause from a pane reopened while the download runs. (unverified)
- [ ] After a force-stop an unfinished area shows "Paused, tap resume to continue." and resumes only from the
      tap; the saved list shows the newest saved area first. (unverified)
- [ ] Nothing in this section, or in sections 1 to 7, has run on a real device. The engine, the backend and
      the connection check were tested against fakes and written from the library's bytecode.
