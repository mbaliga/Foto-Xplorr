# Privacy Policy — Foto Xplorr

> **This is the working copy, not the hosted one.** The URL Play Console points at
> is **https://asystemofcells.com/fotoxplorr/privacy**. Keep the two in sync by hand.

**Last updated: 16 September 2026**

Foto Xplorr is a photo and video gallery and editor for Android (package
`com.fotoxplorr.app`), made by A System of Cells. It can see every photo on your
phone, edit it, and write metadata back into it, so this policy is specific about
what it does with them. This policy describes the **offline** build — the one
published under this listing, with no network permission at all. A separate
`connect` build exists with an opt-in AI feature; see "Optional AI features" below.

## The short version

Foto Xplorr has no accounts, no advertising, no analytics and no tracking. **Your
photos are never uploaded.** The offline build holds no network permission and
cannot make a network request even if it wanted to.

## What the app collects

**Nothing.** No photos, no thumbnails, no metadata, no usage data.

## Your photos and videos

The app reads the photos and videos already on your device to show, organise and
edit them. That is the whole reason it asks for media access.

- **Nothing is uploaded.** There is no cloud, no sync and no backup.
- **Nothing is used to train anything.**
- **Nothing is indexed remotely.** Everything the app knows about your library —
  the timeline, smart albums, duplicates, similarity, on-device recognition — is
  computed on your device and stored on your device.
- If you use Android 14 or later, you can grant access to **selected photos and
  videos only**, and the app works with that, with an in-app way to change the
  selection later.
- On Android 13 and later, browsing your audio library needs a separate audio
  permission (`READ_MEDIA_AUDIO`), used the same way: to show and play files
  already on your device, nothing more.

## Editing and metadata

The editor changes pixels and writes standard photo metadata (EXIF/XMP: rating,
creator, copyright, keywords, location) into files at your request. Saving a copy
or exporting always creates a new file; replacing the original happens only when
you choose "Replace the original" and confirm Android's own permission prompt for
that specific file. Tag edits to audio files (title, artist, album, cover art) work
the same way. None of this leaves your device.

## Location in your photos, and the compass

Some photos carry the coordinates of where they were taken, written by the camera
that took them. The app reads that information **out of your own files**, on your
device, to place them on a map, and can also write a location into a photo that has
none, at your request.

Separately, an optional compass view can use your device's **current** location
(via `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION`) to orient itself — only while
that screen is open, and never logged, stored, or sent anywhere. The map itself is
**offline**: it downloads no tiles and contacts no map service.

## Background audio playback

Playing audio can continue after you leave the app, with a standard Android
notification and lock-screen controls, so you can pause or skip without reopening
it. This uses no network and shares nothing with any other app beyond what Android
itself shows in that system notification.

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
Uninstalling Foto Xplorr deletes all of it. Your actual photos and videos are
untouched by an uninstall; they were never the app's to begin with.

## Permissions, and why each exists

| Permission | Why |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` | To show, organise and edit your own photos, videos and audio. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | So you can grant access to selected photos and videos only, if you prefer. |
| `ACCESS_MEDIA_LOCATION` | So the location embedded in your own photos is not redacted by the system before the app reads it. |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | Only for the optional compass view, only while it is open. |
| `WRITE_EXTERNAL_STORAGE` (Android 9 and below only) | Needed on those versions to save an edit, a conversion, or a metadata change; newer Android handles this per-file instead. |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `WAKE_LOCK` | To show progress for exports/conversions and to keep audio playing with system controls while you use another app. |
| `RECEIVE_BOOT_COMPLETED` | So a background organisation job you scheduled can survive a reboot. |

This build declares no `INTERNET` permission and cannot reach the network.

## Children

Foto Xplorr is not directed at children and collects no personal information from
anyone, including children.

## Changes

If this policy changes, the "Last updated" date above changes with it, and the
revised policy is published at this same URL.

## Contact

Foto Xplorr is made by **A System of Cells**. Questions about this policy or the
app: fotoxplorr@asystemofcells.com
