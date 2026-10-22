package com.fotoxplorr.app.wall

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.editor.carriesLocation
import com.fotoxplorr.app.editor.exportEdgeLadder
import com.fotoxplorr.app.editor.exportQuality
import com.fotoxplorr.app.editor.renderDownTheLadder
import com.fotoxplorr.app.lift.pruneStaleFiles
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.metadata.ExifCopier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// The pure part: sizes, the crop, the density tags and the file name
// ---------------------------------------------------------------------------

/** A print's size in cm. Never swapped or rotated: the frame's width is the file's width. */
internal data class PrintSize(val wCm: Double, val hCm: Double) {
    val aspect: Double get() = wCm / hCm
}

/** A whole-pixel rectangle in a photo. */
internal data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** What kept a file below the dpi that was asked for. */
internal enum class PrintLimit {
    /** Nothing: the file has the full size at the dpi asked for. */
    NONE,

    /** The photo has fewer pixels than the size needs, and is not made up to size. */
    PHOTO,

    /** The largest file the export allows (edge or pixel count). */
    SIZE_LIMIT,
}

/**
 * The file to make: [outW] x [outH] pixels, [dpi] in its density tags, from the [crop] of the upright
 * photo. [enlarged]: the file has more pixels than the crop holds, so it is made by enlarging
 * ([lanczosResample]) and says so.
 */
internal data class PrintPlan(
    val outW: Int,
    val outH: Int,
    val dpi: Int,
    val crop: PixelRect,
    val limitedBy: PrintLimit,
    val enlarged: Boolean = false,
)

/**
 * The part of a [srcW] x [srcH] photo that fills a frame of [aspect] (width over height), centred:
 * the photo is taken to cover the frame and what sticks out is cut off, the same as the wall
 * mock-up shows it. Always inside the photo and at least 1 x 1.
 */
internal fun coverRect(srcW: Int, srcH: Int, aspect: Double): PixelRect {
    val photoAspect = srcW.toDouble() / srcH
    val w: Int
    val h: Int
    if (aspect < photoAspect) {
        w = (srcH * aspect).roundToInt().coerceIn(1, srcW)
        h = srcH
    } else {
        w = srcW
        h = (srcW / aspect).roundToInt().coerceIn(1, srcH)
    }
    return PixelRect((srcW - w) / 2, (srcH - h) / 2, w, h)
}

internal object PrintPlanner {
    /** The density a print file is made for: what a photo lab asks for. */
    const val TARGET_DPI = 300

    /**
     * The file for a [size] print of an upright [srcW] x [srcH] photo (`DecodedBitmap.sourceWidth/Height`,
     * not `MediaAsset.width/height`: TRAPS #48), or null when a number makes no sense.
     *
     * The file is [wantDpi] over the full size unless the photo has fewer pixels (it is never made
     * up to size: no pixel is invented here) or the export limits are lower; either way the
     * density tag is what the pixels really give at that size, so file and tag together still say
     * the printed size. Uniform scale, so the shape stays the frame's.
     *
     * With [enlarge] the photo's own pixel count no longer holds the file back: it is made up to
     * [wantDpi] by enlarging, held only by [maxEdge] and [maxPixels] (the editor's 8192 px and 64 MP).
     * [PrintPlan.enlarged] is true only when that really means more pixels than the crop has; a photo
     * with enough pixels plans exactly as it would without.
     */
    fun plan(
        srcW: Int,
        srcH: Int,
        size: PrintSize,
        wantDpi: Int = TARGET_DPI,
        maxEdge: Int = MAX_EXPORT_EDGE,
        maxPixels: Long = MAX_EXPORT_PIXELS,
        enlarge: Boolean = false,
    ): PrintPlan? {
        if (srcW <= 0 || srcH <= 0 || !(size.wCm > 0.0) || !(size.hCm > 0.0) || wantDpi <= 0 || maxEdge <= 0 || maxPixels <= 0) return null
        val crop = coverRect(srcW, srcH, size.aspect)
        val wantW = size.wCm / PrintMath.CM_PER_INCH * wantDpi
        val wantH = size.hCm / PrintMath.CM_PER_INCH * wantDpi
        val byPhoto = if (enlarge) Double.POSITIVE_INFINITY else min(crop.width / wantW, crop.height / wantH)
        val byLimit = min(maxEdge / max(wantW, wantH), sqrt(maxPixels / (wantW * wantH)))
        val scale = min(1.0, min(byPhoto, byLimit))
        val limit = when {
            scale >= 1.0 -> PrintLimit.NONE
            byPhoto <= byLimit -> PrintLimit.PHOTO
            else -> PrintLimit.SIZE_LIMIT
        }
        var w = max(1, (wantW * scale).roundToInt())
        var h = max(1, (wantH * scale).roundToInt())
        if (w.toLong() * h > maxPixels) {
            w = max(1, floor(wantW * scale).toInt())
            h = max(1, floor(wantH * scale).toInt())
        }
        // Rounding must not take the file past the part of the photo it comes from (unless it is
        // being enlarged), or the limits.
        w = min(w, if (enlarge) maxEdge else min(crop.width, maxEdge))
        h = min(h, if (enlarge) maxEdge else min(crop.height, maxEdge))
        val dpi = (w / (size.wCm / PrintMath.CM_PER_INCH)).roundToInt().coerceIn(1, JfifDensity.MAX_DPI)
        return PrintPlan(w, h, dpi, crop, limit, enlarged = enlarge && (w > crop.width || h > crop.height))
    }

