# ADR-017 — The camera (Chroma ID) and the reel soundtrack: two narrow exceptions

**Status:** accepted; nothing in it has run on a device (see "What is not known").
**Date:** 28 Oct 2026
**Scope of this record:** the two places where batch 8 relaxed an earlier "never" (Fotoz has no camera, Fotoz has no audio), what exactly was allowed, what stays forbidden, why the camera is built on the platform's Camera2 and not CameraX, and what the Play Console forms must say.

## Context

Two earlier statements were true for every release up to batch 7 and are repeated in the README, the store description, `docs/PRODUCT-AND-ARCHITECTURE.md` and the privacy policy:

1. *"There is no camera"* (the wall mock-up works on a photo of the wall; the live-camera wall was listed as "not built, on purpose: needs a CAMERA permission decision").
2. *"Fotoz has no audio"* (the Audio destination, the audio permission and the media-playback service were removed by owner decision in October 2026; the owner's rule since then is **visuals only**).

Batch 8 adds two tools that each need one narrow exception. The owner decided both; this record states them so no later change widens them by accident.

## Decision 1 — Chroma ID may use the camera, inside that tool only

**Chroma ID** (`chroma/`, Experimental > Explore) reads the colour of a surface from two phone pictures, one with the torch off and one with it on, and subtracts the room's light (a white card beside the surface corrects it, to about 3 to 6 CIEDE2000 off at best). It cannot work from library photos: it needs two frames taken under the same locked exposure, white balance and focus, and a controlled light.

What is allowed:

- The manifest declares `android.permission.CAMERA`. The permission is **asked inside the tool, when Chroma ID is opened** (with one sentence of reason on screen first), never at app start, never by another screen.
- The camera is open **only while the measuring view is on screen and the app is resumed.** Leaving, pausing, opening the history, compare or palette view, rotating and closing the tool release the camera, the image reader and the gyroscope. `ChromaPrivacyTest` pins that `Manifest.permission.CAMERA` is named only in `ChromaScreen.kt` and never in `FotoXplorrActivity`.
- The pictures are about 0.3 megapixel, **held in memory only** for the few seconds a measurement takes, and overwritten with zeros as soon as the answer is worked out, on every path out (answer, cancel, failure, leaving). Nothing is written to MediaStore, to a file or to preferences; `ChromaPrivacyTest` reads the source to hold that (no `MediaStore`, `ContentResolver`, `compress` or file writer in the package apart from `ChromaHistory.kt`).
- What the tool **keeps** is a colour, how it was made (with a card, without, room light only) and a time: the last 50, as plain text in `filesDir/chroma/history.txt`, with "Delete all" on the history view. A colour goes to a palette only when the person asks, through the palette builder's own store. A white-card measurement is kept as a handful of numbers only while the light stays held. **No picture, no place, no photo identity is kept.**
- The flash is a held **torch**, not a flash burst (a burst's timing and brightness cannot be tied to a locked exposure, and a pre-flash would break the subtraction).
- The Experimental entry is `restorable = false` and `available = deviceHasCamera` (`FEATURE_CAMERA_ANY`): after a process kill the person is not put back in front of a camera, and a phone with no camera sees the card dimmed.
- The Experimental page says so in one line (`HelpCopy.Note.EXPERIMENTAL_CAMERA`): *"The camera is used only by Chroma ID, and its pictures are never saved or sent."*

What stays forbidden: a camera in any other screen; a live-camera wall or AR; saving, sharing or sending a camera picture; recording video or audio from the camera (`RECORD_AUDIO` is not declared, and `ChromaPrivacyTest` checks it); reading the camera in the background.

## Decision 2 — The reel maker may lay a sound file the person picks under a video

**Make a reel** (`reel/`, Experimental > Make) writes a silent MP4 by default. The person may pick **one sound file** to lay under the whole video (trimmed to the reel, with a fade-out), and may switch on the sound of video clips (off by default).

What is allowed:

- The file comes from the system **document picker** (`ACTION_OPEN_DOCUMENT`, audio types only). **No audio permission** is declared or requested: not `READ_MEDIA_AUDIO`, not `RECORD_AUDIO`, not `READ_EXTERNAL_STORAGE` for audio. `SoundtrackPickerTest` checks the merged manifest for both. The persistable read grant on the picked address (`SoundtrackAccess.keep`) lets a saved reel find the file again; if the grant is refused the screen says it may need picking again.
- The file's name is asked from the provider when the Sound panel opens, it is probed with `MediaExtractor` at the start of the job, and it is read by the export, by Media3 Transformer, as an audio track of the new video. The app holds the persistable read grant for it until the sound is replaced or removed or its reel is deleted (released only if no other saved reel lays it; `ReelSoundGrants`). **Nothing is ever played back in Fotoz.** There is no audio player, no preview of the sound, no waveform and no music library; the reel preview is pictures only.
- If the sound file cannot be read or fails during the export (a container the export cannot parse arrives as `ExportException` `ERROR_CODE_UNSPECIFIED` with a `ParserException` in the causes, since Media3 1.9.0 has no `PARSING_CONTAINER_*` codes there; it is not retried smaller), **nothing is saved** and the job asks "Make the reel without sound?"; only a yes makes a silent reel. A file is never silently dropped.
- The video is written by a muxer that drops every metadata entry of the source clips (including where and when they were filmed) and the finished file is looked at for a place and refused if it has one.
- The Experimental page says so in one line (`HelpCopy.Note.EXPERIMENTAL_SOUND`): *"Fotoz has no music library; a reel can carry one sound file you pick."*

What stays forbidden: an audio library, an audio destination, audio playback or a preview of the chosen sound, any audio permission, audio recording, reading the person's music folder. The existing video player still plays a video's own sound, as it always did. Other tools (GIF/APNG/MP4 maker, mood board, palette builder) make silent output and have no sound control.

## Decision 3 — Camera2, not CameraX; zero new dependencies

The camera seam is the platform's `android.hardware.camera2` (`Camera2Source`, `CameraProbe` in `chroma/`), with `SensorManager` for the gyroscope. **CameraX was considered and not added.**

| Option | Verdict | Why |
|---|---|---|
| CameraX (`androidx.camera:*`) | Rejected | Adds four artifacts and their transitive tree to both flavours for one screen. More important, the method needs what CameraX abstracts away: a **locked** exposure time, ISO and white-balance gain, the torch held steady (manual sensor values where the phone allows them, otherwise the auto-exposure lock), an auto-focus settle with the lens's reported focus distance, and the per-frame capture result matched to its image by `SENSOR_TIMESTAMP` (the job refuses a pair whose reported exposure or white balance slipped by more than 2 percent). CameraX reaches most of those controls only through its Camera2 interop, and its own metering would fight the lock. (Reasoned from its documented use cases, not tried.) |
| Camera2 | **Used** | Already in the platform; gives every control the method needs; no dependency row (`docs/DEPENDENCIES.md` gets no new entry). The cost is that Camera2 differs between phones more than CameraX hides, which is listed in the batch 8 device checklist. |
| Take-picture intent | Rejected | The system camera app applies its own tone curve, HDR, flash timing and white balance; no control, no pair. |

The job is written over a `CameraSource` interface with a fake camera, so the whole sequence (lock, focus, ambient shot, flash shot, release) is unit tested without a device.

## Decision 4 — Manifest

`app/src/main/AndroidManifest.xml` declares `CAMERA` and **three optional features**, all `required="false"`: `android.hardware.camera.any`, `android.hardware.camera` and `android.hardware.camera.autofocus`. The CAMERA permission otherwise implies a *required* camera and autofocus, and Play would hide Fotoz from phones without them; all three are needed to remove the implied requirements, not only `camera.any`. `ChromaPrivacyTest` pins this and `verifyOfflineManifest` (which forbids network permissions) passes. The offline flavour still holds no network permission.

## What the Play Console must say

Re-check against a fresh merged manifest before each submission (`docs/store/play-console.md`).

**Permissions declaration:** `CAMERA` is a regular runtime permission; no special declaration form. Reason to give if asked: *"Used only by the optional Chroma ID tool to read the colour of a surface under a steady light. Pictures stay in memory and are never saved or sent."* There is **no** audio permission to declare: do not carry over any answer from before the audio removal, and do not add one for the soundtrack (the document picker needs none).

**Data safety (offline listing):** the answer stays **"No data collected. No data shared."** Play's form counts data that *leaves the device*. Camera pictures are not stored and not transmitted; the colour history is on-device only and never leaves it; the picked sound file is read on the device into a new video on the device. The `Location` row (compass, on device, not shared) is unchanged. Do not tick "Photos and videos" or "Audio files" as collected. If the form asks about the camera or microphone by hardware, the answer is: camera used by one optional tool, nothing collected; microphone not used.

**Connect build:** unchanged by this record. Chroma ID and the reel's sound file have no network code in either flavour (`verifyOfflineSourceReferences` scans the tools' sources comments included). If the Connect build is ever submitted, its own Data safety answers must still cover the AI providers and the map, not these tools.

**Store listing text:** `fastlane/.../full_description.txt` and `docs/store/privacy-policy.md` say that the camera is used only by Chroma ID and its pictures are never saved or sent, and that a reel can carry one picked sound file with no audio permission. **The hosted privacy policy must be republished with the release that ships these tools.**

**Foreground-service declaration:** unchanged. The reel and GIF/video exports run in the existing `JobForegroundService` (`dataSync|mediaProcessing`); nothing declares `mediaPlayback` or `camera` as a service type, and Chroma ID is foreground-only so it needs no camera service type.

## What is not known

Everything about the camera sequence has run only against a fake camera: whether a phone's torch holds steady with a locked exposure, whether the exposure/white-balance report matches between frames, whether `LENS_FOCUS_DISTANCE` is accurate at 10 to 40 cm, and whether a phone's tone curve keeps the flash-minus-ambient subtraction linear enough. The sound path has run only against fakes and Robolectric: picked-file formats, a revoked grant after a restart, and the level of music under clip sound are device checks. See `docs/device-test/batch-8-checklist.md`.

## Consequences

- Two sentences in `HelpCopy` (`EXPERIMENTAL_CAMERA`, `EXPERIMENTAL_SOUND`) are the only user-visible statement of these exceptions; `HelpCopyTest` pins their key words and length.
- Any future screen that wants the camera, or any audio capability, needs a new decision record; it cannot use this one.
- The README "Known gaps" no longer lists "live-camera wall previews" as blocked by a permission decision: the decision is made and is **no**, apart from Chroma ID.
