package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.targetSize

/**
 * Photos that are flat colours (or a painted pattern), sized the way the real decoder sizes them
 * ([targetSize]), that remember every ask so a test can count decodes and check what was recycled.
 */
internal class FakePhotoSource(
    private val colours: Map<Long, Int> = emptyMap(),
    private val painter: ((ref: PhotoRef, u: Float, v: Float) -> Int)? = null,
    var available: (PhotoRef) -> Boolean = { true },
) : BoardPhotoSource {
    val calls = mutableListOf<Pair<Long, DecodeLimits>>()
    val made = mutableListOf<Bitmap>()
    var outOfMemoryTimes = 0

    /** Called after each bitmap is made, before it is handed over. */
    var onDecoded: () -> Unit = {}

    override fun isAvailable(ref: PhotoRef): Boolean = available(ref)

    override suspend fun decode(ref: PhotoRef, limits: DecodeLimits): Bitmap? {
        calls += ref.mediaId to limits
        if (outOfMemoryTimes > 0) {
            outOfMemoryTimes--
            throw OutOfMemoryError("test")
        }
        if (!available(ref)) return null
        val (w, h) = targetSize(ref.widthPx.coerceAtLeast(1), ref.heightPx.coerceAtLeast(1), limits)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = painter
        if (p == null) {
            bitmap.eraseColor(colours[ref.mediaId] ?: 0xFF808080.toInt())
        } else {
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) px[y * w + x] = p(ref, (x + 0.5f) / w, (y + 0.5f) / h)
            bitmap.setPixels(px, 0, w, 0, 0, w, h)
        }
        made += bitmap
        onDecoded()
        return bitmap
    }
}
