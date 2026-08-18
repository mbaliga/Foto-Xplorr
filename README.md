# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit b2ee549.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## What's new in this build

1. **Sharing is Fotoz-branded, and stylized by default.** Sharing a photo now
   goes through a Foto Xplorr sheet that sits *above* the system share sheet
   rather than replacing it. Three frames: none, Polaroid (deep bottom lip,
   the way a real print has), and the postage stamp — the same silhouette the
   app uses everywhere else, with real punched-out perforations rather than
   notches painted on. The stamp can carry a **seal**: your own short
   signature, set once in Settings, printed as a rotated postmark. Optional
   watermark. Live preview of the actual render, not a mock-up.

   **Metadata is stripped by default.** A shared photo leaves without its GPS
   coordinates, camera serial, or any other EXIF tag — only orientation and
   the capture date are kept, because dropping orientation turns photos
   sideways. "Advanced" on the sheet is where you turn stripping off, not
   where you turn it on.

2. **Selection looks like the reference.** The app bar is gone. Selecting
   photos now floats two clusters over the grid: actions at the top left,
   and at the bottom, "N SELECTED" with a dismiss next to it — with the
   trash deliberately isolated in the opposite corner, so the destructive
   action is never adjacent to the one you tap to leave.

3. **Long-press peek.** Press and hold any tile to see the photo large
   without leaving the grid; release to dismiss. Share / edit / open are on
   the peek itself.

4. **Settings, much richer and visual.** New **Media** tab with live WYSIWYG
   tiles — you see the actual effect of "fit to tile vs. original aspect
   ratio" on your own photos as you toggle it, not a description of it.
   Also: auto-preview on long press, loop animations (GIFs play in the
   grid), share defaults (frame, seal, watermark, stripping). **Data** tab
   restructured around backup and restore. New **About** with support at
   fotoz@asystemofcells.com and a link to more apps from asystemofcells.

5. **Album stacks and a calendar view.** Albums are drawn as fanned stacks of
   prints — depth tells you there is more inside — with a stable lean per
   album rather than a random one that twitches as you scroll. The new
   Calendar destination lays the library out month by month, one stamp per
   day. The layout references you sent were skeuomorphic; these are not.
   Same information, brutalist.

6. **The photo map now works offline.** This was the open question, and the
   answer turned out to be the one you already picked: since a photo's
   location is only a latitude and a longitude, drawing it needs arithmetic,
   not map tiles. So the stylized map — your photos as stamps on a plain
   field, tap one to open it as a poster with the rest beneath — is in
   **both** builds and performs no network requests at all. The offline
   build's three enforcement gates pass with it in. The MapLibre street map
   is still Connect-only and is now labelled "Street map" so the difference
   is visible on the hub.

   Dense libraries are thinned to one stamp per grid cell, so it stays a
   readable scattering rather than an opaque pile.

7. **The compass is immersive.** It was a title bar, two rows of chips and a
   status line above a small scene. It is now the scene, full bleed. Tap
   empty sky to summon the controls, tap again to dismiss. The bearing stays
   on screen as a compass rose whose bezel counter-rotates with the phone —
   the card holds north while you turn under it, like a real one.

8. **Swivel is more pronounced** — the centre pane sits at 22° at rest
   instead of the previous shallower angle.

## About locations on this device

Both the map and the compass read GPS from inside your photos. On the
library you've been testing with, most of it is saved from Pinterest and
Reddit — and images from messaging apps and websites arrive with their GPS
already stripped, before they ever reach the phone. So both screens will
likely show few or no pins here, and both now say so explicitly rather than
appearing broken. Photos taken with this phone's camera, with location on,
will appear.

## Known limits

- The photo editor saves at preview resolution, not full resolution.
- Crop is presets-only, no draggable box yet.
- "Autoplay videos" is stored but not yet wired to the player.
- Backup/restore covers settings, not the photos themselves.
