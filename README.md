# Foto Xplorr — test build (branch claude/fotoz-ui-interactions-bxvgbw)

Debug builds, arm64-v8a only, stripped + 16 KB-page aligned + debug-signed.
Built 14 Aug 2026 from commit f8a0991 plus one uncommitted-until-now fix (see below).

| File | Flavor | Installs as | What it is |
|---|---|---|---|
| `foto-xplorr-offline.apk` | offline | `com.fotoxplorr.app.debug` | No INTERNET permission, no network library. |
| `foto-xplorr-connect.apk` | connect | `com.fotoxplorr.app.connect.debug` | Adds BYOK remote AI, the embedder-model download, and the OpenFreeMap street map. Installs alongside offline. |

## What changed since the last build

- **Likely fix for the native crash loop.** Both "3D photo wall" and "Spatial
  compass" (Places tab) upload photo thumbnails to the GPU at a non-square
  size, then asked the driver to build mipmaps for them. Generating mipmaps
  for a non-power-of-two texture is undefined behaviour in OpenGL ES 2.0
  without an extension most devices don't advertise — some GPU drivers cope,
  some crash natively with no Java stack trace at all, which matches your
  report exactly (native crash, no trace, "Continue" leads straight back into
  it). Removed the mipmap request entirely; those two screens draw with plain
  linear filtering now, which costs a little smoothing at a distance and
  nothing else.

  I can't fully confirm this is *the* cause without a logcat spanning the
  crash — but it's the only genuinely native, GPU-driver-dependent code in
  the app, and this exact bug pattern is a well-known source of driver-level
  native crashes on Android. If it still crashes on this build, please try
  to note what you were doing right before it happened (especially whether
  you were in Places → "3D photo wall" or "Spatial compass") — that detail
  would narrow it down a lot further.

Reinstalling over the previous debug build should update in place.
