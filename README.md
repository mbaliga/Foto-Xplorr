# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 15 Aug 2026 from commit 96e59b6. **All twelve items are in this build.**

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

Judge performance on the **second** launch — the first still runs a full scan
and recognition pass.

## What changed, by your numbering

1 & 5 — **Stutter.** Three feedback loops were making the app re-derive the
whole 22,110-photo catalogue for reasons unrelated to scrolling: recognition
published progress once per photo (and progress is part of gallery state), the
gallery re-derived the catalogue on every recomposition, and recognition was
keyed on the photo count so every scan batch cancelled and restarted it. Also:
the catalogue was fully re-sorted on every scan batch, and the app had no
image-loader configuration at all.

2 — **Viewer.** The text overlay is gone. Actions are icons in the RIGHT room,
settings in the TOP room, details in the BOTTOM room. The details room no
longer ends in a gallery grid; the place plate is last and takes the height.

3 — **Timeline.** Invisible at rest, fades in while you scroll, out when the
grid settles. Still grabbable from a standstill.

4 — **Immersive.** No title, no 3-dot menu. The root grid draws photos and the
floating pill, nothing else. A back bar appears only inside an album.

6 — **Warning triangle.** Gone unless something is actually wrong. It was
literally the resting state before. Tapping a message expands it.

7 — **Settings.** Six tabs, one depth. "All settings…" is gone. Three new
preferences: keep screen on, shuffle slideshows, autoplay videos.

8 — **Rail.** Icon dropped. The three covers moved to the left gutter and now
travel between rows with the marker's lag and scale-dip.

9 — **Swivel.** The parked card turns about its hinge edge as well as
shrinking. It is an option in the shared library (SLIDE / SWIVEL), so other
apps are unaffected.

10 — **Editing.** Snapseed is NOT open source (proprietary Google). uCrop
would fail your offline build gate; Fossify is GPL-3. So: a small
non-destructive editor written in-house, zero new dependencies. Crop presets,
rotate, flip, brightness/contrast/saturation/warmth. Always saves a COPY.

11 — **Gestures.** Swipe-to-page could not fire before — it was unreachable
code. Now: one finger pages, one finger zoomed pans, two fingers zoom and
rotate. Two-finger rotate is new.

12 — **Filmstrip.** Current photo is genuinely centred now, and dragging the
strip navigates.

## Known limits (deliberate, not oversights)

- The editor saves at **preview resolution**, not full resolution. Next step.
- The crop tool offers presets only; no draggable crop box yet.
- "Autoplay videos" is stored and shown but not yet wired to the player.
- A right-edge swipe now opens the actions room instead of paging — the shell
  claims edge bands first. Tell me if that feels wrong in the hand.
