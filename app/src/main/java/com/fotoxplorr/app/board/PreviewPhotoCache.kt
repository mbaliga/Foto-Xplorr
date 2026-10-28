package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.targetSize

/**
 * A [BoardPhotoSource] that remembers decoded photos, for the editor's preview only. While a layer is
 * dragged the preview is drawn again and again, and without this each draw would decode every photo
 * from its file. An export never uses it: it reads through the host's own source so that every photo
 * is decoded, and checked, at the moment it is used.
 *
 *  - **Availability is never cached.** [isAvailable] asks the inner source every time, and so does
 *    [decode] before it returns anything it holds: a photo that was locked or hidden since it was
 *    remembered is dropped from memory and answers null (TRAPS #33).
 *  - **The caller still owns what it is given.** Every bitmap handed out is a copy (the caller recycles
 *    it, as the renderer does), so recycling it cannot hurt the remembered one.
 *  - **Sized for the biggest ask.** A photo remembered at one size answers any request no larger than
 *    it, scaled down; a larger request decodes again and replaces the old one.
 *  - **Bounded.** The remembered bitmaps together stay under [maxBytes]; the one used longest ago goes
 *    first. A photo bigger than the bound is handed over as it was decoded and not kept.
 *
 * [clear] recycles everything; call it when the editor is left. Safe to call from any thread.
 */
class PreviewPhotoCache(
    private val inner: BoardPhotoSource,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : BoardPhotoSource {

    private class Entry(val bitmap: Bitmap, val limits: DecodeLimits)

    private val entries = object : LinkedHashMap<Long, Entry>(16, 0.75f, true) {}
    private var bytes = 0L

    /** How many photos are remembered, and how many bytes they hold. For tests and the memory note. */
    val size: Int get() = synchronized(this) { entries.size }
    val heldBytes: Long get() = synchronized(this) { bytes }

    override fun isAvailable(ref: PhotoRef): Boolean = inner.isAvailable(ref)

    override suspend fun decode(ref: PhotoRef, limits: DecodeLimits): Bitmap? {
        if (!inner.isAvailable(ref)) {
            forget(ref.mediaId)
            return null
        }
        copyOf(ref.mediaId, limits)?.let { return it }
        val fresh = inner.decode(ref, limits) ?: run {
            forget(ref.mediaId)
            return null
        }
        val cost = byteCount(fresh)
        if (cost > maxBytes) {
            forget(ref.mediaId)
            return fresh
        }
        synchronized(this) {
            entries.remove(ref.mediaId)?.let { bytes -= byteCount(it.bitmap); it.bitmap.recycle() }
            entries[ref.mediaId] = Entry(fresh, limits)
            bytes += cost
            trim()
        }
        return copyOf(ref.mediaId, limits)
    }

    /** A copy of the remembered photo shrunk to what [limits] allow, or null if none is remembered or it was decoded smaller than asked. */
    private fun copyOf(id: Long, limits: DecodeLimits): Bitmap? = synchronized(this) {
        val entry = entries[id] ?: return null
        if (limits.maxLongEdge > entry.limits.maxLongEdge || limits.maxPixels > entry.limits.maxPixels) return null
        val b = entry.bitmap
        if (b.isRecycled) return null
        val (w, h) = targetSize(b.width, b.height, limits)
        val width = w.coerceIn(1, b.width)
        val height = h.coerceIn(1, b.height)
        if (width == b.width && height == b.height) b.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(b, width, height, true).let { scaled -> if (scaled === b) b.copy(Bitmap.Config.ARGB_8888, false) else scaled }
    }

    private fun forget(id: Long) = synchronized(this) {
        entries.remove(id)?.let { bytes -= byteCount(it.bitmap); it.bitmap.recycle() }
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            // Never the one just stored: it fits on its own, so everything older goes first.
            if (entries.size == 1) break
            bytes -= byteCount(oldest.value.bitmap)
            oldest.value.bitmap.recycle()
            iterator.remove()
        }
    }

    fun clear() = synchronized(this) {
        entries.values.forEach { it.bitmap.recycle() }
        entries.clear()
        bytes = 0L
    }

    private fun byteCount(b: Bitmap): Long = b.width.toLong() * b.height * 4L

    companion object {
        /** About thirty 1000 px photos; the preview decodes smaller than that. */
        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024
    }
}
