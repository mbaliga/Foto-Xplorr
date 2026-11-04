# Batch 10 device test checklist

Every device-only claim from batch 10 (plan Phases 1-2 in safe order), grouped by area. The areas are: formats and
placeholders, RAW previews, simple formats, grid tiles and memory, deep zoom, HDR and the pro overlay, video frame step,
slideshow, the 360 view, hover/Quick Look/preview pane, justified rows, compare and culling, XMP sidecars, the format
corpus test, the launcher icon and CI. See `docs/handoff/MASTER-PROGRESS.md` (Batch 10) and TRAPS #134 to #149.
**Nothing in this batch has run on a device. Every line is unverified until you tick it.** Roborazzi runs in record mode,
so no screenshot test proves a look.

Suggested devices:
- an API 26 or 27 phone (no platform HEIC regions, no `RELATIVE_PATH` before 29);
- a current phone with an HDR panel (Android 14+);
- a low-end phone (3 to 4 GB);
- a tablet or foldable, plus DeX or a Bluetooth mouse and keyboard.

Prepare:
- real RAW files: CR2, CR3, NEF, ARW, ORF (including an E-1/E-300-era one), RAF, RW2 and DNG, plus extension-less copies
  of a DNG, a NEF and an ARW;
- simple-format files from real apps:
  - TGA, QOI and PNM;
  - a layered PSD;
  - a 20 MP uncompressed camera TIFF (about 60 MB), a single-strip 16-bit RGB TIFF of about 24 MP, and an RGBA TIFF from
    libtiff or ImageMagick;
  - a macOS `.icns`, and Krita and GIMP ORA/KRA exports (one around 20 MP);
  - the crafted headers from `SimpleHostileHeaderTest`, written to files;
- photos:
  - a 100 MP JPEG;
  - a large PNG with soft alpha, and an opaque RGB PNG;
  - a WebP with alpha;
  - a HEIC;
  - an Ultra HDR JPEG;
  - a P3 photo;
- spheres: an equirectangular 360 photo, an 8192x4096 sphere with FullPano but no CroppedArea, a partial panorama and a
  cylindrical GPano panorama;
- video: H.264 and HEVC clips, a variable-frame-rate phone video, and 29.97, 59.94 and 240 fps clips;
- a library folder (DCIM/Camera) of 10,000+ photos, and a photo on an SD card;
- a darktable or Lightroom sidecar beside a RAW+JPEG pair.

## 1. Formats and placeholders
- [ ] **No black tiles.** A real CR3, NEF, ARW, TIFF, EXR and PSD in the grid each show a photo or the neutral tile with
      a badge, never black or empty. Check the badge's size and contrast, and what TalkBack reads (a failed load: the file name, then the
      type sentence; a refused format: the type sentence). (unverified)
- [ ] **Broken files.** A corrupt JPEG and a 0-byte file end as "This file couldn't be read." placeholders. (unverified)
- [ ] **HEIC and AVIF by API level.** A HEIC on an API 28 device without HEVC, and an AVIF on an API 31+ device that
      cannot decode it, take the placeholder path instead of drawing blank. (unverified)
- [ ] **Sniff cost.** On a large folder of files MediaStore gives no type, scrolling stays smooth. Only those files are
      sniffed (64 bytes each). (unverified)
- [ ] **Viewer reason.** The viewer shows the one-line reason on an undecodable file. (unverified)
- [ ] **Protected photos.** A protected photo shows no thumbnail anywhere. This needs an end-to-end check, because the
      protected flag is not passed into `MediaImage`. (unverified)

## 2. RAW previews
- [ ] **Previews, upright.** Grid tiles and the viewer show the embedded preview of real CR2, CR3, NEF, ARW, ORF, RAF,
      RW2 and DNG files, upright, including portrait shots. (unverified)
- [ ] **Old Olympus.** An E-1/E-300-era ORF shows the full-size preview, not the 160x120 thumbnail. (unverified)
- [ ] **Extension-less RAW.** An extension-less DNG, NEF and ARW each show their embedded preview as a tile, upright, not
      the small IFD0 thumbnail. (unverified)
- [ ] **No usable preview.** A DNG with no usable embedded preview: note whether the platform's full decode is upright.
      That fallback path is not rotated by us. (unverified)
- [ ] **Placeholders.** CRW, X3F, MRW and IIQ show "Raw photos can't be previewed yet." (unverified)
- [ ] **Editor note.** Editing a RAW and saving a copy shows the reduced-size note. (unverified)
- [ ] **Tile cost.** Note how long a RAW preview tile takes through the content uri. The plan's target is 60 ms or less
      per thumbnail. (unverified)

