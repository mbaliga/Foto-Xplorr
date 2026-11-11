# Batch 9 device test checklist

Every device-only claim from batch 9, grouped per feature: timeline rail and filmstrip, activity shade and photo stack,
People, text fields, maps, Settings and Services, models, cloud sync, Google Drive and Dropbox, CI. See
`docs/handoff/MASTER-PROGRESS.md` (Batch 9), `docs/adr/ADR-018-cloud-sync.md`, `docs/adr/ADR-019-nooz-model-catalogue.md`
and TRAPS #126 to #133. **Nothing in this batch has run on a device. Every line is unverified until you tick it.**
Roborazzi runs in record mode, so no screenshot test proves a look.

Suggested devices: a current phone (Android 15/16) with gesture navigation; one with three-button navigation; a low-end
phone (3 to 4 GB); a tablet. Check both flavours where a line says so. Prepare: a library of 10,000+ photos with real
faces; the three reference videos in the batch 9 feedback; a Nextcloud (or ownCloud) with an app password; a NAS on plain
http; an S3 bucket (B2, R2 or MinIO); a Google Cloud project and a Dropbox app (see ADR-018); a raster `.mbtiles`, a
`.pbf` one and a `.pmtiles` file; a 2 GB video.

## 1. Timeline rail and filmstrip
- [ ] **Arc vs the videos.** Touch the right edge: labels bend into an arc away from the finger, a bubble with the
      month sits level with it, the list jumps with the month, on release the rail eases straight and fades. Compare with
      the reference recordings. (unverified)
- [ ] **Thumb clearance.** No label sits under the thumb; right hand and left hand, small and large phone. (unverified)
- [ ] **Default is Only while scrubbing.** Nothing is drawn at rest and the grid does not reflow when the rail appears.
      Settings > Library / right room: "Timeline shows" Only while scrubbing / Always visible; Always visible reserves
      the gutter and shows the strip. An update from a build with the timeline on keeps Always visible. (unverified)
- [ ] **Grid is still usable with the rail hidden.** Tap, long-press and fling on tiles in the outermost column; none
      is swallowed. (unverified)
