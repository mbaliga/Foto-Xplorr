# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 12 Aug 2026 from commit 18569bb.

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr.apk` | offline | `com.fotoxplorr.app.debug` | No INTERNET permission, no network library. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

## What changed since the last build

- **Launcher icon artwork swapped** to the owner's chosen top photo (beach/
  rocks scene, replacing the placeholder mother/daughter shot). Same
  stack-of-Polaroids-on-black composition and safe-zone positioning.

Reinstalling over the previous debug build should update the icon in place.
