# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 14 Aug 2026 from commit c3fde05.

| File | Flavor | Installs as |
|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` |

## What to look for in this build

**The stutter (your #5).** Three separate feedback loops were making the app
re-derive your entire 22,110-photo catalogue over and over for reasons that
had nothing to do with scrolling:

1. Face/object recognition published progress **once per photo**, and progress
   is part of the gallery's state — so every photo recognised re-filtered and
   re-sorted all 22,110. Now throttled to 4 updates/second.
2. The gallery re-derived the whole catalogue on **every** recomposition,
   including from those progress ticks. Now memoised on only the inputs it
   actually reads.
3. Recognition was keyed on the photo *count*, so every scan batch cancelled
   and restarted it — it could never finish during a scan, and threw away its
   work each time. Now starts once the scan settles.

Plus: the catalogue was rebuilt and fully re-sorted on every scan batch
(quadratic across a scan) — now a linear merge; the app had **no image-loader
configuration at all**, so thumbnails were re-decoded from scratch constantly
— now a 30% memory cache and a 256 MB thumbnail disk cache; and scan batches
went 64 → 512, cutting scan-time interruptions 8x.

The first launch after installing will still do a full scan and recognition
pass. **Judge it on the second launch**, and on scrolling once the library
has settled.

**The warning triangle (your #6).** It should now be gone entirely when
nothing is wrong. It was literally the resting state: idle returned an empty
message but drew the red triangle anyway. When there IS something to report
you get a sentence, and tapping it expands a cut-off message.

## Not in this build yet

Items 2, 3, 4, 7, 8, 9, 10, 11, 12 — the immersive chrome, viewer rooms,
timeline overlap, filmstrip, gestures, nav, settings tabs, swivel and photo
editing. Those are sequenced next.
