package com.fotoxplorr.app.board

import android.graphics.Bitmap
import com.fotoxplorr.app.backup.MediaIdentity
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset

/**
 * Where the renderer gets a photo's pixels. The renderer knows nothing about content URIs, the
 * library or protection: the Android implementation ([EnvPhotoSource]) goes through the Experimental
 * host, and the tests supply fakes that draw flat colours and count their calls.
 */
interface BoardPhotoSource {
    /**
     * Whether [ref] could be decoded right now: it is in the library, may be shown, and may leave
     * the app. Cheap, and asked before an export starts so that a board with a missing photo is
     * refused up front instead of after a minute of drawing.
     */
    fun isAvailable(ref: PhotoRef): Boolean

    /**
     * The photo upright, no larger than [limits] and never upscaled, or null when it cannot be read
     * now (deleted, protected since, undecodable). The caller owns the bitmap and recycles it. It must be
     * a software bitmap (not [Bitmap.Config.HARDWARE]): the renderer draws it on a software canvas.
     * `decodeUpright` gives that.
     */
    suspend fun decode(ref: PhotoRef, limits: DecodeLimits): Bitmap?

    /**
     * The photo's size as it is shown, upright (width, height), or null when it cannot be read now. The
     * library's own width and height are the stored pixels and are swapped for a photo taken in portrait,
     * so a [PhotoRef] is made from this and not from them. Default: not known.
     */
    suspend fun uprightSize(ref: PhotoRef): Pair<Int, Int>? = null
}

/** What a [PhotoRef] is made from, and back. */
object PhotoRefs {
    fun identityOf(asset: MediaAsset) = MediaIdentity(asset.displayName, asset.sizeBytes, asset.dateTakenMillis, asset.relativePath)

    /** The reference a board keeps for [asset]: its id, its identity and its size. */
    fun of(asset: MediaAsset) = PhotoRef(asset.id.value, identityOf(asset), asset.width, asset.height)

    /**
     * The reference a board keeps for [asset] with its UPRIGHT size, read through [source]. The library's
     * width and height are the stored pixels (a portrait phone photo is stored landscape), so using them
     * would give the photo a box of the wrong shape. When the size cannot be read the library's own is kept.
     */
    suspend fun uprightOf(asset: MediaAsset, source: BoardPhotoSource): PhotoRef {
        val ref = of(asset)
        val (w, h) = source.uprightSize(ref) ?: return ref
        return if (w > 0 && h > 0) ref.copy(widthPx = w, heightPx = h) else ref
    }

    /** The photos that may be shown, as the resolver wants them. */
    fun catalogue(assets: List<MediaAsset>): Map<Long, MediaIdentity> = assets.associate { it.id.value to identityOf(it) }
}

/**
 * A [BoardPhotoSource] over the Experimental host's list of photos. The list is already the gallery's
 * browsable one, so a photo that is locked, hidden or trashed is not in it and resolves as missing;
 * [mayExport] and [decode] are the host's, which re-check at the moment of use (TRAPS #33, #78).
 *
 * Refs are resolved once, when the source is made, with [PhotoResolver], so a photo that moved
 * (a restore, a new folder) is still found. Build a new source after the host's list changes.
 */
class EnvPhotoSource(
    private val assets: List<MediaAsset>,
    refs: List<PhotoRef>,
    private val mayExport: (MediaAsset) -> Boolean,
    private val decodeAsset: suspend (MediaAsset, DecodeLimits) -> DecodedBitmap?,
) : BoardPhotoSource {

    private val byId: Map<Long, MediaAsset> = assets.associateBy { it.id.value }
    private val catalogue: Map<Long, MediaIdentity> = PhotoRefs.catalogue(assets)
    private val resolved: Map<Long, PhotoResolution> = PhotoResolver.resolve(refs, catalogue)

    /** The resolutions, for a screen to relink the board or mark placeholders. */
    val resolutions: Map<Long, PhotoResolution> get() = resolved

    /** A ref the source was not made with is resolved on its own (it cannot claim a photo another ref owns). */
    private fun assetOf(ref: PhotoRef): MediaAsset? = when (val r = resolved[ref.mediaId] ?: PhotoResolver.resolve(listOf(ref), catalogue)[ref.mediaId]) {
        is PhotoResolution.Same -> byId[r.mediaId]
        is PhotoResolution.Moved -> byId[r.mediaId]
        else -> null
    }

    override fun isAvailable(ref: PhotoRef): Boolean = assetOf(ref)?.let(mayExport) == true

    override suspend fun decode(ref: PhotoRef, limits: DecodeLimits): Bitmap? =
        assetOf(ref)?.let { decodeAsset(it, limits)?.bitmap }

    /**
     * A small decode through the host, whose [DecodedBitmap.sourceWidth] and `sourceHeight` are the upright
     * size. The pixels settle which way round it is: if the two disagree about portrait or landscape, the
     * pixels are right, because they are what the board draws.
     */
    override suspend fun uprightSize(ref: PhotoRef): Pair<Int, Int>? {
        val asset = assetOf(ref)?.takeIf(mayExport) ?: return null
        val decoded = decodeAsset(asset, SIZE_PROBE) ?: return null
        val bitmap = decoded.bitmap
        val w = decoded.sourceWidth
        val h = decoded.sourceHeight
        val bitmapLandscape = bitmap.width > bitmap.height
        val bitmapPortrait = bitmap.width < bitmap.height
        val swapped = (bitmapLandscape && w < h) || (bitmapPortrait && w > h)
        bitmap.recycle()
        if (w <= 0 || h <= 0) return null
        return if (swapped) h to w else w to h
    }

    companion object {
        /** Small enough to be quick for every photo in a choice of twenty-four; only its shape and the reported source size are used. */
        private val SIZE_PROBE = DecodeLimits(64, 64L * 64L)

        fun of(env: ExperimentEnv, board: Board): EnvPhotoSource =
            EnvPhotoSource(env.assets, board.photoRefs, env::mayExport) { asset, limits -> env.decode(asset, limits) }
    }
}