    /**
     * Whether the file for [size] could be enlarged, and the picture is worth offering it for: the
     * photo gives less than [PrintQuality.GOOD] dots per inch at that size (cover crop included)
     * and enlarging really adds pixels. Not offered for a photo that is already fine.
     */
    fun canEnlarge(srcW: Int, srcH: Int, size: PrintSize): Boolean {
        if (srcW <= 0 || srcH <= 0 || !(size.wCm > 0.0) || !(size.hCm > 0.0)) return false
        if (PrintMath.effectiveDpi(srcW, srcH, size.wCm, size.hCm) >= PrintQuality.GOOD.minDpi) return false
        return plan(srcW, srcH, size, enlarge = true)?.enlarged == true
    }
}

/**
 * The long edge to decode the photo at so that its [PrintPlan.crop] still holds [PrintPlan.outW] x
 * [PrintPlan.outH] pixels, and no more: a big photo for a small print is decoded smaller (the
 * decoder scales while it reads, which is cheap), while a photo that is short of pixels is decoded
 * in full. Held to [maxEdge].
 */
internal fun decodeEdgeFor(srcW: Int, srcH: Int, plan: PrintPlan, maxEdge: Int): Int {
    val share = min(1.0, max(plan.outW.toDouble() / plan.crop.width, plan.outH.toDouble() / plan.crop.height))
    return ceil(max(srcW, srcH) * share).toInt().coerceIn(1, maxEdge)
}

/**
 * The sizes to go through, largest first, to bring a [fromW] x [fromH] region down to [toW] x [toH]
 * without ever shrinking by more than half in one draw (a single bilinear draw of a big reduction
 * skips pixels and shows as jaggies). Does not include the final size. Each size is at least the
 * target on both sides.
 */
internal fun resampleSteps(fromW: Int, fromH: Int, toW: Int, toH: Int): List<Pair<Int, Int>> {
    val steps = mutableListOf<Pair<Int, Int>>()
    var w = fromW
    var h = fromH
    while (w / 2 >= toW && h / 2 >= toH && w / 2 > 0 && h / 2 > 0) {
        w /= 2
        h /= 2
        steps += w to h
    }
    return steps
}

/** The three Exif values that say a file's print density: dots per inch, the same both ways. */
internal class ResolutionTags private constructor(val xResolution: String, val yResolution: String, val unit: String) {
    companion object {
        /** `ResolutionUnit` 2 is inches. */
        fun of(dpi: Int) = ResolutionTags("$dpi/1", "$dpi/1", "2")
    }
}

private val PRINT_SUFFIX = Regex("-print-[0-9.]+x[0-9.]+cm( \\(\\d+\\))?$")

