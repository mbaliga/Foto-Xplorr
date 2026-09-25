# Foto Xplorr Android v1.0 — the release bar

These are the conditions under which the architect review (Claude, in the planning chat) will call a build **the first production version**. **Every line must be true, with the evidence named.** A green CI is necessary but never sufficient: this app handles irreplaceable files, and most of its risk only shows on a real phone with a real library.

## A. Scope complete

- [ ] Phases 0, 1, 1b and 2–7 of `MASTER-PLAN.md` done, as amended by ADR-009 … ADR-013.
- [ ] Every "deferred with reasoning" item in `MASTER-PROGRESS.md` is either done or explicitly moved to a numbered post-v1 list the owner has approved.
- [ ] No feature ships as a placeholder (competitor-parity "product rule").

## B. Data safety (zero tolerance)

- [ ] The catalogue switch (ADR-012) done on the owner's real library.
  - The Catalogue screen shows a clean verification.
  - One "Return to previous catalogue" round-trip, then the switch again, lost nothing.
  - `CLEANUP` has run, and the app worked for 7+ days afterwards.
- [ ] Every destructive path goes through trash and has passed its device check: delete, move, bulk rename, metadata write, overwrite, drive operations.
- [ ] A USB-yank test mid-copy, mid-move and mid-rename leaves no partial or lost files.
- [ ] A backup-to-drive manifest restores ratings, keywords and edits onto a fresh install.

## C. Privacy

- [ ] A share of each format family (JPEG, HEIC, PNG, WebP, RAW, video, motion photo) arrives with no location, checked with an external EXIF tool.
- [ ] The offline flavour has no network permission, no network library and no telemetry: the merged-manifest and classpath gates are green, and a network monitor on the device shows zero connections.
- [ ] Locked and sensitive content doesn't appear on any surface: map, search, widgets, share targets, picker, recents, memories.

## D. Performance (MASTER-PLAN §2.4, measured on device with Macrobenchmark)

- [ ] Measured on the owner's RedMagic **and** one mid-range phone, with a 100k-item library: cold start, scroll, scrubber jump, open photo, deep zoom, token search, semantic search.
- [ ] No ANR, and no out-of-memory in a 2-hour soak: scroll, view, edit, share, background indexing.

## E. Format coverage

- [ ] The format corpus runs on device: every format in MASTER-PLAN §4 decodes, or shows its documented placeholder. A black tile anywhere is a failure.

## F. Quality and release engineering

- [ ] The crash-free rate in owner testing is ≥ 99.5% of sessions over 2 weeks, from crash-recovery captures.
- [ ] TalkBack works through the core journeys: browse, view, search, share, delete, restore.
- [ ] Release signing with a real key; reproducible build; F-Droid/IzzyOnDroid metadata; a changelog; a licence audit (`DEPENDENCIES.md`, `MODEL_LICENSES.md`) clean.
- [ ] A second person, not the owner, completes the core journeys unaided (an ease-of-use sanity check).

## Where v1 is expected to stand against the field (for the owner's positioning)

- **Against Linux phone galleries** (lomiri-gallery-app, Imaginario, KDE Photos, Maui Pix, Loupe, FuriOS Gallery): ahead on every axis once this bar is met. No Linux phone app has on-device faces, semantic search, duplicates, XMP round-trip, bulk rename or removable-drive offline previews.
- **Against Linux desktop tools:** comparable with Shotwell and gThumb, ahead on search, AI and ease of use. **digiKam stays deeper** in raw metadata tooling, face workflows and batch processing, and **darktable** in RAW development. Foto Xplorr's claim against them is ease of use, phone-first design, and one library across phone and desktop, not depth.
- **Against Android galleries:** parity-plus with Aves/Fossify on formats and privacy, plus capabilities they lack (semantic search, drive sources, rule rename, culling). Against Google Photos and Samsung Gallery: parity on local features, with a stronger privacy position and no cloud dependency.
