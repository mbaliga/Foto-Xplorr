# Format corpus (`testdata/formats/`)

WP1.8. A small synthetic set of image files, one or more per format the app can sniff or decode, for tests that need real bytes. About 66 files, 21 KB in total (limit: 1 MB).

## Where it comes from

Every file is built by `scripts/gen-format-corpus.py` (Python standard library only) from one invented picture: four quadrants, red / green / blue / yellow, 16x16. No photo and no third-party sample is included, so there is nothing to attribute: the corpus is ours, CC0. `testdata/formats/SOURCES.md` has one row per file (origin, licence, what it is good for) and is written by the same script.

- Re-create: `python3 scripts/gen-format-corpus.py`. The output is byte-identical every run (checked).
- One file is not built by the script: `webp-lossy.webp` needs a VP8 encoder, so it was encoded once with libwebp (through ffmpeg) from the same picture. The script keeps the committed file; `--lossy-with-ffmpeg` re-creates it.
- The JPEG, lossless WebP, animated WebP, TIFF (none / PackBits / LZW / Deflate, both byte orders), PSD, QOI, TGA, ICNS, HDR, PNM, ICO, BMP, GIF and APNG writers are hand-written from the format specs and were cross-checked with an independent decoder (Pillow / ffmpeg) when the corpus was made: right size, right quadrant colours. HDR was checked with ffmpeg, SVG as XML, and ORA / KRA only as ZIPs (nothing here opens their contents).

## Two kinds of file

1. Valid tiny files. The manifest column `decode` says `platform` for the ones Android's own codecs must open (JPEG, PNG, APNG first frame, GIF, WebP, BMP, ICO) and `none` for valid files of formats the app does not decode today (TIFF, PSD, QOI, TGA, PNM, HDR, ICNS, SVG, ORA, KRA). `none` is not a claim that they cannot be decoded, only that nothing in the app does it yet.
2. `stub-*` files: the first bytes of a format that needs a real encoder (HEIC, AVIF, JXL, EXR, JP2, the RAW families, DDS, KTX2, PCX, XCF...), padded to 32 bytes. They are not images. They exist only so `FormatSniffer` has a signature to recognise, and they are never to be treated as decodable.

## `manifest.txt`

One row per file: `file sniff decode width height check`.

- `sniff`: the `MediaFormat.formatId` the sniffer must return (`none` when it must return null; TGA and SVG have no binary signature).
- `decode`: `platform` or `none`, as above.
- `width height`: upright size (0 for stubs). `jpeg-exif-rot6.jpg` is stored 16x8 with EXIF orientation 6 and must come out 8x16.
- `check`: `quad` means the four corners are the quadrant colours (within a tolerance for lossy files).

## Tests

- JVM, runs everywhere: `FormatCorpusSniffTest` (app unit tests). It fails when a file is missing from the manifest or SOURCES.md (or the reverse), when the sniffer disagrees with a row, when a stub is bigger than 64 bytes or marked decodable, when the corpus passes 1 MB, and when a PNG / GIF / BMP header disagrees with the manifest size.
- Device, `[DEVICE]`: `FormatCorpusDecodeTest` (`app/src/androidTest`). Decodes every `platform` file with the app's `decodeUpright`, checks size and quadrant colours, checks stubs return null, and logs (does not assert) what this device does with the valid-but-unsupported files. It compiles in the build (`:app:compileOfflineDebugAndroidTestKotlin`, `:app:compileConnectDebugAndroidTestKotlin`); running it needs a device or emulator: `:app:connectedOfflineDebugAndroidTest`. The corpus reaches the test APK as assets (`app/build.gradle.kts`, `sourceSets.androidTest.assets`); the app APK does not contain it.

## Adding a file

Add it to the script's table (it writes the file, the manifest row and the SOURCES.md row together), run the script, run `:app:testOfflineDebugUnitTest --tests '*FormatCorpusSniffTest'`. Never commit a real photo or a downloaded sample: the point of the corpus is that its licence is trivially clean.
