package com.fotoxplorr.app.animate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.editor.EditRenderer
import com.fotoxplorr.app.editor.RecipeStore
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.video.deviceEncoderMimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * [AnimateHost] over the Experimental host's [ExperimentEnv]: every pixel of a photo is read through
 * [ExperimentEnv.decode] (which is `decodeUpright` behind the [ExperimentEnv.mayExport] check, TRAPS
 * #33 and #34), and the bytes of an animated file only for a photo that may be exported.
 *
 * ## Videos
 * [ExperimentEnv.mayExport] is false for every video, by design (an experiment that exports pixels
 * exports photos). A video clip still must not be read once its folder is locked, so [mayUseVideo]
 * asks the same live question the host asks for photos, about the same id: it is
 * `mayExport` of a copy of the asset that says it is an image. That reads the host's CURRENT
 * browsable list (not the snapshot in [ExperimentEnv.assets]) and needs no change to the host; if the
 * host's rule for photos ever looks at more than the id, this needs the same change.
 *
 * ## Saved edits
 * [savedEdit] is the editor's autosaved recipe for a photo ([RecipeStore], keyed by id and modified
 * time, so an edit made to an older version of the file is not applied to a newer one). [still]
 * applies a recipe with [EditRenderer.render] to the decoded bitmap, as the editor does for its own
 * preview, at the animation's size: crops and straighten are fractions, so they land the same, and
 * the caller asks for enough extra resolution to allow for a crop. Heals are placed in the final
 * picture's coordinates, so they are applied at the reduced size (the editor previews that way too).
 * Whether a preview-size render matches a full-size one pixel for pixel is the editor's own question.
 */
class ExperimentAnimateHost(private val env: ExperimentEnv) : AnimateHost {
    private val recipes by lazy { RecipeStore(env.context) }

    /** The edit the editor autosaved for [asset], or null: what a screen offers as "use my edits". */
    fun savedEdit(asset: MediaAsset): EditRecipe? = recipes.load(asset)

    override fun mayExport(asset: MediaAsset): Boolean = env.mayExport(asset)

    override fun mayUseVideo(asset: MediaAsset): Boolean = asset.isVideo && env.mayExport(asset.copy(mimeType = AS_IMAGE))

    override suspend fun still(asset: MediaAsset, longEdge: Int, recipe: EditRecipe?): RawFrame? {
        val decoded = try {
            env.decode(asset, DecodeLimits(maxLongEdge = longEdge, maxPixels = longEdge.toLong() * longEdge))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A file that vanished or cannot be opened throws from the decoder; to the job that is "cannot be read".
            null
        } ?: return null
        return withContext(Dispatchers.Default) {
            val source = decoded.bitmap
            var edited: Bitmap? = null
            try {
                val shown = if (recipe == null || recipe.isIdentity) source else EditRenderer.render(source, recipe).also { edited = it }
                rawOf(shown)
            } finally {
                edited?.let { if (!it.isRecycled) it.recycle() }
                if (!source.isRecycled) source.recycle()
            }
        }
    }

    override suspend fun bytes(asset: MediaAsset, maxBytes: Long): ByteArray? {
        if (!env.mayExport(asset)) return null
        val read = withContext(Dispatchers.IO) {
            try {
                env.context.contentResolver.openInputStream(asset.contentUri)?.use { stream -> readUpTo(stream, maxBytes) }
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
        // Locked while it was being read: hand nothing over.
        return if (env.mayExport(asset)) read else null
    }

    /**
     * `BitmapFactory` directly, not `decodeUpright` (TRAPS #34): this is not a photo from the library
     * but one frame image of an animated WebP, in memory, with no EXIF orientation to apply and no size
     * to bound (the container already fixes it). A library photo never comes through here.
     */
    override fun decodeImage(bytes: ByteArray): RawFrame? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        return rawOf(bitmap)
    }

    override fun openVideo(asset: MediaAsset): VideoReader? =
        if (mayUseVideo(asset)) RetrieverVideoReader.open(env.context, asset.contentUri) else null

    override fun canEncodeVideo(): Boolean = runCatching { MediaFormat.MIMETYPE_VIDEO_AVC in deviceEncoderMimeTypes() }.getOrDefault(false)

    /** The pixels of [bitmap], which is given back. */
    private fun rawOf(bitmap: Bitmap): RawFrame? {
        try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            return RawFrame(w, h, px)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun readUpTo(stream: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw AnimSourceException(AnimateCopy.FILE_TOO_BIG)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val AS_IMAGE = "image/jpeg"
    }
}
