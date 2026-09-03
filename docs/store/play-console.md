# Foto Xplorr — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`.

| | |
|---|---|
| applicationId | `com.fotoxplorr.app` |
| Version at time of writing | `0.3.0-ai-spatial` (versionCode `3`) |
| Category | **Tools** (Photography is also defensible; Tools matches the house pattern for local-first managers) |
| Tags | gallery, photos, offline, local first, albums, privacy |
| Contact email | `fotoxplorr@asystemofcells.com` |
| Website | `https://asystemofcells.com/fotoxplorr` |
| Privacy policy | `https://asystemofcells.com/fotoxplorr/privacy` |

> **Spelling settled here.** `NAMES.md` flagged that branches spell it "Foto Xlorr"
> in places and asked for a decision before any store listing. This sheet settles on
> **Foto Xplorr**, two words, matching the README and the applicationId. Update
> `NAMES.md` to match.
>
> Also: `main` is bare. The app lives on stacked PRs #3 and #4, and #4 is CI red.
> There is no shippable build yet.

## Deltas from the house defaults

### Photo and video permissions — the strictest part of Play policy
Photo and video access is one of the most tightly policed permission families on
Play, and the rules changed recently. Get this right first.

| Permission | Why | Notes |
|---|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | The product: a gallery for the user's own media. | **A gallery is an eligible core use.** Play requires broad photo access to be the app's core function, and here it plainly is. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Supports Android 14+ partial access. | **Keep it.** Supporting partial selection materially strengthens the case for the broad permission. |
| `READ_EXTERNAL_STORAGE` | Legacy path. | Must carry `android:maxSdkVersion="32"`. Verify it does. |
| `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` | ⚠️ **See below. Probably remove both.** | |

**The location permissions look wrong for this app, and removing them is likely a
strict improvement.** The README is explicit that the map is offline, downloads no
tiles, and uses "no current-location permission", and that location metadata is
extracted *from the user's own files*. Reading EXIF GPS out of a photo the user
already has requires **no location permission at all** on modern Android.

So unless something else needs them:

```xml
<!-- delete both, unless a feature genuinely needs the device's own position -->
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Keeping them costs a location entry in Data safety, a scary runtime prompt in a
photo app, and a reviewer question you would rather not answer. **Resolve this
before submitting.** If a feature does need device position, the listing and policy
must both say which one and why.

> One exception worth checking: on Android 10 and above, `MediaStore` redacts EXIF
> location from photos unless the app holds `ACCESS_MEDIA_LOCATION`. If the app
> reads GPS from user photos, the permission it actually needs is
> **`ACCESS_MEDIA_LOCATION`**, not `ACCESS_FINE_LOCATION`. That is a much easier
> permission to justify and does not imply tracking the user.

### Data safety
**No data collected. No data shared** (assuming the location permissions are
removed as above, and no AI feature is enabled by default).

| Question | Answer |
|---|---|
| Collect or share any user data? | **No** |
| Encrypted in transit? | Yes |
| Deletion? | Users can delete data in the app |

Claims that must stay true: photos are never uploaded; no remote indexing; the
similarity map, face grouping and any 3D view are computed locally; the map
downloads no tiles.

**The optional BYO-key AI is the one user-directed transfer**, and it must be
disclosed in the policy in plain words: if the user configures it, the images they
choose to run it on go to the provider they chose. If that feature is ever on by
default, the Data safety answer changes to "collected and shared: Photos and videos".

### Content rating
- Category `Utility, Productivity, Communication, or Other`.
- **"Does the app contain user-generated content?"** → No in the store sense: it
  displays the user's own local media and shares nothing to any service.
- Everything else No. Expected **Everyone**.

> The app has a "sensitive media" flag and smart album. That is a **local
> organisation feature**, not a content category the app ships, and it does not
> change any rating answer. Do not screenshot it with real content.

### Large screens
Ship `tenInchScreenshots/`. A gallery is a large-screen app, and the grid from two
to seven columns is a tablet argument.

## F-Droid
- ✅ Licence present. Declare **`NonFreeNet`** (optional BYO-key AI).

## Pre-submit checklist

- [ ] Merge #3 before #4, and get #4's CI green. No build exists otherwise.
- [ ] Resolve the location permissions: remove, or swap to `ACCESS_MEDIA_LOCATION`.
- [ ] Confirm `READ_EXTERNAL_STORAGE` has `maxSdkVersion="32"`.
- [ ] Update `NAMES.md` with the settled spelling.
- [ ] Screenshots from a **prepared photo library**, not your real one. Timeline
      grid, similarity map, 3D wall, full-screen viewer. Plus tablet.
