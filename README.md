# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 14 Aug 2026 from commit b900516.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## New since the last build

**#9 — the swivel.** Open the left rail (or the viewer's top room) and the
centre pane now turns about its hinge edge as well as shrinking, instead of
only sliding. It is a *option* in the shared library, not a new hardcoded
motion: apps pick `ParkStyle.SLIDE` or `ParkStyle.SWIVEL`, both with the
shrink, so nothing else in the constellation changed.

**#8 — the rail.** The selection-marker icon is gone. The three destination
covers moved from the right of the word to the **left gutter**, and because
the marker slot is pinned to its row and animates on a longer curve than the
wheel, they now *travel* across the intervening rows and dip in scale
mid-flight when you change destination — the movement dance from the
reference video. `HyleDestination` no longer carries an icon at all.

**Also fixed along the way:** the grab-pill on the parked card was aligned to
the wrong edge on the vertical axis — with the top room open it was being
drawn about 2000px off screen, so the affordance was simply never there. Both
vertical cases were inverted.

## Still from the previous build (worth re-checking)

The three stutter feedback loops and the image-loader configuration. Judge
performance on the **second** launch — the first still runs a full scan and
recognition pass.

## Not in this build yet

Items 2, 3, 4, 7, 10, 11, 12 — viewer rooms, timeline overlap, immersive
chrome, settings tabs, photo editing, gestures, filmstrip.
