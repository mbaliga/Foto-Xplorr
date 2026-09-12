package com.fotoxplorr.app.editor

/**
 * A format the editor can actually WRITE — as opposed to [com.fotoxplorr.app.formats.MediaFormat],
 * which classifies what Android can *read*. The two questions have different answers: `Bitmap`'s
 * own encoder only ever produces JPEG, PNG or WebP (see `Bitmap.CompressFormat`), regardless of
 * how many formats `BitmapFactory` can decode.
 *
 * Pure and framework-free on purpose, like [Adjustments] and [AutoFix] — [EditedCopyWriter] is the
 * one place this gets turned into an actual `Bitmap.CompressFormat` and quality value, since that
 * mapping depends on `Build.VERSION.SDK_INT` (WebP's lossy/lossless split arrived in API 30) and
 * has no business complicating a value this simple.
 */
enum class OutputFormat(val label: String, val extension: String, val mimeType: String) {
    JPEG("JPEG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp"),
}

/**
 * The format a saved COPY keeps by default when nobody has explicitly asked for a different one —
 * "the same format the photo already was" rather than always forcing JPEG the way this editor used
 * to.
 *
 * Only JPEG, PNG and WebP sources map to themselves: every other source (HEIC, GIF, BMP, an
 * embedded RAW preview, ...) falls back to JPEG, because [OutputFormat] only lists the three
 * formats `Bitmap.compress` can produce at all — "preserve the original format" cannot mean a
 * format this app has no encoder for.
 */
fun outputFormatFor(sourceMimeType: String?): OutputFormat = when (sourceMimeType) {
    OutputFormat.PNG.mimeType -> OutputFormat.PNG
    OutputFormat.WEBP.mimeType -> OutputFormat.WEBP
    else -> OutputFormat.JPEG
}

/**
 * The format an in-place [EditedCopyWriter.overwrite] would re-encode into, or `null` when
 * [sourceMimeType] cannot be replaced in place at all.
 *
 * Deliberately narrower than [outputFormatFor]: overwriting means writing new bytes into the
 * ORIGINAL file's own container, so the result must stay the format that file already is — a JPEG
 * cannot become a PNG without also changing its extension, which a same-Uri, same-row overwrite
 * has no way to do. A source in any format this app has no [OutputFormat] for (HEIC, GIF, RAW, ...)
 * has no honest answer here at all, which is exactly why this returns nullable rather than a
 * fallback like [outputFormatFor] does for a fresh copy.
 */
fun overwriteFormatFor(sourceMimeType: String?): OutputFormat? =
    OutputFormat.entries.firstOrNull { it.mimeType == sourceMimeType }
