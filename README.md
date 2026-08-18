# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit cb5b081.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## Hyle Grotesk, everywhere

Space Grotesk is out. **Hyle Grotesk Classic** is in — and the two are closer
than the swap sounds: its own licence note says it's built on Space Grotesk
with Archivo letterforms substituted, so the metrics your mockups were drawn
against still hold. Classic rather than Plus, because Plus has the Deco sweep
N and R, and a swept N inside `4,822 of 12,366` is a flourish in a progress
readout.

Four weights, OFL 1.1, licence note shipped in the APK. Set on the whole
Material type scale, not just the styles in use today — a partly-overridden
typography leaks, and you find out when one stray component renders in Roboto.

## Your icons, and a zip that zips

The action bar carries your four SVGs, converted verbatim. **Three glyphs, no
fourth** — the bar is 209dp because it holds three, and the set you sent is
the set.

That left the overflow menu with nowhere to hang, which turned out better:
share, favourite, sensitive, archive, collections, tags, rename, restore,
delete and trash all moved into the **actions room** on the right edge, under
a `N SELECTED` heading. Rows in a room beat items in a dropdown — they carry
a line of explanation, they're reachable one-handed, and they don't vanish
when a finger slips.

**The zip glyph now zips.** It was pointing at share, which made the icon a
lie. Selected photos pack into one archive and go to the share sheet.
`java.util.zip` is in the JDK, so the offline build gains nothing to object
to. Entry names are deduplicated — a real library has `IMG_0001.jpg` in
several folders, and without that the archive throws partway through.

## Also in this build

The activity shade (three states, several simultaneous jobs, content that
never moves), the editor (14 adjustments, full-resolution saves), the swivel
facing the room, the share fix, hold-to-peek, the four-edge room model,
complete photo headers, and the placeable pin on a spinning map.
