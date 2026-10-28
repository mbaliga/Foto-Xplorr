package com.fotoxplorr.app.chroma

/**
 * Why a reading was refused. The chroma code answers a colour or says no: a wrong colour given
 * with confidence is worse than none, so every check that finds the input unusable ends here.
 */
internal enum class Refusal(val message: String) {
    /** Too many pixels of the patch are at the top of the range, so the true colour is hidden. */
    CLIPPED(ChromaCopy.CLIPPED),
    TOO_DARK(ChromaCopy.TOO_DARK),
    UNEVEN(ChromaCopy.UNEVEN),
    TOO_SMALL(ChromaCopy.TOO_SMALL),
    SIZE_MISMATCH(ChromaCopy.SIZE_MISMATCH),

    /** The flash adds little, and the room is already bright. */
    TOO_BRIGHT_ROOM(ChromaCopy.TOO_BRIGHT_ROOM),

    /** The flash adds little, and the room is not bright: the surface is out of the flash's reach. */
    TOO_FAR(ChromaCopy.TOO_FAR),

    /** The flash frame is darker than the ambient one: lamp flicker, a moved hand, or the frames swapped. */
    LIGHT_CHANGED(ChromaCopy.LIGHT_CHANGED),
    CARD_TOO_DIM(ChromaCopy.CARD_TOO_DIM),
    DISTANCE_TOO_DIFFERENT(ChromaCopy.DISTANCE_TOO_DIFFERENT),

    /** A reflectance well above the card's: glare, a tilted card, or a card that is not neutral. */
    NOT_ADDING_UP(ChromaCopy.NOT_ADDING_UP),
}

/** An answer or a refusal; nothing in between. */
internal sealed class Answer<out T> {
    class Value<out T>(val value: T) : Answer<T>()
    class Refused(val reason: Refusal) : Answer<Nothing>()

    /** The value, or null when refused. */
    fun orNull(): T? = (this as? Value)?.value
}

/** A decoded picture: [argb] is [width] x [height] row by row, R, G, B sRGB-encoded (as `Bitmap.getPixels`). */
internal class ChromaFrame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "empty frame" }
        require(argb.size == width * height) { "pixel count ${argb.size} does not match ${width}x$height" }
    }
}

/**
 * The part of a frame to measure, as fractions of its width and height. The default is the middle
 * 40% by 40%, which is where a person aims the phone; a later "tap to pick" can pass any rectangle.
 */
internal class PatchRegion(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && top in 0.0..1.0 && bottom in 0.0..1.0) { "outside the frame" }
        require(left < right && top < bottom) { "empty region" }
    }

    companion object {
        val CENTRE = PatchRegion(0.3, 0.3, 0.7, 0.7)
    }
}
