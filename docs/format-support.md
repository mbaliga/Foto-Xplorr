# Image format support policy

## Honest definition of “all image file types”

Literal universal support is impossible. Some formats are proprietary, encrypted, undocumented, device-specific, intentionally adversarial, or merely arbitrary bytes with an image extension. Foto Xlorr therefore defines success as broad, test-backed support with safe fallback and transparent capability reporting.

A format is not “supported” merely because a dependency lists its extension. A claim requires fixtures and explicit results for:

- signature detection;
- dimensions and metadata;
- thumbnail/preview;
- full decode;
- animation or multi-page behavior where applicable;
- alpha, orientation, color profile, and high bit depth where applicable;
- malformed/truncated input;
- memory and decoded-dimension limits;
- supported Android/API/ABI combinations.

## Decoder tiers

### Tier 1 — Android platform

Use Android platform APIs first for formats the device can decode. Typical candidates include JPEG, PNG, GIF, WebP, BMP/WBMP, HEIF/HEIC, and AVIF, but exact capability can vary by Android version, device codec stack, image profile, bit depth, and animation features.

Foto Xlorr must probe and report actual capability rather than assume every device handles every variant.

### Tier 2 — Managed decoders

Use maintained JVM/Kotlin libraries where they are sufficient and safe. SVG is the first priority because vector images are common but not a general platform-raster-decoder guarantee.

A managed decoder still needs limits on document complexity, external resources, recursion, dimensions, and output bytes.

### Tier 3 — Isolated native codecs

Candidate families, subject to license/security/size review:

- JPEG XL;
- TIFF and BigTIFF;
- camera RAW families and DNG;
- PSD/PSB composite previews;
- JPEG 2000;
- OpenEXR;
- QOI;
- TGA, PCX, and other legacy raster formats;
- icon/container formats such as ICO/ICNS;
- multi-page formats where gallery behavior is defined.

These are roadmap targets, not current support claims. Native parsing runs outside the main process and receives only a bounded file descriptor/request.

### Tier 4 — Metadata-only and external-open fallback

When a file is accessible but no safe decoder is available, Foto Xlorr should still show:

- name, source, size, dates, and declared/detected type;
- supported metadata that can be safely extracted;
- decoder status and reason;
- hash and group/tag membership;
- “open with” or export/share where Android permits.

The app must not disguise a missing decoder by silently producing a blank thumbnail.

## Detection

- Prefer file signatures and structural probes over extensions and provider MIME strings.
- Treat MIME, extension, and signature disagreement as a warning.
- Never execute or fetch external content referenced by an image document by default.
- Apply dimension, frame-count, page-count, decoded-byte, recursion, and time budgets before allocating large buffers.

## Animated and multi-frame media

- GIF autoplay is enabled in the current platform viewer when Android returns an animated drawable.
- Autoplay will become configurable and reduced-motion aware.
- Grid animation is not on by default; it wastes battery and can overwhelm the decoder pipeline.
- Multi-page TIFF/ICO/PSD behavior needs an explicit UX: representative frame/page, page scrubber, or stack. It is not automatically equivalent to animation.

## RAW behavior

“RAW support” has several levels:

1. embedded thumbnail only;
2. embedded full preview;
3. sensor mosaic development with camera profile and white-balance controls.

Foto Xlorr should not claim level 3 when it only shows an embedded preview. The compatibility matrix records the level per camera family and file fixture.

## Color and HDR

The decoder pipeline must record whether it preserved:

- embedded ICC profile;
- wide-gamut color;
- HDR gain map or HDR transfer function;
- alpha and premultiplication;
- source bit depth.

The first fallback may render into standard dynamic range, but the viewer must not claim color-accurate HDR until the display and render path are tested end to end.

## Compatibility matrix shape

A generated matrix should eventually live under `compat/`:

| Format/profile | Detect | Metadata | Thumbnail | Full | Animation/pages | Color/HDR | Decoder | Fixtures | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

Every release updates the matrix from tests. User-facing capability is derived from runtime probes plus this tested baseline.

## Dependency acceptance checklist

Before adding a codec:

- active maintenance and vulnerability history reviewed;
- license compatible with the selected project license and distribution model;
- source available and reproducible build path documented;
- supported ABIs and APK size measured;
- fuzzing corpus or upstream fuzzing status evaluated;
- decoded allocation and dimension limits enforceable;
- cancellation/timeouts possible;
- native symbols and notices retained;
- sample files have redistributable provenance.