- [ ] **Back swipe near the rail edge.** With gesture navigation, a back swipe from the rail edge when idle and just
      after a scrub (system-gesture exclusion trade-off, TRAPS #127). Does the first scrub of a session start cleanly?
- [ ] **Edges.** Left, top and bottom rails bulge away from the finger too. (unverified)
- [ ] **Animations off.** With "Remove animations" the rail appears and snaps back instantly. (unverified)
- [ ] **TalkBack.** The rail offers previous / next month and "Scrub timeline" as actions; the month labels are not read
      one by one. (unverified)
- [ ] **Filmstrip.** Thumbnails lift away from the finger, the large preview above the finger is not clipped at the
      screen edge, and the strip does not jump sideways when you lift. (unverified)

## 2. Activity shade and photo stack
- [ ] **Corners.** With Recognising open, its text clears the rounded corner on the left and the timeline gutter on the
      right (count "120 of 19,951" fully visible), also in the top room. (unverified)
- [ ] **Slipped sheet.** Fillets and shadow make the grid under an open shade read as a slipped sheet; with the shade
      collapsed the corner radius is 0; the grid never moves. (unverified)
- [ ] **Stack.** Shows real recent photos, not a pink square. Drag to ruffle: feel, haptic tick, previous/next
      buttons, tap opens the top photo. With blur on, a sensitive photo is never sharp. With animations off it still
      works. (unverified)

## 3. People
- [ ] **Face crops** are framed on the face on cards; the status line is honest while indexing (not "done" early).
- [ ] **One-time re-read** of photos stored without face boxes after the update: how long, does the library stay usable.
- [ ] **Names, merge, hide, "not this person"** survive a restart and a re-index; a person's number does not shuffle.
- [ ] **Over-split.** The same real person split into two cards: merge fixes it for good. Note how often.
- [ ] **Person filter chip** filters the grid; removing it restores the grid; the pane clears the shade (nothing hidden
      under it).
- [ ] A locked or sensitive-hidden photo never appears in any person.

## 4. Text fields
- [ ] One field design in Settings, search, tags, rename, EXIF editor, decimal inputs: first characters never clip.
- [ ] The decimal field's plus/minus button works; comma-decimal locales (type 1,5); focus ring stays accent colour.
- [ ] TalkBack announces each field by its label; the clear button returns focus to the field.

## 5. Maps
- [ ] **Place section** of a photo's info shows the real world map, with land, borders and city labels legible; the pin
      is in the right place; pin drag works while the room scrolls.
- [ ] **Smoothness** of pan, pinch and double-tap at levels 0 to 8, on a low-end phone. Map taps arrive about 300 ms late.
- [ ] **Offline stamp map** framing on a stamp.
- [ ] **Import (Settings > Data > Maps).** A real raster `.mbtiles` (large; TMS row flip; jpg and webp tiles) draws and
      is zoomable; `.pbf` and `.pmtiles` give the refusal sentence; the import cannot exhaust memory (64 tiles, 24 MB).
- [ ] Offline flavour: no network at all while using any of this. Connect flavour: street map still works.

## 6. Settings and Services
- [ ] Ten tabs fit and scroll; every tab, section and row has an icon; selected tab is clear without colour.
- [ ] Services tab: AI keys, Models on this phone, Sync are all reachable; Experimental no longer lists AI keys.
- [ ] Hub header is not duplicated. Sync keeps running after Settings is closed.
- [ ] Replace a saved key: the new last four show. Delete all asks first. Only Test sends anything, and no photo.

## 7. Models
- [ ] Get on Wi-Fi starts at once; on mobile data it asks first, with the size. Cancel mid-download leaves nothing.
- [ ] Offline flavour: import a model file works; there is no Get.
- [ ] Connect: "Update catalogue" fetches, the cache survives a restart, a failed fetch keeps the bundled snapshot.
- [ ] LLM and image models show collapsed with Get disabled; no checksum is described as verified.

## 8. Cloud sync (folder, WebDAV, S3)
- [ ] **Starts only when you tap**; never on its own; Wi-Fi only is the default and holds on a metered hotspot.
- [ ] **Nextcloud** with an app password, a NAS on plain http (needs "allow plain http", local address only), folder
      layout and file dates on the server.
- [ ] **S3**: B2, R2, MinIO and AWS, path-style and virtual-host; a file over 2 GB goes by multipart; cancel mid-multipart
      aborts the upload on the server (no orphan parts).
- [ ] **Folder (SAF)** on Google Drive, OneDrive and a USB drive: copies appear, rename behaviour, the "still loading" case.
- [ ] **Never deletes or overwrites:** delete and edit a photo on the phone, run again; the server copy is untouched.
- [ ] **Resume** after airplane mode: the interrupted file is sent again from byte 0; nothing is marked done early.
- [ ] Foreground notification with Cancel; sync survives the app being backgrounded.
- [ ] **Protected photos.** A photo in a locked folder, marked sensitive, archived or trashed is never sent; one locked
      while a run is going is skipped.
- [ ] **Encrypted copies.** PBKDF2 key-derivation time (see `BackupKeys`) on a low-end phone; a `.fzs` file opens
      on another device with the passphrase; names and sizes are visible on the server.
- [ ] **GPS.** Plain copies: present or absent (the platform may strip it); encrypted copies carry it. Check both.
- [ ] Keystore-held credentials survive an app update; Offline flavour shows only the folder option.

## 9. Google Drive and Dropbox (Connect only)
- [ ] Real sign-in: Custom Tab and the fallback browser; return after the process was killed; Google accepts the redirect
      scheme `com.fotoxplorr.app.oauth` (constant `OAuthRedirect.SCHEME`) for the client type you registered.
- [ ] Refresh after an hour; 7-day token expiry while the consent screen is in Testing; revoked access gives a
      sign-in-again sentence; quota exceeded is reported.
- [ ] A 2 GB resumable upload on each; a hand-made "Fotoz" folder is invisible under `drive.file`.

## 10. CI
- [ ] The workflow's artifact `fotoz-CONNECT-arm64` downloads and installs next to the offline app.
