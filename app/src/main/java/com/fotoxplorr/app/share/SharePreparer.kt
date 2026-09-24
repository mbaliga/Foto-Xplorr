package com.fotoxplorr.app.share

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaId
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.pro.LocalProEntitlement
import com.fotoxplorr.app.pro.ProEntitlement
import com.fotoxplorr.app.video.RemuxResult
import com.fotoxplorr.app.video.findRotationDegrees
import com.fotoxplorr.app.video.remux
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The outcome of preparing one shared item.
 *
 * [SharePreparer.prepare] returns one of these per input item rather than throwing on the first
 * one that can't be prepared (P0-04) -- an undecodable photo in a 20-item share used to fail the
 * whole share with no indication which item was the problem.
 */
sealed interface PreparedItem {
    /** @param note a short, user-facing caveat -- e.g. that this item was converted to a
     * different format to remove its location -- or null when nothing is worth mentioning. */
    data class Ready(val uri: Uri, val mimeType: String, val note: String? = null) : PreparedItem
    data class Failed(val asset: MediaAsset, val reason: String) : PreparedItem
}

/**
 * Turns the photos a user chose to share into files that are safe and branded to send.
 *
 * Replaces `CleanShareExporter`, which could only do one thing (strip EXIF) and only when the user
 * remembered to pick "share without metadata" from a menu. Stripping is now the **default** on
 * every share (owner, 2026-08-15), and the frame options ride the same pass, because both need
 * exactly the same thing: a copy in the cache directory handed out as a FileProvider URI.
 *
 * The original is never opened for writing, in any path through this class.
 *
 * The watermark is resolved against [ProEntitlement] in here, not trusted from the caller's
 * [ShareOptions] as handed in -- a pattern kept from the 2026-08-21 decision that first
 * centralised this resolution, even though what it now resolves TO has changed: Phase 1 owner
 * decision 3 (24 Sep 2026) turns the mark off for everyone, gating nothing on Pro status until a
 * monetization model is chosen (see [ShareOptions.resolveWatermark]). The share sheet's own switch
 * is disabled and unchecked for everyone now, but the sheet is UI, and UI is not the only caller
 * this class can ever have or the only place a bug could put the wrong value in
 * [ShareOptions.watermark] -- see [ShareOptions.resolvedFor], which is the actual decision and is
 * what makes this class, not the sheet, the thing a future caller cannot argue its way around.
 */
