# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 12 Aug 2026 from commit f8a0991.

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr.apk` | offline | `com.fotoxplorr.app.debug` | No INTERNET permission, no network library. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

## What changed since the last build

- **Launcher icon replaced** with an abstract violet card-fan mark (flat,
  no rendered photo content), swapped in after review found the photoreal
  Polaroid-stack renders didn't hold up: didn't match the app's own flat
  visual language, lost all detail at real launcher sizes, and depicted a
  specific identifiable person on every install. This new mark is legible
  even at 48px.

Reinstalling over the previous debug build should update the icon in place.