/** The start of every name [printFileName] gives a [displayName] at [size]: what a library query for taken names matches. */
internal fun printNameBase(displayName: String, size: PrintSize): String {
    val trimmed = displayName.trim().ifBlank { "photo" }
    val dot = trimmed.lastIndexOf('.')
    // A leading dot is a hidden file, not an extension.
    val stem = (if (dot > 0) trimmed.substring(0, dot) else trimmed).replace(PRINT_SUFFIX, "").ifBlank { "photo" }
    return "$stem-print-${FramePresets.number(size.wCm.toFloat())}x${FramePresets.number(size.hCm.toFloat())}cm"
}

/**
 * The name a print file is saved under: the photo's stem, `-print-`, the size, `.jpg`; if that is
 * in [taken] (compared without regard to case) the first of " (2)", " (3)" ... that is not. A print
 * of a print takes the marker out first, so it does not stack. Never the photo's own name unless
 * that name is free, and the file is always a new one besides: nothing here ever writes to the photo.
 */
internal fun printFileName(displayName: String, size: PrintSize, taken: Set<String>): String {
    val base = printNameBase(displayName, size)
    val lowerTaken = taken.mapTo(HashSet()) { it.lowercase() }
    var name = "$base.jpg"
    var n = 2
    while (name.lowercase() in lowerTaken) name = "$base (${n++}).jpg"
    return name
}

// ---------------------------------------------------------------------------
// The Android part
// ---------------------------------------------------------------------------

/** The photo may not be exported now (it was locked or removed since the screen listed it). */
internal class PrintRefusedException : IllegalStateException(WallCopy.CANT_EXPORT)

/** The export could not be made; the message is the sentence to show. */
internal class PrintFailedException(message: String) : IllegalStateException(message)

/**
 * What an export may read: [asset] and the two calls that guard its pixels. [mayExport] is
 * asked again at the moment anything is written out, and [decode] is the only way pixels are read
 * (both are `ExperimentEnv`'s: the live browsable set, then `decodeUpright`).
 */
internal class PrintSource(
    val asset: MediaAsset,
    val mayExport: () -> Boolean,
    val decode: suspend (DecodeLimits) -> DecodedBitmap?,
)

/** A saved print file. [enlarged]: its pixels were made up to size by enlarging, with no new detail. */
internal class PrintSaved(
    val uri: Uri,
    val displayName: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int,
    val enlarged: Boolean = false,
)

/** Where a finished file goes, and what names are already there. Separate so the pipeline can be run without a MediaStore. */
internal interface PrintPublisher {
    /** Names in the library that start with [prefix]. */
    fun existingNames(prefix: String): Set<String>

    /** Puts the finished [file] in the library as a new image called [displayName]. */
    fun publish(file: File, displayName: String, dateTakenMillis: Long): Uri
}

/**
 * Writes into the device's Pictures, in this app's own `Fotoz/Prints` album (Android 10 and later;
 * below that `RELATIVE_PATH` does not exist and the row lands in the default Pictures folder), the way
 * [com.fotoxplorr.app.lift.StickerExporter.saveToGallery] writes a sticker: a new MediaStore row
 * held `IS_PENDING` until the bytes are in, deleted again if the copy fails. Below Android 10 the
 * `WRITE_EXTERNAL_STORAGE` the manifest already declares (up to API 28) is what allows the insert.
 */
internal class MediaStorePrintPublisher(context: Context) : PrintPublisher {
    private val app = context.applicationContext

    override fun existingNames(prefix: String): Set<String> = runCatching {
        app.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            buildSet { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
        }.orEmpty()
    }.getOrDefault(emptySet())

    override fun publish(file: File, displayName: String, dateTakenMillis: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, OutputFormat.JPEG.mimeType)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (dateTakenMillis > 0) put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, PrintExporter.GALLERY_RELATIVE_PATH)
                // Keeps a half-written file out of every other gallery until the bytes are there.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw PrintFailedException(WallCopy.CANT_CREATE_FILE)
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw PrintFailedException(WallCopy.CANT_CREATE_FILE)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }
}