## 3. Simple formats
- [ ] **Files from real apps.** A TGA, QOI, PSD, TIFF, ICNS, ORA and KRA exported from Photoshop, Krita and GIMP each show
      in the grid and the viewer. (unverified)
- [ ] **TIFF orientation.** A TIFF with an orientation tag is upright. (unverified)
- [ ] **16-bit TIFF.** A multi-strip 16-bit RGB TIFF looks right. A single-strip uncompressed 16-bit RGB TIFF of about
      24 MP (6000x4000) opens in the viewer, not as a placeholder, with no out-of-memory error. (unverified)
- [ ] **TIFF alpha.** An RGBA TIFF with ExtraSamples=1 has no darkened edges. (unverified)
- [ ] **Large ORA/KRA.** One around 20 MP still opens under the 128 MB inflate cap. (unverified)
- [ ] **ICNS.** A real macOS `.icns` shows its largest icon. (unverified)
- [ ] **Hostile files.** A crafted .tif, .psd and .ora each show a placeholder, with no crash and no heap pressure felt
      elsewhere in the app. (unverified)
- [ ] **Memory and time.** Note memory near the 24 MP cap and the decode time on a mid-range phone. (unverified)

## 4. Grid tiles and memory
- [ ] **Crop sharpness.** In grids that crop tiles (gallery, album stack, calendar, similarity), TGA, PSD, TIFF and RAW
      tiles look as sharp as the JPEG tiles beside them, and a 3:1 panorama tile is sharp. (unverified)
- [ ] **Big files in the grid.** Scroll a grid with a 20 MP uncompressed TIFF (about 60 MB) and a layered PSD. Both get
      thumbnails, with no out-of-memory error and no long stall of the other tiles. (unverified)
- [ ] **Fast scroll.** Scroll quickly through several 100 to 150 MB scanned TIFFs and extension-less NEF/ARW files. There
      is no out-of-memory error and no long GC pause. (unverified)
- [ ] **Viewer during tile decodes.** Open such a file in the viewer while tiles are still decoding. The viewer waits for
      at most two light tile decodes, and later tiles do not starve it. (unverified)
- [ ] **File length.** Real MediaStore and SAF content uris give a length, so simple-format tiles load. (unverified)
- [ ] **Scroll latency.** Reading through the content uri adds no noticeable scroll latency. (unverified)

## 5. Deep zoom
- [ ] **100 MP JPEG.** It opens by file descriptor without buffering the whole file. Tiles arrive in time, it pans at
      60 fps, 1:1 is sharp, and a real rotated photo is the right way up. (unverified)
- [ ] **Other formats.** Region reads work on HEIC, PNG and WebP. Note memory use when the stream fallback is used.
      (unverified)
- [ ] **Alpha.** An RGBA PNG and a WebP with alpha keep the 6x ceiling and the small double-tap zoom, with no jump to 1:1
      and back. An opaque RGB PNG and a JPEG still reach 1:1 with sharp tiles. The 16 px probe adds no visible delay.
      (unverified)
- [ ] **Soft alpha.** A large PNG with soft alpha on the checkerboard shows no darker seam and no doubled opacity at deep
      zoom. (unverified)
- [ ] **Probe on a device.** The probe reports alpha on PNG, WebP and HEIC as the format header says, and a HEIC still
      tiles. (unverified)
- [ ] **HEIC on API 26/27.** The pinch ceiling stays at 6x and the double-tap goes to 2.5x, not 1:1. StrictMode reports
      no leaked ParcelFileDescriptor. (unverified)
- [ ] **Ceiling drop.** Zoom to about 30x on a 100 MP photo, then rotate past the snap or turn on a colour filter. The
      zoom pulls back to 6x at once, keeping the same centre. (unverified)
- [ ] **Page swipes.** Swipe pages during a slow tile decode: no dropped frames from closing the decoder. (unverified)

## 6. HDR, wide colour and the pro overlay
- [ ] **Ultra HDR.** An Ultra HDR photo shows visibly brighter highlights on an HDR panel. A P3 photo shows wide colour
      correctly. (unverified)
- [ ] **Leaving the viewer.** The window colour mode is restored, and switching modes does not flicker badly.
      (unverified)
- [ ] **Masks line up.** Clipping and focus-peaking marks line up with the photo under zoom. Past 1.05x on a large JPEG
      they stay visible over the sharp tiles. (unverified)
- [ ] **Card placement.** The pro card does not collide with the filmstrip or the minimap. (unverified)

## 7. Video frame step
- [ ] **One frame per press.** Each press shows the neighbouring frame on H.264, HEVC and variable-frame-rate phone video.
      A clip with no declared rate shows "This clip does not say its frame rate, so a step is about one thirtieth of a second.". (unverified)
