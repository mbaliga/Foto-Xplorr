# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 12 Aug 2026 from commit 344c506.

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr.apk` | offline | `com.fotoxplorr.app.debug` | No INTERNET permission, no network library. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

## What changed since the last build

- **The app now has a launcher icon.** It had none before — no `android:icon`
  in the manifest, every install used the bare OS default. Now it's the
  stack-of-photos artwork on solid black, adaptive-icon format (this app's
  minSdk is 26, exactly where that format was introduced, so there's no
  legacy fallback icon to keep in sync).
- Carries the top-edge gesture fix and the viewer inset fixes from the
  previous round (pull-down for the top room, filmstrip scrubbing).

Reinstalling over the previous debug build should just update the icon in
place. If your launcher caches the old (default) icon, a long-press →
app info → or a reinstall clears it.
