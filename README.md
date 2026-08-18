# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit 901cef7.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## Selection chrome, built to your mockup

Measured from the CSS you sent rather than interpreted.

- **Top left** — black bar bled into the corner, 209×51, square-cornered,
  with your 0/4/8 shadow. Three 30dp white glyphs on a 46dp pitch starting
  44dp in. That leading run of bare black is in your design and is what makes
  the bar read as a cut-out rather than a toolbar.
- **Bottom left** — 44dp pill from the screen edge across three quarters of
  the width, 16dp radius. Count at 28sp, `SELECTED` at 20sp uppercase and
  half opacity, then the long empty run of black, then the dismiss near its
  right end.
- **Bottom right** — the trash: black, **square-cornered**, flush into the
  corner, separated from the pill by a gap of bare photograph.

The differing shapes are load-bearing: the pill is rounded and the trash is
not, and that is the only signal distinguishing "how many" from "destroy
them". It survives being glanced at.

### Two deliberate deviations, both recorded in the code

- The trash is **96dp** wide, not the CSS's 134. At 134 starting at x=301 it
  would overlap the pill, and your screenshot plainly shows a gap.
- **Space Grotesk is not bundled**, so the sizes are yours and the typeface is
  the platform's. Say the word and I'll add the font (it's OFL-licensed).

### One thing I had to choose

Your bar shows zip / move / copy. There is no zip or archive-export action in
the app at all, so the three slots are **copy to folder**, **move to folder**,
**share**, plus an overflow for everything else. Tell me if you want a real
"export as zip" action and I'll build it.

## Also in this build

The editor from the previous build (14 adjustments, full-resolution saves,
save-mode choice), the swivel facing the room, the share fix, hold-to-peek,
the four-edge room model, complete photo headers, and the placeable pin on a
spinning map.
