# The fonebrew navigation pattern — constellation reference

> Owner direction (2026-08-05): *"I expected the navigation pattern of fonebrew to be followed
> everywhere."* This file is the written form of that pattern, distilled from the owner's two
> reference videos, the fonebrew (Android-IDE-core "Workbench") rooms implementation, and the
> correction round on the first Foto Xplorr / Fylz test builds. It exists so no future build
> session re-derives the pattern from memory — or worse, ships hamburgers and bottom nav bars
> again.

## What fonebrew navigation IS

**Rooms, not screens.** The app is a small set of rooms. Moving between rooms is the primary
navigation act and it happens **on the content**, not through chrome.

**The rail is a word-wheel** (reference video 2 — the scrolling word list):

- Room names are large text in a vertical run. The **focused** room is the brightest and
  heaviest; neighbours dim and lighten **progressively with distance** — the fade IS the
  scroll position indicator, so no scrollbar, no highlight box, no selected-item background.
- A small **morphing bullet** rides beside the focused row (dot → dash → dot-trail as focus
  moves). It travels *between* rows during the transition rather than teleporting: motion
  carries the state change (Hyle: state is shown, not said).
- Scrolling the rail feels like turning a wheel: rows approach the focus line, gain weight,
  pass it, lose weight. Settle snaps the nearest row to focus.

**The top room** (reference video 1 — the fluid top-bar reveal):

- Pulling down from the top of any room reveals a space ABOVE it — the top room. The reveal
  is a **fluid melt** (the bar/notch area stretches and flows open around the pull, like the
  video's island morphing around focus), never a rectangular slide or a fade.
- **Because the pull-down owns that space, no other gesture may claim pull-down.** This is
  why pull-to-refresh / pull-to-backup are banned everywhere in the constellation.
- The top room is NOT yet required in any app (owner, 2026-08-05). What is required now is
  keeping its gesture unclaimed and its copy off the screen — no "PULL TO …" static text.

**Refresh is a shake**, not a pull (`ShakeToRefresh`, now in `dev.aarso:cell-shell`). A
deliberate physical gesture that needs no affordance copy and competes with no scroll.

## Where the pattern lives

`dev.aarso:cell-shell`, in Shared-Libraries-asoc. **One implementation, not one per app** —
"followed everywhere" is a claim about consistency, and two apps each deriving their own
version of the same gesture is exactly how consistency drifts. It ships `SpatialShell` +
`SpatialController` (the rooms and their motion), `WordWheelRail`, `EdgeTimelineScrubber` and
`ShakeToRefresh`. Its motion constants are ported verbatim from Android-IDE-core's
`ui/spatial/SpatialRoot.kt` and are documented there as a **contract, not defaults**: an app
that tunes them has broken the thing the module exists to guarantee.

## What fonebrew navigation is NOT

These are the things the first test builds got wrong. Do not reintroduce them:

- **No hamburger menu** as the primary way into navigation. The rail is reachable by edge
  swipe and lives *over the content*; a small header affordance may open it but must not be
  a `Menu` icon opening a drawer-style list.
- **No bottom `NavigationBar` / tab bar.** (Fylz shipped "Files / Recovery" tabs — wrong.)
- **No slide-over panels either.** A slide-over covers the screen you were on; the owner was
  explicit that the current view should *move a bit and then swivel away*. Foto Xplorr's
  `SlideInPanel` was the first attempt at this and was wrong; `SpatialShell` replaced it.
- **No full-screen Material settings dialogs.** Settings are the right-hand room, rendered in
  the app's own theme. A modal window floating over a room is two contradictory ideas of where
  you are — and it is how Fylz ended up with a light-themed screen inside a black app.
- **No static instructional copy in gesture spaces** ("PULL TO CREATE BACKUP"). If a gesture
  needs a permanent caption, the gesture is wrong.

## Where each app stands (2026-08-05)

| App | Has | Needs |
|---|---|---|
| fonebrew (IDE-core) | rooms implementation (`ui/rooms/`) | is the reference |
| Foto Xplorr | **adopted.** Rail = left room, settings = right room, top reserved. Hamburger, app bar, `SlideInPanel` and the settings dialog all retired. Date-keyed edge scrubber over the grid | the top room itself, when there is something to put in it |
| Fylz | **adopted.** Locations = left room, Tools + settings = right room, Recovery = bottom room. Bottom tabs, workspace chips and the light Tools screen retired. Sort-keyed edge scrubber | as above |
| csapp / assay | standard Material consoles | pattern adoption once the two testable apps validate it |

## Motion notes for the implementer

- The settle is `tween(320ms, CubicBezierEasing(0.4f, 0f, 0.2f, 1f))` and **has no spring**.
  The reference implementation is emphatic about this: a room is a place you arrive at, not
  something that bounces into position. Pinned by `SpatialMotionTest`.
- Rail focus: text weight/alpha interpolate with the row's distance to the focus line
  *continuously during drag* — not at settle. The bullet travels between rows.
- The reveal melt: drive a corner/edge distortion from pull distance; the surface being
  revealed scales from ~0.97 and un-blurs. Nothing fades in from nothing — material flows.
- Every transition must remain interruptible mid-flight (drag reversal inverts it).
