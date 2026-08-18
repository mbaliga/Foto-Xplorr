# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit d84b38c.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## The activity shade

**The content no longer moves.** That was the real bug in the old one: it
translated the grid downwards by the banner's height, so every scan starting
or finishing nudged 22,000 photographs down the screen and back. The shade is
now drawn *over* the content — growing it covers more of the grid, nothing is
displaced, and the top row of tiles ends up half-hidden behind it exactly as
your mockups show.

Three states, drag down to open and up to close (tap cycles too):

- **Collapsed** — a 14dp sliver, one coloured bar per running job, no words.
- **Notification** — a 50dp row per job: turning glyph, name, `4,822 of
  12,366` with the count solid and the total muted.
- **Expanded** — a 250dp hero for the first job (fanned plates, name, count,
  full-width bar), with any others listed compactly beneath.

**Several jobs at once.** The old model literally could not represent this —
it described "what the app is doing" as one string, so a move running during
a rescan during a recognition pass showed one and hid two. Colour is per
*kind*, which is what makes the collapsed strip readable: three slivers tell
you which three things are running only if green always means the same thing.

Today the app registers two real jobs (library scan, recognition). Moves,
copies, backups and exports slot into the same list — the type is ready, the
call sites aren't wired yet.

## Selection chrome, to your mockup

Top-left bar 209×51 square-cornered with your shadow and 46dp glyph pitch;
bottom-left pill with the count at 28sp and `SELECTED` at 20sp/50%; the trash
square-cornered and flush into the bottom-right corner, separated by a gap.

Two deviations, both recorded in code: the trash is 96dp not 134 (at 134 it
would overlap the pill, and your screenshot shows a gap), and Space Grotesk
isn't bundled so the sizes are yours and the face is the platform's.

Your bar shows zip / move / copy. There's no zip action in the app, so the
slots are copy / move / share plus an overflow. Ask and I'll build a real
export-as-zip.

## Everything else from this round

The editor (14 adjustments, full-resolution saves, save-mode choice), the
swivel facing the room, the share fix, hold-to-peek, the four-edge room
model, complete photo headers, and the placeable pin on a spinning map.