- [ ] **Hold and slide-off.** Press and hold: it repeats and speeds up. Press and slide the finger off: it stops stepping.
      (unverified)
- [ ] **Resume.** Normal play and seeking behave as before after a frame step, including after Retry. (unverified)

## 8. Slideshow
- [ ] **Transitions.** Fade, slide and zoom fade are smooth, and nothing flashes on the first frame of a transition.
      (unverified)
- [ ] **Ken Burns.** It is jank-free and never shows past the photo's edge. (unverified)
- [ ] **Animations off.** With "Remove animations" the show cuts and stays still. (unverified)
- [ ] **Remembered choice.** The "Slideshow look" choice survives a restart. (unverified)

## 9. The 360 view
- [ ] **Button only on spheres.** "View in 360" shows on an equirectangular photo, not on a cylindrical GPano panorama.
      (unverified)
- [ ] **Over everything.** It opens over the filmstrip and pro card, and the button pad is reachable. A running slideshow
      does not advance while it is open. Back closes it. (unverified)
- [ ] **Taps on a real screen.** A slightly held or jittery tap on Close and on every pad button (left, right, up, down,
      zoom in, zoom out, reset, gyro) always fires. (unverified)
- [ ] **Nothing falls through.** A touch on the sphere, also while it loads or after it failed, never pages or toggles the
      viewer beneath. Drag and pinch still work on the stage. (unverified)
- [ ] **Failures.** Delete the file from another app, then tap View in 360: the view shows the failure line instead of
      crashing. On a low-memory phone it shows the memory line or decodes smaller. (unverified)
- [ ] **Gyro.** Gyro feel and direction are right. Press Home or lock the screen: sensor events stop (check with
      `dumpsys sensorservice`), and on return the view resumes without a jump. (unverified)
- [ ] **Full sphere.** An 8192x4096 sphere with FullPano but no CroppedArea wraps a full 360 degrees. A 4096x2048 decode
      fits in memory. (unverified)
- [ ] **Partial panorama.** A partial panorama opened in landscape shows no black past its edge, and turning the device
      re-clamps the view. (unverified)
- [ ] **Render rate.** Note the CPU render rate at 480 px wide on a mid-range phone. Look-around is smooth, and the memory
      profiler shows no bitmap churn. (unverified)

## 10. Hover, Quick Look and the preview pane
- [ ] **Hover.** With a Bluetooth mouse or DeX, the hover preview appears after 400 ms. It does not flicker, and it clears
      on leave, press or scroll. A finger never triggers it. (unverified)
- [ ] **Quick Look.** Space opens it on the keyboard cursor or the first chosen photo. Arrows step, Enter opens the
      viewer, and Escape, Space and Back close it. None of these fire while the search field has focus. The 48 dp buttons
      work by touch. (unverified)
- [ ] **Preview pane.** P, or the actions-room row, toggles the pane on a tablet or foldable (600 dp or more). It follows
      the hovered photo, else the cursor. (unverified)
- [ ] **Keys stay in the overlay.** With 5 photos selected, open Cull, then Compare, then Quick Look. In each, press
      Backspace, Delete, Enter, Ctrl+A and the arrows: nothing is trashed, opened or selected behind the overlay. Back and
      Tab still work. (unverified)

## 11. Justified rows
- [ ] **Look and feel.** Settings > Media > Justified rows (off by default): rows look right with real aspect ratios and
      scroll smoothly. The scrubber and pill track them. Pinch still changes the row height by rung. (unverified)
- [ ] **Top photo kept.** In a large library scrolled deep, pinch through the rungs, turn justified off and on, and go to
      Stamps and back. The same photos stay at the top. (unverified)
- [ ] **No jump on refresh.** Scroll partway into a row, then toggle a favourite or let a MediaStore refresh arrive. The
      grid does not jump. (unverified)

## 12. Compare and culling
- [ ] **Compare.** Compare 2 to 4 photos. Pinch and pan stay in sync across cells, and the drop and close buttons work.
      Zoom in with +, then reach the top-left corner with the arrow buttons only; repeat with the keyboard arrow keys.
      (unverified)
- [ ] **Culling by keys and touch.** Keys and the touch key-caps work, as do auto-advance and undo. Focus returns to the
      grid after leaving, and Back closes each overlay first. (unverified)
- [ ] **Auto/Stay.** Tap Auto/Stay right after P during a slow sidecar write: the chip keeps the chosen state.
      (unverified)
- [ ] **500 photos.** A 500-photo cull with the keyboard only. (unverified)
- [ ] **Memory only.** With "Save ratings beside photos" off, the HUD says ratings stay in memory. (unverified)
- [ ] **No grant yet.** Turn sidecars on with no grant, and rate several photos. The hint reads "Choose the photos'
      folder to save ratings beside them." "Choose folder" opens the picker. After you pick DCIM (or the photo's folder),
      a `<name>.xmp` exists beside every photo rated before the grant. The HUD returns to the normal hint only after those
      are written. (unverified)
