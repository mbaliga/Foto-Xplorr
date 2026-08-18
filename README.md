# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 18 Aug 2026 from commit d895063.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## The one that matters: the pane no longer vanishes

The centre pane was supposed to park with a strip of itself still on
screen, and instead disappeared completely. It was a real bug in the shared
shell, not a tuning problem.

The card was hinging its swivel on its **far** edge — the one already off
screen — while the grip pill was drawn on the **near** one. Two places
encoding the same fact, disagreeing. So the card swung its only visible
sliver out through the perspective divide, and the rotation signs were
inverted with it, swinging the free edge toward you instead of away.

This was invisible at 10° and unmissable at 22°, which is why raising the
tilt looked like it broke the swivel when it only exposed it.

All three consumers now read one `bandEdge()`. That also caught a smaller
error: a plain 0/1 transform origin hinges on the *box* edge, half a shrink
outside the card it is turning. And with the hinge correctly on the band
edge, that edge sits still through the whole rotation — so the band is a
full 72dp at any angle, and the slide and swivel styles park to identical
depth. It applies to all four edges, so the top and bottom rooms park
correctly too.

## Also in this build

1. **Top room (drag down from the top of a photo): two controls → seven.**
   Four of them — keep the screen awake, slideshow shuffle, loop
   animations, autoplay videos — were preferences that already existed and
   were already honoured, but had no control anywhere in the app to reach
   them. Filmstrip on/off is new. The room scrolls now.

2. **Bottom room (drag up): a new "In your library" block** — favourite,
   sensitive, in the trash, and which album it lives in. Everything else in
   that room is read off the file; this is what the library itself knows.
   "No location in this file" now says *why*, instead of leaving a bare
   negative that reads as a failure.

3. **One room language.** The left rail, the top room and the detail room
   were each using their own type sizes, and the top room used Material's
   switch — a rounded, elevated, rippling control in the middle of a black
   brutalist room. They now share one set of styles and a drawn toggle.

4. **Selection screen.** "N SELECTED" was bare white text over the mosaic
   and genuinely unreadable on bright photos; it now sits on the same
   ground as the other two clusters. All three moved 12dp → 16dp off the
   screen edge, because on gesture navigation the inset is a few pixels and
   they were reading as clipped. And the "Recognising…" banner no longer
   shares the top strip with the action cluster — on a 22k library those two
   were colliding for many minutes at a time.

## Known limits

- The photo editor saves at preview resolution, not full resolution.
- Crop is presets-only, no draggable box yet.
- Backup/restore covers settings, not the photos themselves.
- Both the map and the compass read GPS from inside your photos, and most
  of this library is Pinterest/Reddit saves with GPS already stripped — so
  expect few or no pins. Both screens say so rather than looking broken.