class SharePreparer(
    context: Context,
    private val entitlement: ProEntitlement = LocalProEntitlement(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val authority = fileProviderAuthority(appContext)

    /**
     * @param allowRender when false, every item takes the plain copy/strip path regardless of
     *   [options] or Pro status -- never a frame, never a watermark. This is the one seam that
     *   *can* override the watermark decision the class doc above says no caller can argue its way
     *   out of: it exists for [ZipExporter] alone (P0-06's own explicit "plain path only: never a
     *   frame or watermark" requirement for an archive export, which is a faithful copy of the
     *   user's own files, not a branded, shared-to-the-world artifact the way a plain share is),
     *   not exposed through [ShareOptions] itself where an ordinary caller could reach it.
     * @return one [PreparedItem] per input item, in order -- never throws for an individual
     *   item's own failure; only [Result.failure] for something that stops the whole batch before
     *   any item is even attempted (no items, or the share cache directory itself is unusable).
     */
    suspend fun prepare(
        items: List<MediaAsset>,
        options: ShareOptions,
        allowRender: Boolean = true,
        animatedIds: Set<MediaId> = emptySet(),
    ): Result<List<PreparedItem>> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.isNotEmpty()) { "No photos selected" }

            // Resolved ONCE, here, before anything downstream asks requiresRender or touches a
            // pixel -- see the class doc above for why this class re-decides the watermark rather
            // than trusting it, and the ShareOptions doc for why resolving late would also charge
            // a Pro share for a render it does not need.
            val resolvedOptions = options.resolvedFor(entitlement.isPro.value)

            val directory = File(appContext.cacheDir, SHARE_DIRECTORY).apply {
                // Cleared each time: these are transient hand-offs, and a share cache that only
                // grows is a privacy problem as much as a disk one -- it would keep unstripped
                // intermediates of everything ever shared.
                deleteRecursively()
                check(mkdirs() || isDirectory) { "Could not prepare share storage" }
            }

            items.map { asset -> prepareOne(asset, resolvedOptions, directory, allowRender, asset.id in animatedIds) }
        }
    }

    /**
     * One item's own failure (an undecodable file, a format [MetadataStripper] can't parse at
     * all, a strip this class can't then verify is actually clean) becomes a [PreparedItem.Failed]
     * here rather than aborting [prepare] for every other item in the batch. [CancellationException]
     * is the one thing let through -- a cancelled share should stop, not "fail" item by item.
     */
    private suspend fun prepareOne(
        asset: MediaAsset,
        options: ShareOptions,
        directory: File,
        allowRender: Boolean,
        animated: Boolean,
    ): PreparedItem {
        // Video cannot be framed or stripped by this path, so it is shared as-is rather than
        // failed. Refusing to share a video because a frame was selected for the photos beside it
        // would be the app being clever at the user's expense.
        val strippable = !asset.isVideo && asset.mimeType.startsWith("image/")
        // A real animated image (P0-14's AnimationIndex, sniffed from actual bytes -- not every
        // GIF/WebP/AVIF by MIME type, which used to include every static one of those formats
        // too) is never rendered with a frame or watermark: baking one static frame over the top
        // would silently destroy the animation on every viewer that respects it. It is still
        // stripped like any other image, just never routed through prepareRendered. An asset the
        // index has not sniffed YET (freshly imported, before the next background pass catches up)
        // reads as `animated = false` here -- a narrow, temporary window where a fresh animated
        // file could be framed before its row exists, accepted rather than adding a three-state
        // known-animated/known-static/unknown model for a gap the background pass closes within
        // moments of the next scan.
        val renderable = strippable && !animated && allowRender
        return try {
            when {
                renderable && options.requiresRender -> prepareRendered(asset, options, directory)
                strippable && options.stripMetadata -> prepareStripped(asset, directory)
                asset.isVideo && options.stripMetadata -> prepareRemuxedVideo(asset, directory)
                else -> prepareRawCopy(asset, directory)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PreparedItem.Failed(asset, error.message ?: "Could not prepare ${asset.displayName}")
        }
    }

    private suspend fun prepareRendered(asset: MediaAsset, options: ShareOptions, directory: File): PreparedItem {
        val extension = if (options.frame == ShareFrame.STAMP) "png" else "jpg"
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            renderFramed(asset, options, target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        val mimeType = if (options.frame == ShareFrame.STAMP) "image/png" else "image/jpeg"
        return PreparedItem.Ready(FileProvider.getUriForFile(appContext, authority, target), mimeType)
    }

    /** Video, a non-image, or the user deliberately chose to keep metadata -- copied through with
     * nothing touched, same as before P0-04. */
    private fun prepareRawCopy(asset: MediaAsset, directory: File): PreparedItem {
        val extension = asset.displayName.substringAfterLast('.', "jpg")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "jpg"
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            copyRaw(asset, target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return PreparedItem.Ready(FileProvider.getUriForFile(appContext, authority, target), asset.mimeType)
    }

    /**
     * P0-05: a video's location lives in the container, not any track's own samples, so removing
     * it means writing a new container without ever copying that tag across -- [remux] never calls
     * `MediaMuxer.setLocation`. A codec [containerFor] cannot place, or any muxer exception, means
     * the same thing to a sharer: this video cannot be shared with its location removed today, so
     * it becomes [PreparedItem.Failed] with an actionable reason (turn stripping off to send the
     * original) rather than silently going out with the location intact.
     */
    private suspend fun prepareRemuxedVideo(asset: MediaAsset, directory: File): PreparedItem {
        var target = File(directory, "${UUID.randomUUID()}.tmp")
        return try {
            val rotation = findRotationDegrees(appContext, asset.contentUri)
            val result = remux(appContext, asset.contentUri, target, rangeUs = null, rotationDegrees = rotation)
            val container = when (result) {
                is RemuxResult.Unsupported -> {
                    target.delete()
                    return PreparedItem.Failed(asset, VIDEO_LOCATION_STRIP_UNSUPPORTED)
                }
                is RemuxResult.Remuxed -> result.container
            }
            val renamed = File(directory, "${target.nameWithoutExtension}.${container.extension}")
            check(target.renameTo(renamed)) { "Could not finish preparing ${asset.displayName}" }
            target = renamed

            if (!isFreeOfLocation(target)) {
                target.delete()
                return PreparedItem.Failed(asset, VIDEO_LOCATION_STRIP_UNSUPPORTED)
            }
            PreparedItem.Ready(FileProvider.getUriForFile(appContext, authority, target), container.mimeType)
        } catch (error: CancellationException) {
            target.delete()
            throw error
        } catch (error: Throwable) {
            // A muxer exception (a corrupt source, an edge case containerFor's own check didn't
            // catch) means the same thing to a sharer as Unsupported: this video's location can't
            // be removed today.
            target.delete()
            PreparedItem.Failed(asset, VIDEO_LOCATION_STRIP_UNSUPPORTED)
        }
    }

    /** [android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION] on the remuxed output is null
     * -- re-checked here rather than merely trusted from [remux] never having called
     * `setLocation`, the same "never hand out unverified" contract [verifyNoLocationMetadata]
     * holds images to. A read failure counts as "not clean": fail closed, same as there. */
    private fun isFreeOfLocation(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION) == null
        } catch (error: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * The default share path: stream the source through [MetadataStripper] rather than decoding
     * and re-encoding it, so a plain share stays cheap and pixel-lossless. A format
     * [MetadataStripper] does not have a parser for falls back to [reencodeToRemoveLocation]
     * rather than going out unstripped -- the P0-04 defect this whole file exists to close.
     */
    private suspend fun prepareStripped(asset: MediaAsset, directory: File): PreparedItem {
        var activeFile = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            val result = resolver.openInputStream(asset.contentUri)?.use { input ->
                activeFile.outputStream().buffered().use { output -> MetadataStripper.strip(input, output) }
            } ?: throw IOException("Could not read ${asset.displayName}")

            when (result) {
                is MetadataStripper.StripResult.Unsupported -> {
                    activeFile.delete()
                    return reencodeToRemoveLocation(asset, directory)
                }
                is MetadataStripper.StripResult.Stripped -> {
                    val target = File(directory, "${UUID.randomUUID()}.${result.format.extension}")
                    check(activeFile.renameTo(target)) { "Could not finish preparing ${asset.displayName}" }
                    activeFile = target
                    if (result.format == MetadataStripper.Format.JPEG) restoreOrientationOnly(asset, target)
                    val failure = verifyNoLocationMetadata(target, result.format)
                    if (failure != null) {
                        target.delete()
                        return PreparedItem.Failed(asset, failure)
                    }
                    return PreparedItem.Ready(
                        FileProvider.getUriForFile(appContext, authority, target),
                        result.format.mimeType,
                    )
                }
            }
        } catch (error: CancellationException) {
            activeFile.delete()
            throw error
        } catch (error: Throwable) {
            activeFile.delete()
            throw error
        }
    }

    /**
     * [MetadataStripper] found a format it has no verified-safe parser for (HEIC/HEIF, TIFF, a RAW
     * variant, ...). Rather than sending it out unstripped -- the exact defect P0-04 closes -- this
     * decodes it (through [decodeUpright], so it comes out upright regardless of source EXIF),
     * re-encodes it as a format this class knows carries no metadata of its own, and verifies that
     * output exactly like a streamed strip. PNG only when the source has real transparency to
     * preserve; JPEG otherwise, since it is dramatically smaller for a photo.
     */
    private suspend fun reencodeToRemoveLocation(asset: MediaAsset, directory: File): PreparedItem {
        val decoded = decodeUpright(appContext, asset.contentUri, UNSUPPORTED_FORMAT_REENCODE_LIMITS)?.bitmap
            ?: return PreparedItem.Failed(asset, "Could not read ${asset.displayName}")
        val hasAlpha = decoded.hasAlpha()
        val compressFormat = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (hasAlpha) "png" else "jpg"
        val mimeType = if (hasAlpha) "image/png" else "image/jpeg"
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            try {
                target.outputStream().buffered().use { out ->
                    check(decoded.compress(compressFormat, REENCODE_JPEG_QUALITY, out)) {
                        "Could not encode ${asset.displayName}"
                    }
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        } finally {
            decoded.recycle()
        }
        val verifyFormat = if (hasAlpha) MetadataStripper.Format.PNG else MetadataStripper.Format.JPEG
        val failure = verifyNoLocationMetadata(target, verifyFormat)
        if (failure != null) {
            target.delete()
            return PreparedItem.Failed(asset, failure)
        }
        val note = "Converted to ${if (hasAlpha) "PNG" else "JPEG"} to remove its location"
        return PreparedItem.Ready(FileProvider.getUriForFile(appContext, authority, target), mimeType, note)
    }

    /**
     * [MetadataStripper] drops a JPEG's APP1 segment whole, which is where `Orientation` lives --
     * and unlike [renderFramed], this path streams the source's own pixels through unchanged, so a
     * sideways source needs its `Orientation` written back or the shared copy displays rotated in
     * an EXIF-respecting viewer. [ExifInterface] can add a fresh APP1 to a JPEG that has none.
     * Best-effort, matching [copyBackKeptExif] below: a failure here is cosmetic, not a privacy
     * problem, so it must not fail an otherwise-clean, already-verified share.
     */
    private fun restoreOrientationOnly(asset: MediaAsset, target: File) {
        val orientation = resolver.openInputStream(asset.contentUri)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) {
            return
        }
        runCatching {
            val exif = ExifInterface(target.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
    }

    /**
     * The check that makes [prepareStripped] and [reencodeToRemoveLocation] trustworthy rather
     * than merely hopeful: re-reads the exact bytes this class is about to hand to another app and
     * confirms they are actually clean, instead of assuming the pass above did its job. Never
     * skipped, never soft-failed -- see the class doc's "never hand out unverified" contract.
     *
     * @return a user-facing failure reason, or null when [target] is clean.
     */
    private fun verifyNoLocationMetadata(target: File, format: MetadataStripper.Format): String? {
        if (format !in VERIFIABLE_FORMATS) return null
        val clean = runCatching {
            val hasGpsTag = ExifInterface(target.absolutePath).latLong != null
            val bytes = target.readBytes()
            !hasGpsTag && LOCATION_MARKERS.none(bytes::containsAscii)
        }.getOrDefault(false)
        return if (clean) null else "Could not confirm this copy was free of its location"
    }

    /**
     * Decode, frame, re-encode.
     *
     * A framed share is re-encoded, so EXIF does not survive the round trip regardless of the
     * strip setting -- which means a frame is always at least as private as a plain share, never
     * less. When the user asked to KEEP metadata, the handful of tags worth carrying are copied
     * back onto the output explicitly rather than being silently lost.
     */
    private suspend fun renderFramed(asset: MediaAsset, options: ShareOptions, target: File) {
        val source = decodeUpright(appContext, asset.contentUri, SHARE_FRAME_LIMITS)?.bitmap
            ?: throw IOException("Could not read ${asset.displayName}")
        try {
            val framed = FrameRenderer.render(source, options)
            try {
                // PNG, not JPEG: the stamp's perforations are real transparency, and JPEG has no
                // alpha channel -- encoding it as JPEG would fill every notch with black.
                val format = if (options.frame == ShareFrame.STAMP) {
                    Bitmap.CompressFormat.PNG
                } else {
                    Bitmap.CompressFormat.JPEG
                }
                target.outputStream().buffered().use { out ->
                    check(framed.compress(format, JPEG_QUALITY, out)) {
                        "Could not encode the framed copy"
                    }
                }
            } finally {
                if (framed !== source) framed.recycle()
            }
            if (!options.stripMetadata && options.frame != ShareFrame.STAMP) {
                copyBackKeptExif(asset, target)
            }
        } finally {
            source.recycle()
        }
    }

    private fun copyRaw(asset: MediaAsset, target: File) {
        resolver.openInputStream(asset.contentUri)?.use { input ->
            target.outputStream().buffered().use { output ->
                input.copyTo(output, COPY_BUFFER_SIZE)
            }
        } ?: throw IOException("Could not read ${asset.displayName}")
    }

    /**
     * Date only, plus an explicit upright orientation -- enough that a kept-metadata share is not
     * visibly broken.
     *
     * [renderFramed] now decodes through [decodeUpright] (P0-03), so the pixels it renders and
     * writes to [target] are already upright; copying the source's own `Orientation` tag onto
     * [target], as this used to do, would tell an EXIF-respecting viewer to rotate an already-
     * rotated image a second time. Writing `Orientation = 1` here, rather than just leaving the
     * tag unset, follows [decodeUpright]'s own documented contract for every caller that writes
     * EXIF at all.
     */
    private fun copyBackKeptExif(asset: MediaAsset, target: File) {
        runCatching {
            val outputExif = ExifInterface(target.absolutePath)
            resolver.openInputStream(asset.contentUri)?.use { input ->
                val sourceExif = ExifInterface(input)
                KEPT_EXIF_TAGS.forEach { tag ->
                    sourceExif.getAttribute(tag)?.let { outputExif.setAttribute(tag, it) }
                }
            }
            outputExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            outputExif.saveAttributes()
        }
    }

    internal companion object {
        /**
         * The cache sub-directory shared copies are written to.
         *
         * Must match a `<cache-path>` in `res/xml/file_paths.xml`, or `getUriForFile` throws
         * "Failed to find configured root that contains ..." on every share. Nothing in the build
         * checks that; [com.fotoxplorr.app.share.ShareDirectoryTest] does, which is why this is
         * internal rather than private.
         */
        const val SHARE_DIRECTORY = "outgoing-share"
        const val COPY_BUFFER_SIZE = 128 * 1024
        const val JPEG_QUALITY = 92

        /** P0-05: shown when a video's codec can't be remuxed into a container this class knows
         * carries no location, or the remux itself throws. */
        const val VIDEO_LOCATION_STRIP_UNSUPPORTED =
            "This video's format can't be shared without its location yet — turn off " +
                "\"Remove location and camera data\" to send the original."

        /** Longest edge of a re-rendered share, in pixels. */
        const val MAX_SHARE_EDGE = 3200
        val SHARE_FRAME_LIMITS = DecodeLimits(maxLongEdge = MAX_SHARE_EDGE, maxPixels = MAX_SHARE_EDGE.toLong() * MAX_SHARE_EDGE)

        /** Limits for [reencodeToRemoveLocation]'s decode of a format [MetadataStripper] can't
         * parse -- generous, since this is the one path where re-encoding is the only option at
         * all, not a size-driven downscale like [SHARE_FRAME_LIMITS]. */
        const val MAX_REENCODE_EDGE = 8192
        const val MAX_REENCODE_PIXELS = 48_000_000L
        val UNSUPPORTED_FORMAT_REENCODE_LIMITS =
            DecodeLimits(maxLongEdge = MAX_REENCODE_EDGE, maxPixels = MAX_REENCODE_PIXELS)
        const val REENCODE_JPEG_QUALITY = 95

        /** Orientation is deliberately not in this list -- see [copyBackKeptExif]. */
        val KEPT_EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
        )

        /** Formats [ExifInterface] can itself read GPS from, and so the only ones
         * [verifyNoLocationMetadata] can check that way. */
        val VERIFIABLE_FORMATS = setOf(
            MetadataStripper.Format.JPEG,
            MetadataStripper.Format.PNG,
            MetadataStripper.Format.WEBP,
        )

        /** Scanned for as a belt-and-suspenders check alongside [ExifInterface]'s own GPS read --
         * these are the ASCII substrings XMP location fields are written as, which a structured
         * EXIF-tag read alone would not catch if a strip left an XMP packet behind. */
        val LOCATION_MARKERS = listOf(
            "GPSLatitude",
            "GPSLongitude",
            "exif:GPS",
            "LocationShown",
            "LocationCreated",
        )
    }
}

private fun ByteArray.containsAscii(needle: String): Boolean {
    val pattern = needle.toByteArray(Charsets.US_ASCII)
    if (pattern.isEmpty() || pattern.size > size) return false
    outer@ for (i in 0..size - pattern.size) {
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) continue@outer
        }
        return true
    }
    return false
}
