# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 12 Aug 2026 from commit 3de320c (WP0+WP1 on top of PR #8).

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr.apk` | offline | `com.fotoxplorr.app.debug` | The app's identity: no INTERNET permission, no network library. Start device testing here — `docs/device-test/build-4-checklist.md`. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

Airplane-mode smoke test belongs to the offline APK (WP1 exit gate, `[OWNER]`).
