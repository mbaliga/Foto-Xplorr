package com.fotoxplorr.app.formats

/**
 * One GIF frame, decoded or about to be encoded -- the common currency [GifDecoder] and
 * [GifEncoder] standardise on so a caller can pass a decoded frame straight into a re-encode
 * (which is exactly what [GifTrimmer] does) without reshaping anything in between.
 *
 * [argb] is `width * height` packed ARGB_8888 pixels, row-major, top-to-bottom -- the exact
 * layout `Bitmap.getPixels`/`setPixels` use, so a frame sourced from (or destined for) a real
 * `Bitmap` needs no conversion either way. [GifDecoder] always emits a frame at the GIF's full
 * logical-screen size, fully composited (backgrounds, disposal, transparency already resolved)
 * -- never a raw sub-rectangle -- so every frame this type carries is immediately self-
 * contained and paintable on its own.
 *
 * @param delayCentiseconds how long this frame is shown, in 1/100s -- the GIF spec's own unit,
 *   kept as-is (not converted to millis) so a decode-then-re-encode round trip through
 *   [GifTrimmer] never rounds it twice. 0 is legal per the spec and conventionally means "as
 *   fast as the decoder can go"; this type does not second-guess that.
 */
data class GifFrame(
    val argb: IntArray,
    val width: Int,
    val height: Int,
    val delayCentiseconds: Int,
) {
    init {
        require(width > 0 && height > 0) { "frame must have positive dimensions, got ${width}x$height" }
        require(argb.size == width * height) {
            "argb has ${argb.size} pixels, expected ${width * height} for a ${width}x$height frame"
        }
        require(delayCentiseconds >= 0) { "delay cannot be negative, got $delayCentiseconds" }
    }

    // Hand-written for content equality: the data class default for an IntArray property
    // compares by REFERENCE, so two frames built separately from identical pixels -- exactly
    // what "expected vs. actual" looks like in a test -- would compare unequal and fail
    // assertEquals for a reason that has nothing to do with the thing being tested.
    override fun equals(other: Any?): Boolean = this === other || (
        other is GifFrame &&
            width == other.width &&
            height == other.height &&
            delayCentiseconds == other.delayCentiseconds &&
            argb.contentEquals(other.argb)
        )

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + delayCentiseconds
        result = 31 * result + argb.contentHashCode()
        return result
    }
}