/**
 * Makes a print-ready file from a photo: the frame's part of it (cover crop), at the size and
 * density a lab wants, as a JPEG in sRGB with the density written where programs look for it.
 *
 * Its own small function, deliberately apart from [com.fotoxplorr.app.editor.EditedCopyWriter]:
 * that class's `save` is the editor's and stays as it is. What is shared is the building blocks
 * (the decode limits, the out-of-memory ladder, the JPEG quality, the location proof).
 *
 * ## Steps
 *  1. Ask [PrintSource.mayExport]. Decode a tiny copy to learn the photo's real upright size.
 *  2. Plan ([PrintPlanner]) and decode no larger than needed through [PrintSource.decode]
 *     (EXIF orientation baked in, within the editor's 8192 px / 64 MP limits).
 *  3. Crop, resample (in halves for a big reduction, bilinear) and convert to sRGB by drawing onto
 *     an opaque white sRGB bitmap, so a Display-P3 photo is not written with colours a lab will
 *     read as sRGB, and transparency never turns black. When the person asked to enlarge and the
 *     photo has fewer pixels than the size needs, [enlargeBitmap] does this step instead: Lanczos
 *     band by band into an sRGB bitmap, a little unsharp mask after. It adds pixels, not detail.
 *  4. Encode to a staging JPEG under the cache. Copy the photo's Exif/XMP onto it WITHOUT location
 *     ([ExifCopier], `keepLocation = false`), then overwrite the resolution tags and the pixel
 *     size, and set the JFIF density ([JfifDensity]). [ExifCopier] copies a source's own
 *     `XResolution`, `YResolution` and `ResolutionUnit` (they are not in its excluded set), so the
 *     overwrite happens after the copy, and the result is read back and checked.
 *  5. Prove no location is in the file, ask [PrintSource.mayExport] once more, and only then
 *     publish a NEW file ([PrintPublisher]). The photo is never opened for writing.
 *
 * Out of memory steps the file down [exportEdgeLadder] (the density tag follows the real pixels,
 * and an enlargement steps down to fewer made-up pixels); if every rung fails the person gets a
 * sentence, not a crash. Cancellation is honoured between the steps and between an enlargement's
 * bands; a single decode, draw, band or encode call is not interrupted.
 *
 * Nothing here has run on a device: the colour conversion, the JPEG's marker order after Exif is
 * written, and memory on a 48 MP photo are device checks.
 */
