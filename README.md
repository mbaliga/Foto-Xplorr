# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 12 Aug 2026 from commit fdcd693 (fixes the top-edge/status-bar gesture
conflict and the missing inset padding found in the first test round).

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr.apk` | offline | `com.fotoxplorr.app.debug` | No INTERNET permission, no network library. Airplane-mode smoke test belongs here. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

## What changed since the last build

- **Pull-down for the viewer's top room no longer fights the OS notification
  shade.** The top-edge gesture zone now starts below the status bar instead
  of overlapping it, so it stops being a coin flip between the two.
- **The viewer's control bar and filmstrip clear both system bars.** The top
  row no longer renders partially behind the status bar; the bottom filmstrip
  no longer sits inside the swipe-up-for-home gesture zone (this was the
  "buggy strip scrolling").

Both were found from the crash report + screen recording from the first
round. Please re-run the same interactions: opening the viewer, pulling down
for the top room, scrubbing the filmstrip, and the trash-confirmation flow.

**Not addressed:** the native crash. The crash-recovery report's "Source"
field is the app's *install* source (Files by Google, from sideloading), not
a clue about the crash cause — Android's ApplicationExitInfo doesn't expose
a real native backtrace to the app. If it recurs, a logcat captured around
the crash (`adb logcat -b crash` or a full logcat spanning the crash
timestamp) would actually let this get debugged; the on-device report alone
has nothing more to give.
