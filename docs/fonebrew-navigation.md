# The fonebrew navigation pattern — constellation reference

> Owner direction (2026-08-05): *"I expected the navigation pattern of fonebrew to be followed
> everywhere."* This file is the written form of that pattern, distilled from the owner's two
> reference videos, the fonebrew (Android-IDE-core "Workbench") rooms implementation, and the
> correction round on the first Fotoz / Fylz test builds. It exists so no future build
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
- *(Revised 2026-10-21, owner #5.)* Turning the wheel *previews*: the square tracks the focus line
  live, and the word that rests on it for about 140 ms while the finger is down is committed (the
  pane behind follows it). A fling commits once, where it lands. **Release only settles; it never
  selects and never closes the room.** The room closes by tapping the selected word, tapping the
  parked card, or Back (a selection callback must not also close the room: TRAPS #61). The pinned
  rail on a wide window has no room to close.

**The top room** (reference video 1 — the fluid top-bar reveal):

- Pulling down from the top of any room reveals a space ABOVE it — the top room. The reveal
  is a **fluid melt** (the bar/notch area stretches and flows open around the pull, like the
  video's island morphing around focus), never a rectangular slide or a fade.
- **Because the pull-down owns that space, no other gesture may claim pull-down.** This is
  why pull-to-refresh / pull-to-backup are banned everywhere in the constellation.
- The top room was NOT required in any app at first (owner, 2026-08-05); what was required was
  keeping its gesture unclaimed and its copy off the screen — no "PULL TO …" static text. That
  held until it was earned: see the 2026-08-09 note in the next bullet and the geography table. It is now the
  **details** room, in the gallery as well as the viewer.
- Fotoz built the first one (2026-08-09), after the owner's third reference video showed
  what belongs in it: a photo's own facts, and a plate of *where it was taken* that the photo
  itself flies into. The flight is the argument for the room — the same content on a pushed
  screen would just be a form. The video's ordering is the one to copy, and the implementation
  does: the photo departs first, the ground resolves under it, the text settles last.

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
  explicit that the current view should *move a bit and then swivel away*. Fotoz's
  `SlideInPanel` was the first attempt at this and was wrong; `SpatialShell` replaced it.
- **No full-screen Material settings dialogs.** Settings are rendered in the app's own theme,
  never a modal window floating over a room — two contradictory ideas of where you are, which is
  how Fylz ended up with a light-themed screen inside a black app. *(Revised 2026-10-21: in Fotoz
  Settings is no longer the right-hand room. It is a page that takes over the place the photos
  are, in the same scaffold as Albums, Calendar, Discover, Library and Tidy up: black ground, white
  ink, a back chevron, 20 dp gutters. The right-hand room is now view options.)*
- **No static instructional copy in gesture spaces** ("PULL TO CREATE BACKUP"). If a gesture
  needs a permanent caption, the gesture is wrong.

## Fotoz's rooms: one geography (2026-10-21)

Owner direction (batch 2, #26): the same four rooms on the same four edges in the gallery and the
viewer. The code's single table is `ui/RoomGeography.kt`.

| Edge | Holds | Gallery | Viewer |
|---|---|---|---|
| Left | where you can go | the destination rail (nine words) with a Settings row | none |
| Top | details | the view's counts and the library (`GalleryInfoRoom`) | the photo's facts and place plate (`PhotoDetailRoom`) |
| Bottom | actions | a short pull shows a strip of quick actions, a longer one every action | the same two stages, for the open photo |
| Right | view options | columns, sort, what to show | filmstrip, slideshow, autoplay, transparent-photo background, pinch panels |

The reasoning: view options sit at the side because you want to watch their effect, and an
action does not need the content in view, so tilting the content up from under is the better
motion for something you do once and leave. This replaces the earlier tables, in which the
gallery had settings on top and the viewer had them at the bottom. Nothing may claim the
pull-down for anything else, and the top 56 dp of the root grid is still handed to the
notification shade (`topReserve`). Settings is **not** a room (above).

## Where each app stands (2026-08-05)

| App | Has | Needs |
|---|---|---|
| fonebrew (IDE-core) | rooms implementation (`ui/rooms/`) | is the reference |
| Fotoz | **adopted.** Rail = left room. Hamburger, app bar, `SlideInPanel` and the settings dialog all retired. Date-keyed edge scrubber over the grid. **Top room built, in the viewer**: `PhotoDetailRoom` — the file's facts and a place plate the photo flies into. The `ImageDetailScreen` Material screen and the bottom `MetadataPanel` it duplicated are both retired. *As of 2026-10-21*: the top room is also on the grid (`GalleryInfoRoom`), actions are the bottom room (two-stage), view options the right room, and Settings a page. See "Fotoz's rooms" above | nothing outstanding; the two-stage reveal and its tuning live in `shared-libraries/cell-shell` |
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