- [ ] **Folder not covered.** Rate a photo in a folder the grant does not cover: the HUD stays on "not saved", even after
      later good writes. (unverified)
- [ ] **SD card.** A photo on an SD card with only a primary-volume grant still asks for a folder. (unverified)
- [ ] **API 26 to 28.** The cull asks for a folder, and a write still fails without losing any rating. (unverified)
- [ ] **10,000 photos.** Cull in DCIM/Camera with sidecars on. "Reading saved ratings." shows briefly, and key presses
      stay instant with no growing lag. (unverified)

## 13. XMP sidecars on real storage
- [ ] **Overwrite with a backup.** On the ExternalStorageProvider and a USB-OTG tree, overwriting an existing darktable or
      Lightroom sidecar first makes `<name>.bak`. It writes and reads back, then deletes the `.bak`. Check that `wt`
      truncation is supported and the `.bak` deletion works. (unverified)
- [ ] **Case and renames.** An existing `IMG_1.CR3.XMP` is merged into under its own name, with no second file. A
      provider rename on a clash (`IMG_1.CR3 (1).xmp`) is refused and cleaned up. (unverified)
- [ ] **RAW+JPEG pair.** Rating the JPEG writes `IMG_1.JPG.xmp` and never copies the RAW's Lightroom `IMG_1.xmp`
      (develop settings, keywords, rating). The RAW still reads its own. (unverified)
- [ ] **Desktop round trip.** darktable, Lightroom or digiKam reads Fotoz's rating, flag, label and keywords back, and
      their own fields survive. (unverified)

## 14. Format corpus test
- [ ] **Corpus decode test.** Run `./gradlew :app:connectedOfflineDebugAndroidTest`, which includes
      `FormatCorpusDecodeTest`, on an API 26 to 32 device and an API 33+ device. Every `platform` corpus file decodes
      through `decodeUpright` with the right size and quadrant colours, stubs return null, and nothing throws
      `NoSuchMethodError`. Keep the log of what each device does with the valid but unsupported files. (unverified)

## 15. Launcher icon
- [ ] **Light and dark.** The new Fotoz icon shows white ground and black panel in light theme, and black ground and white
      panel in dark theme. It switches when the system theme changes. (unverified)
- [ ] **Masks.** No part of the art is clipped by circle, squircle or rounded-square masks. (unverified)
- [ ] **Themed icon.** On Android 13+ with themed icons on, the monochrome layer is legible. (unverified)
- [ ] **Owner's eye.** The redraw is compared side by side with your original artwork. (unverified)

## 16. CI
- [ ] **Green run.** The PR's CI is green after these commits, including `core-native`, `lintOfflineDebug` and
      the full unit suite for both flavors. (unverified)

## 17. Composite media (WP2.8)
- [ ] **Motion photos.** A Pixel motion photo (Container), an older MicroVideo file and a Samsung motion photo show the
      play button; tap and hold play the clip with no sound, and music from another app keeps playing. (unverified)
- [ ] **Export.** Export still / Export video create new files in Pictures/Fotoz and Movies/Fotoz; a HEIC still is
      saved as .heic; Google Photos no longer shows the still as a motion photo; the original is unchanged; a sensitive
      or locked photo offers no export. On Android 8 or 9 the files keep their extension after a media rescan. (unverified)
- [ ] **Live Photos.** iPhone HEIC+MOV pairs show as one photo; the MOV is hidden in the grid and the button plays it
      silently. (unverified)
- [ ] **Bursts.** iPhone, Google and Samsung bursts collapse into one tile with a count badge; tap, Tab+Enter,
      Shift+Enter, the right-click "Open stack" and TalkBack open and close it; a same-second WhatsApp batch stays as
      separate tiles. (unverified)
- [ ] **Selection.** In a folder with a collapsed burst, Select all checks only the visible tiles and Trash removes only
      those. (unverified)
- [ ] **Round trip.** Open a stack, open frame 5, press Back: same photo, same place, stack still open; a search
      keystroke or a library refresh does not unstack iPhone bursts. (unverified)
- [ ] **Stereo.** A real MPO (Fuji W3 or 3DS) opens in all four 3D modes; the anaglyph works with red-cyan glasses;
      wiggle is hidden with Remove animations on; a spatial HEIC shows the "only one eye" line. (unverified)
- [ ] **Cost.** The first identifier scan of a 20k HEIC library: time, and no rescan or regroup flicker afterwards;
      cacheDir/motion-clips is empty after closing the viewer. (unverified)
