# Device-test checklist — build 4 (FX-006) `[OWNER]`

**Why this gates WP2:** this build carries the navigation rework *plus* the three
reference-clip surfaces (viewer top room, notification room, rail icons), and none of it
has been on a phone. Migrating a catalogue whose UI is unverified means two unknowns
compounding. WP2 (schema/migration) does not start until this checklist has a signed
result.

**Build:** `offlineDebug`-equivalent debug APK from branch
`claude/fotoz-ui-interactions-bxvgbw` (or `main` after PR #8 merges). Every line below
says what to do, what to look for, and what failure looks like. Mark each ✅/❌ with a
note; screenshots where indicated.

## A. Rooms navigation (the rework)

- [ ] **A1 — Left room (rail).** Edge-swipe from the left. *Look for:* home card lifts,
  shrinks ~10%, gains rounded corners and a ring, parks right leaving a grab band; word
  wheel underneath with large words, fade-by-distance, **no scrollbar, no highlight box**.
  *Failure:* room slides over the top of home like a drawer; home stays full-size; a
  Material drawer appears.
- [ ] **A2 — Word wheel feel.** Slow-drag the wheel. *Look for:* words gain weight and
  brightness *continuously* as they approach the focus line — not a step-change on
  release; settle snaps to the nearest row with **no bounce**. *Failure:* highlight jumps
  row-to-row; spring/overshoot on release.
- [ ] **A3 — Marker icon.** *Look for:* the selected destination shows **its icon** in
  the gutter (Photos → photo-library glyph, etc.); no icon on any other row; on selection
  change the icon **travels** across intervening rows, dipping slightly in scale
  mid-flight, arriving a beat after the text settles. *Failure:* icons on every row;
  marker teleports; glyph visibly distorts/stretches.
- [ ] **A4 — Return affordances.** With a room open: tap the parked card → home returns;
  drag the card → tracks the finger 1:1 and is reversible mid-drag; Back button → home.
  *Failure:* taps land on controls underneath the parked card; drag is not reversible.
- [ ] **A5 — Right room (settings).** Same lift-and-park mechanics mirrored; settings
  render in-theme (dark), **no full-screen Material dialog**, no light-themed screen.
- [ ] **A6 — Corners stay honest.** A *downward* drag starting in the top-left corner
  must scroll the grid, not open the rail or stall. *(This pins the slop-race gesture
  claim.)*

## B. Viewer top room (PR #8 — never seen on a device)

- [ ] **B1 — Reveal.** Open any photo full-screen; pull down from the top edge. *Look
  for:* the room reveals with the photo shrinking as you pull — **the photo flies into
  the pin on the place plate**; the plate's grid resolves under it; text settles last.
  Mid-drag, stopping your finger freezes the flight exactly where it is; reversing the
  drag reverses it. *Failure:* room fades or slides in as a fixed layout; photo jumps
  between two sizes; releasing at half-open snaps with a bounce. **Screenshot mid-pull.**
- [ ] **B2 — Place plate honesty.** For a photo *with* GPS: coordinate line + "N m/km
  across" scale reads sensibly for where it was actually taken; for a photo *without*
  GPS (any screenshot): plate is absent, "No location in this file" shows. *Failure:* a
  pin at 0°,0° in the Atlantic; a plate on a screenshot.
- [ ] **B3 — No tile fetch.** Airplane mode ON, open the top room on a GPS photo. The
  plate must render identically (it is drawn locally). *Failure:* blank tiles, spinners,
  any network-error surface.
- [ ] **B4 — Old surfaces gone.** Viewer action row has **no "Info" and no "Details"**
  buttons; the bottom shows the filmstrip only.
- [ ] **B5 — Status bar.** Room content clears the status bar/notch; the pull-down that
  opens the room must **not** fight the system notification shade — a pull starting on
  the very top pixel may open the shade; a pull starting on app content must open the
  room.
- [ ] **B6 — EXIF present mid-pull.** Camera card is already populated during the pull
  (EXIF is pre-read), not blank-then-popping-in after the room lands.

## C. Notification room (PR #8 — never seen on a device)

- [ ] **C1 — Reveal shape.** Trigger a scan (Shake to refresh with new photos on
  device, or first launch). *Look for:* the **grid's frame recedes downward** — top
  corners rounding as it goes — uncovering the status line *behind* it; the line itself
  does not move or animate. Photos inside the grid keep their exact size (watch a tile
  edge). *Failure:* a banner slides down pushing content; grid contents scale/shrink;
  the notification appears **over** the grid.
- [ ] **C2 — Copy honesty.** During an incremental scan the line reads "Added N new
  items" / "Library up to date" — never a full-library re-count. **Screenshot mid-scan.**
- [ ] **C3 — Collapse.** ~2s after scan completes, the pane slides back up flush; no
  residual gap, no clipped first grid row afterwards.
- [ ] **C4 — Touch during reveal.** While the pane is receded, taps on photos still hit
  the photo they visually land on (the pane moves by placement offset, not translation —
  this is the claim under test).

## D. Edge timeline scrubber

- [ ] **D1 — Alignment.** Long library, fling to the middle, touch the right-edge strip.
  *Look for:* month label under the finger matches the grid content shown. *Failure:*
  label and content drift apart (the headerless-grid invariant broken).
- [ ] **D2 — Arbitration.** On the right edge: horizontal drag opens settings; vertical
  drag scrubs; tap does neither. All three must coexist.

## E. Crash recovery + protected content

- [ ] **E1 — Restyled crash screen.** Force a crash if a debug hook exists (or note
  n/a). *Look for:* the two-pane recovery UI in app theme; "Reset app data" only after
  the loop gate.
- [ ] **E2 — Protected viewer cleanup on ON_STOP.** Unlock a protected folder, open an
  item, press Home (do not just switch apps), return. *Look for:* relocked, content not
  in the recents thumbnail. *Failure:* protected image visible in recents or after
  return.

## F. Indexing under churn (the long one)

- [ ] **F1 — Long scan with churn.** On the 21k-item device: clear app data, first-run
  index while taking new photos mid-scan, backgrounding and returning twice. *Look for:*
  scan completes; new photos appear; **the banner never restarts a full re-count because
  a screenshot landed** (says "Added N", not "Indexing 21,526"); no duplicate items.
  **Screenshots mid-scan.**
- [ ] **F2 — Kill during scan.** Force-stop mid-index, relaunch. *Look for:* scan
  resumes or restarts cleanly; no corrupted/empty library; no crash loop.

## Sign-off

| Field | |
|---|---|
| Device / OS | |
| Build (branch + commit) | |
| Date | |
| Blocking defects (A–F ref) | |
| Verdict: WP2 may start? | yes / no |
