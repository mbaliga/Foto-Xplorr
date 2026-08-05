# Foto Xplorr — installable test build (2026-08-05, build 4)

Not for distribution. Debug-key signed, arm64-v8a only. Built from `0f756c4`.

**Direct download:** [`foto-xplorr.apk`](https://github.com/mbaliga/Foto-Xplorr/raw/apk-testbuild/foto-xplorr.apk)

Build 4 — the UX and fidelity pass:

- **Indexing no longer restarts.** Taking a screenshot used to send "Indexing 3456 of
  21526" back to zero. Scans now run incrementally off a persisted watermark, and a
  MediaStore change queues a follow-up pass instead of cancelling the running one.
- **The fonebrew navigation.** Destinations are the left room, settings the right —
  drag from an edge and the grid lifts and parts to reveal them. The hamburger, the app
  bar and the settings dialog are gone. The top edge is reserved; nothing claims the
  pull-down.
- **Niagara-style edge scrubber.** Glide a finger down the right edge to sweep the whole
  library, with a bubble naming the month under your thumb.
- **The crash screen looks and behaves properly** — the clipped mark and the empty
  button are fixed, buttons answer a press, and it arrives in Foto Xplorr's violet.
- Refresh is a shake. The placeholder "Notifications & Alerts appear here" copy is gone.

Same package as build 3 (`com.fotoxplorr.app.debug`), so it updates in place.
Delete this branch with `git push origin --delete apk-testbuild` when done.
