# Model and data licences

Every ML model, and every data file that is not code, that the app ships inside the APK or fetches at
run time has a row here (`MASTER-PLAN.md` §0.2 standing rule 2). `scripts/verify-governance-docs.sh`
(run by `scripts/verify.sh`, so by CI) fails when one of these is missing:

- a file under `app/src/*/assets/` (the row names `assets/<path>`),
- a spec id in `services/ModelCatalogue.kt`,
- an ML Kit or MediaPipe artifact in a build file (the row names `group:name`),
- a model-like, font or audio file (`.tflite`, `.task`, `.onnx`, `.gguf`, `.ttf`, `.otf`, `.wav`, `.mp3`, `.ogg`, ...) or anything in a `res/raw` folder, anywhere under `app/src` outside tests (the row names its file name).

**How to read the Licence and Size columns.** A claim is marked **verified** only when it was read from
something checkable, and the source is named in the row: the model file's own embedded metadata, the
library's POM or AAR as resolved by Gradle, or the HTTP headers of the download. Anything else says
**unverified**. "Unverified" is not a statement that the licence is bad; it is a statement that this file
does not know. Sizes of bundled models are the uncompressed size of the files inside the library's AAR
(what ends up in the APK is the compressed size, shown where measured).

Last checked: 2026-11-04, against the Gradle cache of this build (`mlkit` 16.1.7 / 17.0.9 / 16.0.1 / 17.0.3,
`mediapipe tasks-vision` 0.10.35).

## Models

| Item | What it enables | Where it comes from | Licence | Size | Flavor |
|---|---|---|---|---|---|
| `image-embedder-mobilenet-v3-small` (MobileNet V3 small image embedder, `mobilenet_v3_small.tflite`, float32) | "Find look-alike photos": photos laid out by visual similarity (`ai/SimilarityIndexer.kt`). | Not bundled. Fetched on the user's tap, or imported from a file the user picks. Source URL (`ModelCatalogue.IMAGE_EMBEDDER`): `https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite`. Saved app-private, with its SHA-256 recorded. | **Verified Apache 2.0**: the model file carries the string "Apache License. Version 2.0 http://www.apache.org/licenses/LICENSE-2.0." in its own metadata (file read 2026-11-04). The MediaPipe library that runs it (`com.google.mediapipe:tasks-vision`) is Apache 2.0 per its POM. The training-data terms (the model description says "MobileNetV3 image classifier") were not checked: **unverified**. | **Verified 4,117,670 bytes (3.9 MiB)** from the download's `Content-Length`/`x-goog-stored-content-length` header, SHA-256 `bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40`, object last modified 2023-04-26. NOTE: `ModelCatalogue.IMAGE_EMBEDDER.approxBytes` still says 10 MB (not true; the screen shows the wrong "about" size before the download). | Connect downloads or imports; Offline imports a file only (it has no network code). |
| `com.google.mlkit:face-detection` 16.1.7: bundled face detector (BlazeFace-style `blazeface.tfl`, `contours.tfl`, `fssd_*` detectors, eye-closed / smile classifiers) | The People destination: finds faces in photos on the device. | Inside the library's AAR (`assets/models_bundled/`, 14 files), so inside the APK. No download, no Play Services. | **Unverified for the model files themselves.** The artifact's POM declares only "ML Kit Terms of Service" (`https://developers.google.com/ml-kit/terms`), a proprietary licence, not an open-source one; the AAR also ships `third_party_licenses.txt` (about 1.4 MB) for its dependencies, which was not read in full. Treat as: usable in this app under Google's ML Kit terms; not redistributable on their own. The "bundled" artifact's POM also depends on `com.google.android.gms:play-services-mlkit-face-detection` (client code, same terms) on both flavors; whether it needs Play Services at run time is **unverified** (it is a device question). | **Verified 4,277,846 bytes uncompressed** (3,384,376 compressed) of model files in the AAR. | Both |
| `com.google.mlkit:image-labeling` 17.0.9: bundled default image labeller (`assets/mlkit_label_default_model/mobile_ica_8bit_with_metadata_tflite`) | Pets / scene labels on photos, on the device. | Inside the AAR, so inside the APK. | POM: "ML Kit Terms of Service" only; model licence beyond that **unverified**. Same note as above. | **Verified 3,042,638 bytes uncompressed** (1,989,552 compressed) for the one model file. | Both |
| `com.google.mlkit:text-recognition` 16.0.1: bundled Latin text recogniser (`assets/mlkit-google-ocr-models/`: text detector, script identifier, line recogniser, layout models) | The Identity destination and "Search inside this photo": reads text in photos, on the device. | Inside the AAR, so inside the APK. | POM: "ML Kit Terms of Service" only; model licence beyond that **unverified**. | **Verified 1,486,803 bytes uncompressed** (1,053,740 compressed) of model and config files. (The comment in `app/build.gradle.kts` quotes about 11 MB per ABI for the whole text-recognition artifact including native code; that figure is from an earlier debug-APK measurement and was not re-measured here.) | Both |
| `com.google.mlkit:translate` 17.0.3: translation language packs | The Translate action of "Search inside this photo". | **Not bundled.** ML Kit itself downloads a language pack the first time a language pair is used; the app does not manage these files. The AAR carries the pack list `res/raw/translate_models_metadata.json`. | POM: "ML Kit Terms of Service" only; pack licences **unverified**. | **Verified from that metadata**: 86 pack entries, download sizes from 1,201,533 to 48,033,822 bytes (about 1.1 to 46 MiB); the comment in `app/build.gradle.kts` says about 30 MB, which sits inside that range. | Connect only. The Offline flavor never links it: its translator reports itself unavailable and hands off to an installed translator app. |
| `com.google.mediapipe:tasks-vision` 0.10.35: the runtime for the embedder above | Runs the embedder. | The AAR bundles **no model file** (checked: only `R.txt` under non-code entries); native libraries only. | Apache 2.0 per the POM (verified). | Native-library size in the APK: see `docs/DEPENDENCIES.md`. | Both |

