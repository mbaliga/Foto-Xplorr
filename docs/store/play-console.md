# Foto Xplorr — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`.

| | |
|---|---|
| applicationId | `com.fotoxplorr.app` (the **offline** flavor — no `INTERNET` permission, no network library on the classpath, enforced at build time) |
| Version at time of writing | `0.4.0-pro-media` (versionCode `4`) |
| Category | **Photography** (the app is now a full photo/video editor and metadata tool, not just a browser — Tools remains defensible if the reviewer prefers it) |
| Tags | gallery, photo editor, video editor, offline, local first, privacy |
| Contact email | `fotoxplorr@asystemofcells.com` |
| Website | `https://asystemofcells.com/fotoxplorr` |
| Privacy policy | `https://asystemofcells.com/fotoxplorr/privacy` |

> **Spelling settled.** **Foto Xplorr**, two words, matching the README and the
> applicationId (some branch names elsewhere spell it "Foto Xlorr" — those are
> typos, not a second name).
>
> **Two flavors, one listing.** This listing is for the **offline** flavor: no
> network permission at all, nothing in Data safety to disclose. A separate
> `connect` build (`com.fotoxplorr.app.connect`) adds an opt-in, bring-your-own-key
> AI feature and a live map, and would need its own listing (or its own Data
> safety answers) if it is ever published — do not submit its APK under this
> listing's answers.

## Deltas from the house defaults

### Photo, video and audio permissions — the strictest part of Play policy
Photo, video and audio access is one of the most tightly policed permission
families on Play, and the rules changed recently. Get this right first.

| Permission | Why | Notes |
|---|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` | The product: a gallery, editor and player for the user's own media. | **A gallery/editor is an eligible core use.** Play requires broad media access to be the app's core function, and here it plainly is. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Supports Android 14+ partial access, with an in-app "manage selection" affordance. | **Keep it.** |
| `READ_EXTERNAL_STORAGE` | Legacy path. | Carries `android:maxSdkVersion="32"`. |
| `WRITE_EXTERNAL_STORAGE` | Legacy path for saving an edit/conversion/metadata change. | Carries `android:maxSdkVersion="28"` — Android 9 and below only; every write on newer Android goes through per-file consent instead. |
| `ACCESS_MEDIA_LOCATION` | Reads GPS embedded in the user's own photos, which Android 10+ otherwise redacts. | This, not `ACCESS_FINE_LOCATION`, is what the metadata/map features actually need — see below. |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | A real feature now: an optional on-device compass view orients using the device's current position while that screen is open. | Declared and used; disclose in Data safety as "approximate/precise location, used for App functionality, not shared." |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`, `WAKE_LOCK` | Progress notifications for exports/conversions; background audio playback with lock-screen controls. | Standard for any app with background media playback or long-running jobs. |
| `RECEIVE_BOOT_COMPLETED` | Lets a scheduled background organisation job survive a reboot. | Declared for this alone; no boot receiver runs other code. |

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

Claims that must stay true for this build: photos/videos/audio are never uploaded;
no remote indexing; similarity, face/pet/identity recognition, and metadata writes
are all computed and applied locally; the map downloads no tiles; there is no AI
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
- ✅ Licence present (Apache-2.0).
- This (offline) build declares no non-free network dependency — no `NonFreeNet`
  tag needed. If the `connect` build is ever submitted separately, that one needs
  `NonFreeNet` for its optional third-party AI providers.

## Pre-submit checklist

- [ ] Screenshots from a **prepared photo library**, not your real one: the
      timeline grid, the editor mid-edit, the video editor, the Audio destination,
      the full-screen viewer with metadata, plus a tablet shot.
- [ ] Confirm the built APK/AAB is the **offline** flavor (`assembleOfflineRelease`)
      before uploading under this listing.
- [ ] Re-check this sheet's version/versionCode line against `app/build.gradle.kts`
      before each submission — it drifts every release.
