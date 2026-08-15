# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 15 Aug 2026 from commit 491175b.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## What's new in this build

1. **AI provider dialog restyled.** It was a plain Material `AlertDialog`,
   which follows the app's Light/System theme setting -- genuinely light in
   those modes, which is what made it look out of place in an otherwise
   all-black app. Rebuilt as a hand-styled dark card, same as every other
   room in the app.

2. **"AI and similarity" simplified.** Leads with a plain sentence about what
   each section does; device specs, model hash/size, and the literal HTTP
   request a test sends are now behind "Show technical details" /
   "Show exactly what gets sent" instead of on the page by default. "Add a
   service" now shows plain names (ChatGPT, Claude, Gemini) instead of API
   shape names.

3. **Filmstrip moved back onto the photo itself** (reversing where it lived
   in the previous build) -- it was in the bottom details room; now it's an
   overlay over the open photo, same as the chrome. Two new things: a fixed
   frame stays at the strip's centre while the strip scrolls beneath it, and
   thumbnails now magnify continuously as they near that centre (a loupe,
   like macOS Dock zoom) rather than only the selected one snapping larger.
   Fling physics unchanged -- it already used real velocity-based
   deceleration.

4. **Settings moved under the nav rail.** No longer only reachable by
   knowing to swipe from the right edge -- there's now a visible "Settings"
   row beneath the destination list in the left rail.

## Known limits (unchanged from last build)

- The photo editor saves at preview resolution, not full resolution.
- Crop is presets-only, no draggable box yet.
- "Autoplay videos" is stored but not yet wired to the player.
