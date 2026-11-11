package com.fotoxplorr.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Turns a stored [FaceBox] into the square picture a person's card shows.
 *
 * The seam to Android around [FaceCrop]'s pure geometry: decode the photo upright (TRAPS #34), cut
 * the margined square, scale it to the card. NOT a pure-JVM-testable unit and not run on a device
 * yet; what is tested is the rectangle it cuts ([FaceCropTest]).
 *
 * **Held in memory only.** A face crop is a picture of a person's face; it is never written to
 * disk, never put in Coil's disk cache, and is gone with the process. The cache below is a small
 * byte-bounded LRU so scrolling the cards back and forth does not decode a photo each time.
 *
 * The decode is bounded to 1600 px on the long edge, so a small face in a big photo is still a
 * usable card; two decodes at a time at most, so a screen of cards does not decode a screenful of
 * 12-megapixel photos at once.
 */
object FaceCropLoader {
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(MAX_PARALLEL_DECODES)

    private val limits = DecodeLimits(maxLongEdge = 1600, maxPixels = 1600L * 1600L)

    fun cached(asset: MediaAsset, face: ClusterFace, sizePx: Int): Bitmap? = cache.get(key(asset, face, sizePx))

    /** The card picture, or null if the photo cannot be decoded or the box cannot be cropped. */
    suspend fun load(context: Context, asset: MediaAsset, face: ClusterFace, sizePx: Int): Bitmap? {
        val box = face.box?.takeIf { it.isUsable } ?: return null
        val key = key(asset, face, sizePx)
        cache.get(key)?.let { return it }
        val rect = FaceCrop.squareAround(box) ?: return null
        val made = gate.withPermit {
            val decoded = decodeUpright(context.applicationContext, asset.contentUri, limits) ?: return@withPermit null
            val source = decoded.bitmap
            val (x, y, w, h) = rect.toPixels(source.width, source.height).toList()
            // createBitmap and createScaledBitmap both hand back their input when nothing changes,
            // so each step is recycled only if it is not what the next step (or the caller) holds.
            val cut = Bitmap.createBitmap(source, x, y, w, h)
            val scaled = if (cut.width == sizePx && cut.height == sizePx) cut
            else Bitmap.createScaledBitmap(cut, sizePx, sizePx, true)
            if (cut !== scaled && cut !== source) cut.recycle()
            if (source !== scaled) source.recycle()
            scaled
        } ?: return null
        cache.put(key, made)
        return made
    }

    /** Drops every picture held: called when protected folders lock, so nothing outlives the unlock. */
    fun clear() = cache.evictAll()

    private fun key(asset: MediaAsset, face: ClusterFace, sizePx: Int) =
        "${asset.id.value}:${asset.dateModifiedSeconds}:${face.faceIndex}:$sizePx"

    private const val CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_PARALLEL_DECODES = 2
}
