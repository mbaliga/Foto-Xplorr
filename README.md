# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit 08c5039.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## Fixed

**Sharing worked at all.** It threw on every attempt. The share pipeline was
rewritten and its cache folder renamed while the FileProvider config still
declared the old name, so Android refused to hand out the URI. Nothing failed
to compile and no test covered it, because the coupling is a Kotlin string
against an XML string — there is now a test that reads both files.

**The swivel faces the room.** It was turning the pane away from the content
it had just revealed. "The free edge recedes" was my aesthetic call, not
something you asked for; both rotation signs are flipped.

## New

1. **Peek is a hold.** Press and hold a tile to see it large; let go and it
   goes. Its buttons are gone — a control you must release to reach is not a
   control. Selection therefore has an explicit way in: **Select photos**, in
   the gallery's actions room. Un-picking your last photo now keeps you in
   selection rather than dropping you out; the X leaves.

2. **The four edges mean the same thing everywhere.**

   | | |
   |---|---|
   | **LEFT** where you can go | **RIGHT** what you can do here |
   | **TOP** settings | **BOTTOM** what this is |

   Settings used to be the gallery's RIGHT room and the viewer's TOP one. The
   gallery now has all four: a new actions room (select, new collection,
   rescan, columns, sort, what to show) and a new info room (photos, videos,
   size, span, folders — counted for whichever view you are actually in).

3. **The notification opens.** Pull down on the status strip for the expanded
   view; pull up to close. Tapping does the same. (The shell had to hand that
   strip back — its own top-room gesture lived in the same pixels.)

4. **Every header on a photo, always.** Dashes where the file has nothing,
   rather than the row disappearing. FILE: name, kind, size, dimensions,
   megapixels, aspect, duration, album, path, taken, modified, colour space.
   CAPTURE: camera, lens, focal length, aperture, shutter, ISO, exposure
   bias, flash, latitude, longitude.

5. **A photo with no location gets a map, not a sentence.** The stylized
   field, turning slowly to say "nothing found yet", with a pin you drag and
   coordinate fields you can type into — both editing one value, so they
   cannot disagree. The spin stops when a coordinate exists. Saved in Foto
   Xplorr's index, **not written into your photo file**: rewriting your
   original to add a GPS tag is a destructive change to your data and needs a
   per-file grant, and neither belongs behind a pin drag.

## Still open

- **The editor is not GIMP-grade.** It is presets and preview-resolution
  saves. See the notes in chat — that is a large piece of work and I have not
  started it.
- I have not identified which mockup you meant. Asking in chat.