## Data files shipped in the APK

| Item | What it enables | Where it comes from | Licence | Size | Flavor |
|---|---|---|---|---|---|
| `assets/basemap/world.fxmap` | The world basemap behind the Place section and the Map destination (land, lakes, country borders, the 1,000 largest populated places), so the map works with no network. | Built by `scripts/gen_basemap.py` from Natural Earth 1:50m data (naturalearthdata.com). The generator is deterministic and its docstring documents the file format. | Natural Earth states its data is in the public domain, no attribution required; the app credits it anyway on About. This is the project's own statement from `docs/DEPENDENCIES.md`; the licence text on naturalearthdata.com was **not re-fetched** for this table: unverified here. | **Verified 220,074 bytes** (file on disk). | Both |
| `assets/ai-catalogue/models.json` (the Nooz "ai-catalogue", 22 entries: 17 LLM GGUF, 3 Stable Diffusion, 2 TTS ONNX) | Lists models in Settings, Services, "Model catalogue". **It is a list only**: the app has no runtime for those kinds (`CataloguePolicy.RUNNABLE_KINDS` is empty), the "Get" button is disabled, and none of the listed model files is bundled or downloaded. The two TTS entries are hidden. | Snapshot of the shared Nooz catalogue (`lastUpdated` 2026-07-22), refreshed by `scripts/refresh-ai-catalogue.sh`; the Connect build can fetch a newer copy on an explicit tap. | The JSON file itself: the Nooz project's data, same repository owner; **unverified** as to its own licence. The entries carry no per-model licence field in this file (checked: no `license` key), so **the licence of each listed model is unverified here**. Before any of them becomes downloadable, each needs its own row in this file, read from the model's own page. | **Verified 16,929 bytes** (file). The listed models total about 101 GB if all were downloaded; they are not. | Both (the Connect flavor may refresh it) |
| `assets/ai-catalogue/free-tiers.json` (12 cloud providers' free-tier facts) | The BYOK setup screen's "what is free" hints. | Same snapshot and refresh as above; each entry carries its own `sourceUrl`. | Facts about third-party services, not a licensed work; **unverified** as to reuse terms. The file's own note says the numbers are external and drift. | **Verified 8,868 bytes** (file). | Both |

## Fonts and sounds shipped in the APK

Not models, but non-code files whose licence differs from the code's, so they are listed here.

| Item | What it enables | Where it comes from | Licence | Size | Flavor |
|---|---|---|---|---|---|
| `hyle_grotesk_light.ttf`, `hyle_grotesk_regular.ttf`, `hyle_grotesk_medium.ttf`, `hyle_grotesk_bold.ttf` (`app/src/main/res/font/`), used by `ui/HyleType.kt` | The app's typeface (Hyle Grotesk). | Copied from the Hyle Design System repository (`hyle-design-system/fonts/HyleGroteskClassic`). | SIL Open Font License 1.1, as stated by the family's own `LICENSE-NOTE.txt` there and by the app's `res/raw/license_hyle_grotesk.txt` (read 2026-11-04). The note says the family is a derivative of Space Grotesk with Archivo letterforms; the **upstream fonts' own licence files were not read**: unverified. Hyle's `NOTICE` says fonts are not under Hyle's Apache-2.0 grant. | **Verified** 88,284 to 88,416 bytes each (352 KB for the four). | Both |
| `license_hyle_grotesk.txt` (`app/src/main/res/raw/`) | The font licence note itself, kept in the APK. | Same note as above. | Text of the note above. | **Verified** 306 bytes. | Both |
| `hyle_tab_tick.wav` (`app/src/main/res/raw/`) | A short tab-change tick from the Hyle design system. **Nothing in the app's Kotlin or XML references it** (searched 2026-11-04), and the app has no sound feature, so it is an unused resource that shrinking may drop. | Added with the Hyle submodule commit; the original source of the sound is not recorded. | **Unverified**: no licence statement found for this file. Remove it, or find the source, before a public release. | **Verified** 4,012 bytes. | Both |

## Data fetched at run time (Connect flavor only)

These are not shipped and not models, but they are third-party data the app pulls, so they are listed.

| Item | What it enables | Where it comes from | Licence | Size | Flavor |
|---|---|---|---|---|---|
| Street map style and vector tiles | The street-level map. | `https://tiles.openfreemap.org/styles/liberty` and `.../dark`, read by MapLibre at pan time. | **Unverified** (OpenFreeMap serves OpenStreetMap-derived data; the exact terms and attribution wording were not checked). | Streamed; not measured. | Connect only |
| Terrain elevation tiles | Hillshade / 3D terrain on the street map. | `https://s3.amazonaws.com/elevation-tiles-prod/terrarium/`. | **Unverified**. | Streamed; not measured. | Connect only |

## Not models, listed so nobody wonders

- Imported map files (`.mbtiles`, raster only) are the user's own files; nothing is shipped or fetched.
- The reel soundtrack is a file the user chooses; no audio is bundled.
- `app/src/main/res` contains no model, no `res/raw`, and no `.tflite`/`.task`/`.onnx` file (checked 2026-11-04).
