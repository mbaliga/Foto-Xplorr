# Privacy Policy — Fotoz

> **This is the working copy, not the hosted one.** The URL Play Console points at
> is **https://asystemofcells.com/fotoxplorr/privacy**. Keep the two in sync by hand.
> The hosted page must be republished together with the release that removes the audio
> permission (versionCode 5): until then it still describes an audio library and
> background playback that no longer exist.

**Last updated: 21 October 2026**

Fotoz (previously called Foto Xplorr) is a photo and video gallery and editor for
Android (package `com.fotoxplorr.app`), made by A System of Cells. It can see every
photo on your phone, edit it, and write metadata back into it, so this policy is
specific about what it does with them. This policy describes the **offline** build —
the one published under this listing, with no network permission at all. A separate
`connect` build exists with an opt-in AI feature; see "Optional AI features" below.

## The short version

Fotoz has no accounts, no advertising, no analytics and no tracking. **Your photos are
never uploaded.** The offline build holds no network permission and cannot make a
network request even if it wanted to.

## What the app collects

**Nothing.** No photos, no thumbnails, no metadata, no usage data.

## Your photos and videos

The app reads the photos and videos already on your device to show, organise and
edit them. That is the whole reason it asks for media access. It does not ask for
access to your music or other audio files, and it has no audio library.

- **Nothing is uploaded.** There is no cloud, no sync and no backup.
- **Nothing is used to train anything.**
- **Nothing is indexed remotely.** Everything the app knows about your library —
  the timeline, smart albums, duplicates, similarity, on-device recognition — is
  computed on your device and stored on your device.
- If you use Android 14 or later, you can grant access to **selected photos and
  videos only**, and the app works with that, with an in-app way to change the
  selection later.

## When another app opens a photo in Fotoz

Fotoz can be chosen from another app's "Open with" list, receive a photo or video
you share to it, and act as a photo picker for other apps. Only the file the other
app hands over (a `content://` address, never a raw file path) is read, to show it.
It is not added to your library and is not sent anywhere.

A photo that Fotoz is hiding from you — in a locked folder, in the archive, in the
trash, or hidden as sensitive — is not opened for another app either: the request is
refused with a short notice. The photo picker only offers photos that are visible in
the gallery.

## Editing and metadata

The editor changes pixels and writes standard photo metadata (EXIF/XMP: rating,
creator, copyright, keywords, location) into files at your request. Saving a copy
or exporting always creates a new file; replacing the original happens only when
you choose "Replace the original" and confirm Android's own permission prompt for
that specific file. Saving to a destination you choose ("Save to…") removes the photo's
location from the file. None of this leaves your device.

While a replacement is being written, the app keeps a copy of that one original in
its own private storage so it can put the photo back if the write fails. The copy is
deleted once the new file has been checked. If the app is closed or stopped part-way
through, the copy is kept and the next start tells you which photo it belongs to and
offers to share it, so you can restore the photo yourself; it is deleted 7 days after
you answer that notice (30 days if the original could not be put back), or at once
if you choose to delete it. The copy lives only in the app's private storage and is
never sent anywhere unless you choose to share it.

## Location in your photos, and the compass

Some photos carry the coordinates of where they were taken, written by the camera
that took them. The app reads that information **out of your own files**, on your
device, to place them on a map, and can also write a location into a photo that has
none, at your request.

Separately, an optional compass view can use your device's **current** location
(via `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`) to orient itself — only while
that screen is open, and never logged, stored, or sent anywhere. The map itself is
**offline**: it downloads no tiles and contacts no map service.

## Organising is reversible

Collections group your media without moving the underlying files. A move copies the
file first, and only then asks Android to put the original in the system trash. The
app tries hard never to be the reason a photo is gone.

## Optional AI features (connect build only)

Not present in this build. A separate `connect` build offers an off-by-default,
bring-your-own-key AI feature: if you supply your own API key for a provider, the
images you choose to run it on are sent to that provider, over HTTPS, under that
provider's privacy policy. It never happens unless you configured it and asked for
it, image by image, and we never see the images — there is no proxy of ours in the
middle. If you installed this app from a listing that does not mention a
BYO-key AI feature, you have the offline build, which cannot do this at all.

## What is stored, and where

Your tags, favourites, archive state, sensitive flags, collections, saved edit
presets and settings are stored in the app's private storage on your device.
Uninstalling Fotoz deletes all of it. Your actual photos and videos are
untouched by an uninstall; they were never the app's to begin with.

If you used an earlier version that had an audio library, the first start of this
version deletes that library's database and its scan settings from the app's private
storage. Your audio files themselves are never touched.

## Permissions, and why each exists

This is the permission list of the offline build's merged manifest, as built. There
is no audio permission and no media-playback service.

| Permission | Why |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | To show, organise and edit your own photos and videos. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | So you can grant access to selected photos and videos only, if you prefer. |
| `READ_EXTERNAL_STORAGE` (Android 12L and below only) | The older form of the photo and video access above. |
| `ACCESS_MEDIA_LOCATION` | So the location embedded in your own photos is not redacted by the system before the app reads it. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Only for the optional compass view, only while it is open. |
| `WRITE_EXTERNAL_STORAGE` (Android 9 and below only) | Needed on those versions to save an edit, a conversion, or a metadata change; newer Android handles this per-file instead. |
| `POST_NOTIFICATIONS` | To show progress for a conversion, export or copy while the app is in the background. On Android 13 and later the app asks once, the first time such a job runs. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING` | To keep a long conversion, export or copy running, with a visible notification, if you leave the app. |
| `WAKE_LOCK` | Added automatically by the video player library the app is built with. The app's own code does not hold a wake lock. |
| `RECEIVE_BOOT_COMPLETED` | So a background organisation job you scheduled can survive a reboot. |

This build declares no `INTERNET` permission and cannot reach the network.

## Children

Fotoz is not directed at children and collects no personal information from
anyone, including children.

## Changes

If this policy changes, the "Last updated" date above changes with it, and the
revised policy is published at this same URL.

## Contact

Fotoz is made by **A System of Cells**. Questions about this policy or the
app: fotoxplorr@asystemofcells.com