internal class PrintExporter(
    context: Context,
    private val publisher: PrintPublisher = MediaStorePrintPublisher(context),
) {
    private val app = context.applicationContext
    private val stagingDir get() = File(app.cacheDir, STAGING_DIRECTORY)

    /**
     * Saves a print file for [source] at [size]. Cancellation is rethrown; every other failure is in the [Result].
     * With [enlarge], a photo short of pixels is enlarged to the size ([lanczosResample]) instead of
     * being saved at its own pixels; a photo that has enough is saved as it always was.
     */
    suspend fun save(source: PrintSource, size: PrintSize, enlarge: Boolean = false, onProgress: (Float?) -> Unit = {}): Result<PrintSaved> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(saveNow(source, size, enlarge, onProgress))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /**
     * A bitmap of [size] for the system print path, up to [dpi] dots per inch, in sRGB. [use] gets
     * it and the plan it was made to, and the bitmap is recycled when [use] returns: nothing may
     * keep it. Refused if the photo may not be exported; asked again before [use] runs.
     */
    suspend fun <T : Any> render(source: PrintSource, size: PrintSize, dpi: Int, use: suspend (Bitmap, PrintPlan) -> T): T =
        withContext(Dispatchers.IO) {
            renderedPrint(source, size, dpi, enlarge = false, {}) { bitmap, plan ->
                if (!source.mayExport()) throw PrintRefusedException()
                use(bitmap, plan)
            }.second
        }

    /** Deletes staging files older than a day (a job that was killed before it cleaned up). */
    suspend fun pruneCache(maxAgeMillis: Long = CACHE_MAX_AGE_MILLIS): Int = withContext(Dispatchers.IO) {
        pruneStaleFiles(stagingDir, System.currentTimeMillis(), maxAgeMillis)
    }

    private suspend fun saveNow(source: PrintSource, size: PrintSize, enlarge: Boolean, onProgress: (Float?) -> Unit): PrintSaved {
        pruneCache()
        val staging = File(stagingDir.apply { mkdirs() }, "print-${UUID.randomUUID()}.jpg")
        try {
            val (plan, _) = renderedPrint(source, size, PrintPlanner.TARGET_DPI, enlarge, onProgress) { bitmap, _ ->
                FileOutputStream(staging).buffered().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, OutputFormat.JPEG.exportQuality(), out)) { WallCopy.CANT_CREATE_FILE }
                }
            }
            coroutineContext.ensureActive()
            onProgress(0.8f)
            writeMetadata(source.asset, staging, plan)
            stampDensity(staging, plan)
            verify(staging, plan)
            onProgress(0.9f)
            val name = printFileName(source.asset.displayName, size, publisher.existingNames(printNameBase(source.asset.displayName, size)))
            // The last look before a copy of the pixels goes into the library.
            if (!source.mayExport()) throw PrintRefusedException()
            val uri = publisher.publish(staging, name, source.asset.dateTakenMillis)
            onProgress(1f)
            return PrintSaved(uri, name, plan.outW, plan.outH, plan.dpi, plan.enlarged)
        } finally {
            staging.delete()
        }
    }

    /**
     * Decode, plan, resample (or, for [enlarge] on a photo short of pixels, enlarge), hand the
     * finished bitmap to [consume]; down the memory ladder when a rung runs out. Returns the plan
     * that worked (its [PrintPlan.enlarged] says what was really done) with [consume]'s result.
     */
    private suspend fun <T : Any> renderedPrint(
        source: PrintSource,
        size: PrintSize,
        wantDpi: Int,
        enlarge: Boolean,
        onProgress: (Float?) -> Unit,
        consume: suspend (Bitmap, PrintPlan) -> T,
    ): Pair<PrintPlan, T> {
        if (!source.mayExport()) throw PrintRefusedException()
        val probe = source.decode(PROBE_LIMITS) ?: throw PrintFailedException(WallCopy.CANT_READ_PHOTO)
        val srcW = probe.sourceWidth
        val srcH = probe.sourceHeight
        probe.bitmap.recycle()
        onProgress(0.05f)
        val first = PrintPlanner.plan(srcW, srcH, size, wantDpi, enlarge = enlarge) ?: throw PrintFailedException(WallCopy.CANT_READ_PHOTO)
        val outcome = renderDownTheLadder(exportEdgeLadder(max(first.outW, first.outH))) { edge ->
            val planned = PrintPlanner.plan(srcW, srcH, size, wantDpi, maxEdge = edge, enlarge = enlarge) ?: return@renderDownTheLadder null
            val running = coroutineContext
            running.ensureActive()
            val decoded = source.decode(DecodeLimits(decodeEdgeFor(srcW, srcH, planned, MAX_EXPORT_EDGE), MAX_EXPORT_PIXELS))
                ?: throw PrintFailedException(WallCopy.CANT_READ_PHOTO)
            onProgress(0.25f)
            // Judged on the pixels actually decoded (a photo over the decode limits is held to them),
            // not on the plan's guess: only a result bigger than what it comes from is an enlargement.
            val from = coverRect(decoded.bitmap.width, decoded.bitmap.height, size.aspect)
            val enlarging = enlarge && (planned.outW > from.width || planned.outH > from.height)
            val plan = planned.copy(enlarged = enlarging)
            val rendered = try {
                if (enlarging) {
                    enlargeBitmap(decoded.bitmap, size.aspect, plan.outW, plan.outH, { running.ensureActive() }, { onProgress(0.25f + 0.25f * it) })
                } else {
                    resample(decoded.bitmap, size.aspect, plan.outW, plan.outH)
                }
            } finally {
                decoded.bitmap.recycle()
            }
            try {
                running.ensureActive()
                onProgress(0.5f)
                plan to consume(rendered, plan)
            } finally {
                rendered.recycle()
            }
        } ?: throw PrintFailedException(WallCopy.NOT_ENOUGH_MEMORY)
        return outcome.value
    }

    /**
     * The photo's Exif and XMP onto [file], without location, then the print density over whatever
     * the copy brought: the source's own resolution (often 72) must not win. A photo whose
     * metadata cannot be read still gets a good file with the density alone. A location that is
     * still found afterwards fails the export rather than letting it out.
     */
    private fun writeMetadata(asset: MediaAsset, file: File, plan: PrintPlan) {
        val target = ExifInterface(file.absolutePath)
        runCatching {
            app.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
                ExifCopier.copy(ExifInterface(stream), target, keepLocation = false)
            }
        }
        val tags = ResolutionTags.of(plan.dpi)
        target.setAttribute(ExifInterface.TAG_X_RESOLUTION, tags.xResolution)
        target.setAttribute(ExifInterface.TAG_Y_RESOLUTION, tags.yResolution)
        target.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, tags.unit)
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, plan.outW.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, plan.outH.toString())
        // The pixels are sRGB now, whatever the source said (Adobe RGB, or "uncalibrated" for a P3 photo).
        if (target.getAttribute(ExifInterface.TAG_COLOR_SPACE) != null) target.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        if (target.getAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX) != null) {
            target.setAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX, "R98")
        }
        target.saveAttributes()
        check(!carriesLocation(file)) { WallCopy.LOCATION_NOT_REMOVED }
    }

    /** Last, because writing Exif rewrites the file's header: the JFIF density, then a read-back of it. */
    private fun stampDensity(file: File, plan: PrintPlan) {
        val patched = JfifDensity.withDpi(file.readBytes(), plan.dpi)
        check(JfifDensity.readDpi(patched) == plan.dpi to plan.dpi) { WallCopy.FILE_NOT_AS_PLANNED }
        file.writeBytes(patched)
    }

    /** The staged file is a JPEG of the planned size whose Exif says the planned density. */
    private fun verify(file: File, plan: PrintPlan) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth == plan.outW && bounds.outHeight == plan.outH) { WallCopy.FILE_NOT_AS_PLANNED }
        val exif = ExifInterface(file.absolutePath)
        check(exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0).roundToInt() == plan.dpi) { WallCopy.FILE_NOT_AS_PLANNED }
        check(exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0) == 2) { WallCopy.FILE_NOT_AS_PLANNED }
        check(JfifDensity.readDpi(file.readBytes()) == plan.dpi to plan.dpi) { WallCopy.FILE_NOT_AS_PLANNED }
    }

    internal companion object {
        /** Prints land in their own album under the app's Pictures folder, so a pile of them does not interleave with the camera roll. */
        const val GALLERY_RELATIVE_PATH = "Pictures/Fotoz/Prints/"

        /** Must not be one of the directories `res/xml/file_paths.xml` exposes: these files are never shared by URI. */
        const val STAGING_DIRECTORY = "print-staging"

        const val CACHE_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** A thumbnail-sized decode, only to learn the photo's real upright size. */
        val PROBE_LIMITS = DecodeLimits(maxLongEdge = 64, maxPixels = 4096)
    }
}

/**
 * [src] cropped to the frame's shape ([coverRect]) and drawn at [outW] x [outH] onto an opaque
 * white sRGB bitmap. The draw is what converts a wide-gamut source to sRGB (the canvas maps colours
 * between the two spaces) and flattens transparency onto white. A reduction of more than half goes
 * through [resampleSteps] first. [src] is not recycled; the intermediate bitmaps are.
 */
internal fun resample(src: Bitmap, aspect: Double, outW: Int, outH: Int): Bitmap {
    val from = coverRect(src.width, src.height, aspect)
    var current = src
    var region = Rect(from.left, from.top, from.left + from.width, from.top + from.height)
    for ((w, h) in resampleSteps(from.width, from.height, outW, outH)) {
        val next = drawOnWhite(current, region, w, h)
        if (current !== src) current.recycle()
        current = next
        region = Rect(0, 0, w, h)
    }
    try {
        return drawOnWhite(current, region, outW, outH)
    } finally {
        if (current !== src) current.recycle()
    }
}

private fun drawOnWhite(src: Bitmap, region: Rect, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.SRGB))
    Canvas(out).apply {
        drawColor(Color.WHITE)
        drawBitmap(src, region, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
    }
    return out
}
