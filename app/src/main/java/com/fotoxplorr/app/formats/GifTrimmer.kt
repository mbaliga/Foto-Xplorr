package com.fotoxplorr.app.formats

/**
 * "Keep only frames [startFrame, endFrame] of this GIF." Deliberately thin: [GifDecoder] and
 * [GifEncoder] do all the real work, kept separate from each other and from this so a caller
 * that only wants to build a frame-picker UI (decode) or only has frames from somewhere else
 * to write out (encode) is not forced through both.
 */
object GifTrimmer {

    /**
     * @param bytes the trimmed GIF, ready to write to disk or hand to Coil.
     * @param frameCount how many frames [bytes] actually contains -- `endFrame - startFrame + 1`,
     *   surfaced so a caller can show "3 of 3 frames" without decoding its own output back.
     * @param originalFrameCount how many frames [source] had, so the caller can tell the user
     *   what got cut ("12 -> 3 frames").
     */
    data class TrimResult(
        val bytes: ByteArray,
        val frameCount: Int,
        val originalFrameCount: Int,
    ) {
        // Same reasoning as GifFrame: a data class default treats `bytes` as reference-equal,
        // which would fail an assertEquals between two results built from identical content.
        override fun equals(other: Any?): Boolean = this === other || (
            other is TrimResult &&
                frameCount == other.frameCount &&
                originalFrameCount == other.originalFrameCount &&
                bytes.contentEquals(other.bytes)
            )

        override fun hashCode(): Int {
            var result = frameCount
            result = 31 * result + originalFrameCount
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /**
     * @param startFrame first frame to KEEP, inclusive, 0-indexed.
     * @param endFrame last frame to keep, inclusive.
     * @param loopForever whether the trimmed GIF should loop -- see [GifEncoder.encode].
     */
    fun trim(source: ByteArray, startFrame: Int, endFrame: Int, loopForever: Boolean = true): TrimResult {
        val decoded = GifDecoder.decode(source)
        require(startFrame in decoded.frames.indices) {
            "startFrame $startFrame out of range 0..${decoded.frames.lastIndex}"
        }
        require(endFrame in startFrame..decoded.frames.lastIndex) {
            "endFrame $endFrame must be between startFrame ($startFrame) and ${decoded.frames.lastIndex}"
        }

        val selected = decoded.frames.subList(startFrame, endFrame + 1)
        val bytes = GifEncoder.encode(selected, loopForever)
        return TrimResult(bytes = bytes, frameCount = selected.size, originalFrameCount = decoded.frames.size)
    }
}
