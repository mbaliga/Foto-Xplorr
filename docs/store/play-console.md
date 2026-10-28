# Fotoz — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`.

| | |
|---|---|
| applicationId | `com.fotoxplorr.app` (the **offline** flavor — no `INTERNET` permission, no network library on the classpath, enforced at build time) |
| Version at time of writing | `0.4.0-pro-media` (versionCode `4`) in `app/build.gradle.kts`. The next release's notes are `fastlane/.../changelogs/5.txt`, so the release must bump versionCode to `5` (and the versionName) before upload. |
| Category | **Photography** (the app is now a full photo/video editor and metadata tool, not just a browser — Tools remains defensible if the reviewer prefers it) |
| Tags | gallery, photo editor, video editor, offline, local first, privacy |
| Contact email | `fotoxplorr@asystemofcells.com` |
| Website | `https://asystemofcells.com/fotoxplorr` |
| Privacy policy | `https://asystemofcells.com/fotoxplorr/privacy` |

> **Name settled: Fotoz.** The store title is "Fotoz: Gallery" (`fastlane/.../title.txt`),
> and the listing text and privacy policy say Fotoz. The app was called Foto Xplorr until
> October 2026; the repository name, the `com.fotoxplorr.app` applicationId, the contact
> address and the hosted policy's URL deliberately keep the old name, because changing
> any of them would orphan installs or links. The full description says "formerly Foto
> Xplorr" once so existing users can find it. (Some branch names elsewhere spell it "Foto
> Xlorr" — typos, not a name.)
>
> **Two flavors, one listing.** This listing is for the **offline** flavor: no
> network permission at all, nothing in Data safety to disclose. A separate
> `connect` build (`com.fotoxplorr.app.connect`) adds an opt-in, bring-your-own-key
> AI feature and a live map, and would need its own listing (or its own Data
> safety answers) if it is ever published — do not submit its APK under this
> listing's answers.

## Deltas from the house defaults

### Photo and video permissions — the strictest part of Play policy
Photo and video access is one of the most tightly policed permission families on
Play, and the rules changed recently. Get this right first. **This release removes
audio entirely**: `READ_MEDIA_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and the
media-playback service are gone from the manifest, so the permission declaration and
the foreground-service declaration must be updated (see below) and the old audio
answers must not be carried over from a previous submission.

The table is the offline flavor's **merged** manifest (`app/build/intermediates/
merged_manifests/offlineDebug/...`), checked against `app/src/main/AndroidManifest.xml`
on 21 Oct 2026 (the `CAMERA` row and its three optional camera features were added on 28 Oct 2026 and checked against the merged offlineDebug manifest then: no audio permission, no `INTERNET`). Re-check it against a fresh merged manifest before each submission.

| Permission | Why | Notes |
|---|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | The product: a gallery, editor and player for the user's own photos and videos. | **A gallery/editor is an eligible core use.** Play requires broad media access to be the app's core function, and here it plainly is. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Supports Android 14+ partial access, with an in-app "manage selection" affordance. | **Keep it.** |
| `READ_EXTERNAL_STORAGE` | Legacy path. | Carries `android:maxSdkVersion="32"`. |
| `WRITE_EXTERNAL_STORAGE` | Legacy path for saving an edit/conversion/metadata change. | Carries `android:maxSdkVersion="28"` — Android 9 and below only; every write on newer Android goes through per-file consent instead. |
| `ACCESS_MEDIA_LOCATION` | Reads GPS embedded in the user's own photos, which Android 10+ otherwise redacts. | This, not `ACCESS_FINE_LOCATION`, is what the metadata/map features actually need — see below. |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | A real feature now: an optional on-device compass view orients using the device's current position while that screen is open. | Declared and used; disclose in Data safety as "approximate/precise location, used for App functionality, not shared." |
| `CAMERA` | The optional Chroma ID tool reads a surface's colour from two small camera pictures (torch off, torch on), asked when the tool is opened and used only while it is on screen. Pictures stay in memory and are erased; nothing is saved or sent (ADR-017). | A regular runtime permission, no special declaration form. Declared with `camera.any`, `camera` and `camera.autofocus` all `required="false"`, so Play does not hide Fotoz from a phone with no camera. **No** microphone or audio permission: the reel maker's sound file comes from the system document picker. |
| `POST_NOTIFICATIONS` | The progress notification for a conversion, export or copy/move. | Requested once on Android 13+, the first time such a job runs (not at launch, and not for any audio feature). |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` | `JobForegroundService` keeps a conversion/export/copy job alive and cancellable when the app is backgrounded. | Declared service types are `dataSync\|mediaProcessing` only. **The foreground-service declaration form must list data sync and media processing, and no longer media playback.** |
| `WAKE_LOCK` | Contributed by the Media3 ExoPlayer library's own manifest, not requested by the app (no app code takes a wake lock; the screen stays on during playback through a window flag). | Standard for a video player. It is in the merged manifest, so the policy lists it. |
| `RECEIVE_BOOT_COMPLETED` | Lets a scheduled background organisation job survive a reboot. | Declared for this alone; no boot receiver runs other code. |

