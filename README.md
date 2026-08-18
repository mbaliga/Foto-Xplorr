# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit a988518.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## The editor is real now

Fourteen adjustments in three groups, ordered the way you'd actually work:

- **Light** — exposure (in stops, ±2), contrast, highlights, shadows,
  whites, blacks
- **Colour** — temperature, tint, vibrance, saturation
- **Detail** — sharpen, clarity, vignette

Every slider has a one-tap reset next to its name.

**Saving is full-resolution.** It previously saved the *preview* bitmap,
capped at 2048px — so every edit you'd saved was a downscaled copy. Fixed.

**Save asks you**, and remembers if you tick "do this every time". One
honest caveat: *Replace the original* is offered but not wired up — it needs
a per-file write grant this build doesn't request. Choosing it saves a copy
and says so, rather than claiming a replacement that didn't happen.

The engine underneath is 40 JVM tests, and several found real bugs while
being written. What they pin is the class of error you only see as a
photograph that came out subtly wrong: exposure applied in gamma space
instead of linear light (a stop would clip mid-grey to white), contrast as a
linear scale (flattens both ends of the histogram), tone regions with a hard
cutoff (a visible seam across every sky), desaturation by flat average
instead of luma (green goes near-black), and tone curves on a natural spline
(overshoots between control points — haloed edges and inverted patches).

## Also fixed

**The swivel faces the room** instead of turning away from it.

**Sharing worked at all** — it threw on every attempt. The share folder was
renamed while the FileProvider config still declared the old name.

## Still to come on the editor

- Curve editor UI (the engine has per-channel curves; nothing draws them yet)
- Draggable crop box (presets only for now)
- Layers, on top of this engine
- Replace-the-original write grant

## Known limits

- Both map and compass read GPS from inside your photos; most of this
  library is Pinterest/Reddit saves with GPS stripped. A photo with none now
  gets a spinning map and a pin you can place by hand.