The merged manifest also carries a build-internal signature permission
(`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, from AndroidX) that is not a
user-facing permission and needs no declaration.

### Data safety
**No data collected. No data shared**, except the on-device use of location for the
compass view, which Play's Data safety form still asks about even though nothing
leaves the device.

| Question | Answer |
|---|---|
| Collect or share any user data? | **No** ("Photos and videos" and "Location" are used but not collected/transmitted — see below) |
| Location used but not collected? | Yes — approximate/precise location, App functionality only, not shared, user cannot request deletion because nothing is retained |
| Encrypted in transit? | N/A — no network permission |
| Deletion? | Users can delete data in the app (uninstall removes everything the app stored) |

Batch 8 added the camera (Chroma ID) and a picked sound file (reel maker). Neither changes the
answers above: camera pictures are held in memory and erased, the colour history and the sound file
stay on the device, so the form still reads **no data collected, no data shared**. Do not tick
"Photos and videos", "Audio files" or "Audio" as collected; if the form asks about hardware, the
camera is used by one optional tool and the microphone is not used. Full wording: ADR-017.

Claims that must stay true for this build: photos and videos are never uploaded;
no remote indexing; similarity, face/pet/identity recognition, and metadata writes
are all computed and applied locally; a photo the app hides is never opened for another app; the map downloads no tiles; there is no AI
feature in this build at all (that is the `connect` build's story, not this one's).

### Content rating
- Category `Utility, Productivity, Communication, or Other`.
- **"Does the app contain user-generated content?"** → No in the store sense: it
  displays, organises and edits the user's own local media and shares nothing to
  any service.
- Everything else No. Expected **Everyone**.

> The app has a "sensitive media" flag and smart album, and a spot-heal/editing
> toolset. These are local organisation/editing features, not a content category
> the app ships, and do not change any rating answer. Do not screenshot the
> sensitive-media album with real content.

### Large screens
Ship `tenInchScreenshots/`. The adjustable grid (two to seven columns) and the
editor's tool rail are both a tablet argument, and the app supports mouse/keyboard
on ChromeOS.

## F-Droid
- The licence is **PolyForm Noncommercial 1.0.0** (`LICENSE`): source-available and free
  for noncommercial use, **not** an OSI/F-Droid free-software licence. An earlier version
  of this sheet said Apache-2.0; that was wrong. F-Droid inclusion needs a free-software
  licence, so do not submit there unless the licence changes. The store description says
  "source-available", not "open source", for the same reason.
- This (offline) build declares no non-free network dependency — no `NonFreeNet`
  tag would be needed. If the `connect` build is ever submitted separately, that one needs
  `NonFreeNet` for its optional third-party AI providers.

## Pre-submit checklist

- [ ] **Merge `Shared-Libraries-asoc` PR #17 first** (merge commit, not squash), then this
      app's PR: the submodule pin `1f33d10` has to exist on that repo (TRAPS #2).
- [ ] Bump `versionCode` to `5` (and `versionName`) in `app/build.gradle.kts` so it matches
      `fastlane/.../changelogs/5.txt`, and update the "Version at time of writing" row above.
- [ ] **Republish the hosted privacy policy** (`docs/store/privacy-policy.md`) in the same
      release: it must say Fotoz and drop the audio permission and background playback.
- [ ] Re-answer the **permissions declaration** and **foreground service** forms without
      audio or media playback; re-check **Data safety** (still: no data collected or shared;
      location used for the compass only, on device; the camera used by Chroma ID only, nothing kept
      but colours, ADR-017). Republish the hosted privacy policy with its new camera and sound-file section.
- [ ] Screenshots from a **prepared photo library**, not your real one: the
      timeline grid, the editor mid-edit, the video editor, the full-screen viewer with
      metadata, the actions room's quick strip, plus a tablet shot. No Audio screens.
- [ ] Confirm the built APK/AAB is the **offline** flavor (`assembleOfflineRelease`)
      before uploading under this listing, and that its merged manifest matches the
      permission table above (no `READ_MEDIA_AUDIO`, no `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
      no `INTERNET`).
- [ ] Re-check this sheet's version/versionCode line against `app/build.gradle.kts`
      before each submission — it drifts every release.
