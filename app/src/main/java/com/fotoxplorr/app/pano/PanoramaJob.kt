package com.fotoxplorr.app.pano

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The Android seam of the panorama maker: the one function that takes a person's chosen photos and
 * runs the pure core ([PairAligner], [PanoLayout], [ExposureGain], [PanoCompositor]) over them, and
 * the narrow interfaces it needs from the phone ([PanoramaHost], [PanoramaSink]). Everything it
 * says to the person is a [PanoCopy] sentence; it never throws a failure, it returns one.
 *
 * ## The steps
 * 1. **Refuse** fewer than two or more than twelve photos, and any photo [PanoramaHost.mayExport]
 *    says no to (protected, sensitive, locked since the picker opened), before a pixel is read.
 * 2. **Read small.** Every photo's 35 mm focal length is read from EXIF first; then each photo is
 *    decoded once at about [WORKING_EDGE] px through the host (which is
 *    [com.fotoxplorr.app.media.decodeUpright], TRAPS #34: upright pixels, never upscaled) and warped
 *    onto the cylinder as a grey picture ([warpToGrey]) for the aligner. A photo with no usable
 *    focal length borrows the median of the others' ([sharedFocal35]); only when none has one is a
 *    field of view guessed. Either way the result carries [PanoCopy.LENS_GUESSED]. A photo that is
 *    not a 4:3 or 3:2 frame ([looksCropped]) gets [PanoCopy.CROPPED] instead, because the tag
 *    describes the lens's whole frame.
 * 3. **Align and lay out.** Neighbouring pairs go through [PairAligner]; any pair that is not
 *    [Offset.confident] stops the job with a sentence that names the photo (the first photo after the
 *    break, or "no two matched"). Nothing is joined on a guess.
 * 4. **Plan.** The exposure multipliers ([ExposureGain]) and the canvas ladder
 *    ([PanoGeometry.ladder], the pure plan capped at [PanoramaRequest.maxPixels], the editor's own
 *    export ceilings and a floor) are made from the small pictures.
 * 5. **Compose** on a NEW canvas ([PanoramaSink.newCanvas]), starting at the first rung whose decoded
 *    sources fit the Java heap ([startRung]; usually the first, smaller on a small heap): every photo
 *    is decoded again, one ahead of the one being drawn, at exactly the size the canvas needs
 *    ([PanoCompositor.decodeSize]) and held as an `IntArray` on the Java heap (the canvas alone is
 *    native). An [OutOfMemoryError] anywhere in steps 5 and 6 takes the next, smaller rung on a fresh
 *    canvas and redoes the compose, as `renderDownTheLadder` does for the editor.
 * 6. **Publish** the auto-cropped canvas through [PanoramaSink.publish], which asks
 *    [PanoramaHost.mayExport] AGAIN right before it creates the file (TRAPS #33: a folder can be
 *    locked while a long job runs).
 *
 * ## What this does not know
 * Real-photo quality is unproven: the tests use procedural scenes with gain, bias, noise, vignetting,
 * roll, jitter and a moving blob, and measure how much each costs; nothing here has run on a device
 * or on a photograph. The cylinder model needs a stationary photographer turning the phone: parallax
 * (near objects, walking) shows as ghosting that no step here repairs. All photos must come from one
 * camera at one size ([sameCamera]); a mixed set is refused rather than stitched wrongly. A photo
 * that lost its tag is not a different lens, so it borrows the others' ([sharedFocal35]) and the
 * lens check does not count it. A mid-job photo the host stops serving is named, not blamed
 * ([PanoCopy.noLongerAvailable]): the host cannot tell a lock from a deletion.
 *
 * ## Cancel and process death
 * Cancelling the coroutine stops the job between pairs, bands and photos (the aligner and compositor
 * are asked to stop), leaves no MediaStore row (the sink deletes a pending row on any failure,
 * including this one) and returns by throwing [CancellationException] so [JobRunner] reports it as
 * cancelled. If the process is killed, nothing has been saved: the panorama exists only as a new
 * file once step 6 finishes, a half-written one is a pending row Android hides and discards (on
 * Android 10 and later; on 8 and 9 there is no pending state, so a kill during the copy can leave a
 * truncated JPEG, see [PanoramaWriter]), and a leftover staging file is deleted by the next run
 * ([PanoramaWriter.pruneStaging]). What the person had selected is the screen's to remember, not this job's.
 */
object PanoramaJob {

    /** The long edge the alignment copies are decoded at. */
    const val WORKING_EDGE = 1200

    /** Alongside [WORKING_EDGE], for an unusual aspect ratio (a square photo is 1.44 MP at this edge). */
    private const val WORKING_PIXELS = 1_500_000L

    /** Photo sizes may differ by this fraction (rounding, a crop of a pixel or two) and still be one camera. */
    private const val SIZE_TOLERANCE = 0.01

    /** A photo's long over short edge may be this fraction off 4:3 or 3:2 and still count as a whole frame. */
    private const val ASPECT_TOLERANCE = 0.02

    /** The compositor holds this many decoded sources at once: the one being drawn and the next one, decoding. */
    private const val LIVE_SOURCES = 2

    /** Bytes of Java heap per decoded pixel: the host hands pixels over as an IntArray. */
    private const val HEAP_BYTES_PER_PIXEL = 4

    /**
     * The share of the Java heap the live decoded sources may take; the rest is the app, the grey
     * copies, the compositor's band scratch and the garbage collector's room. A guess, not measured on a device.
     */
    private const val HEAP_SHARE = 0.4

    /** Focal length over the diagonal may differ by this fraction between photos and still be one lens. */
    private const val LENS_TOLERANCE = 0.05

    // Where each step ends on the 0..1 progress bar.
    private const val READ_END = 0.15f
    private const val ALIGN_END = 0.30f
    private const val PLAN_END = 0.32f
    private const val COMPOSE_END = 0.90f
    private const val PUBLISH_END = 0.99f

    /** The bar moves in steps of at least this much, so a worker thread's tiny updates do not flood the shade. */
    private const val PROGRESS_STEP = 0.004f

    /**
     * Run the whole job. Returns [PanoramaOutcome.Done] or [PanoramaOutcome.Failed]; throws only
     * [CancellationException]. [onProgress] gets 0..1, rising, from several threads (one at a time).
     */
    suspend fun run(
        request: PanoramaRequest,
        host: PanoramaHost,
        sink: PanoramaSink,
        onProgress: (Float) -> Unit = {},
    ): PanoramaOutcome = withContext(Dispatchers.Default) {
        try {
            Steps(request, host, sink, Progress(onProgress), currentCoroutineContext()[Job]).go()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PanoramaProtectedException) {
            PanoramaOutcome.Failed(PanoCopy.PROTECTED)
        } catch (e: PanoramaSaveException) {
            PanoramaOutcome.Failed(if (isOutOfSpace(e)) PanoCopy.NO_SPACE else PanoCopy.SAVE_FAILED)
        } catch (e: OutOfMemoryError) {
            PanoramaOutcome.Failed(PanoCopy.OUT_OF_MEMORY)
        } catch (e: Exception) {
            PanoramaOutcome.Failed(PanoCopy.FAILED)
        }
    }

    /**
     * Start [run] as a [JobRunner] job (progress, cancel, the activity shade). The outcome arrives on
     * [JobRunner.outcomes] as a [PanoramaOutcome.Done] result, or as a [PanoramaFailure] carrying the
     * sentence for the person, or as a cancellation.
     */
    fun launch(runner: JobRunner, request: PanoramaRequest, host: PanoramaHost, sink: PanoramaSink): JobHandle =
        runner.launch(PanoCopy.JOB_TITLE, ActivityKind.EXPORTING) { onProgress ->
            when (val outcome = run(request, host, sink) { onProgress(it) }) {
                is PanoramaOutcome.Done -> Result.success(outcome)
                is PanoramaOutcome.Failed -> Result.failure(PanoramaFailure(outcome.message))
            }
        }

    // ---- the pure decisions ------------------------------------------------------------------

    /**
     * True when [failure] or something it was caused by is the phone running out of space: the
     * sink's own [PanoramaNoSpaceException], or an I/O error whose text is the system's
     * `ENOSPC (No space left on device)`. Anything else, "disk full" in a message included, is a plain save failure.
     */
    internal fun isOutOfSpace(failure: Throwable?): Boolean {
        var t = failure
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is PanoramaNoSpaceException) return true
            val text = t.message.orEmpty()
            if (t is IOException && (text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true))) return true
            t = t.cause
        }
        return false
    }

    /**
     * The Java heap the decoded sources of [plan] hold at the compositor's peak: the larger of the
     * photos' decode sizes ([PanoCompositor.decodeSize]) in two live copies ([LIVE_SOURCES]), 4 bytes
     * a pixel. The canvas is not in it: it is a native [android.graphics.Bitmap].
     */
    internal fun sourceHeapBytes(plan: PanoGeometry.Plan, sources: List<PanoSource>): Long {
        val largest = sources.maxOf { PanoCompositor.decodeSize(it, plan).let { size -> size.width.toLong() * size.height } }
        return LIVE_SOURCES * HEAP_BYTES_PER_PIXEL * largest
    }

    /**
     * The rung of [ladder] to try first on a Java heap of [heapBytes] ([Runtime.maxMemory]): the first
     * whose [sourceHeapBytes] fit in [HEAP_SHARE] of it, or the last when none do. An
     * [OutOfMemoryError] still takes the next rung, but only after the whole compose has been redone,
     * which is minutes at 24 MP; this spares that on a phone whose heap is small next to its photos.
     */
    internal fun startRung(ladder: List<PanoGeometry.Plan>, sources: List<PanoSource>, heapBytes: Long): Int {
        val room = heapBytes * HEAP_SHARE
        val first = ladder.indexOfFirst { sourceHeapBytes(it, sources) <= room }
        return if (first >= 0) first else ladder.lastIndex
    }

    /**
     * True when the lens had to be guessed: [focal35mm] is missing, or [PanoGeometry.focalPixels]
     * did not believe it (it falls back to a field of view, which is exactly what asking for no
     * focal length gives, so the two answers are equal only then).
     */
    internal fun lensWasGuessed(focal35mm: Int?, width: Int, height: Int): Boolean =
        focal35mm == null ||
            PanoGeometry.focalPixels(focal35mm, width, height) == PanoGeometry.focalPixels(null, width, height)

    /**
     * The 35 mm tag a photo without a believable one borrows: the MEDIAN of the believable tags
     * ([lensWasGuessed] false) of the sweep, the lower middle one for an even count, or null when no
     * photo has one. A photo re-saved by an editor or a messenger loses its EXIF while the lens did
     * not change, and the 78 degree guess is 11% off a 24 mm phone, which [sameCamera] would read as
     * another lens and refuse the sweep for.
     */
    internal fun sharedFocal35(tags: List<Int?>): Int? {
        val believed = tags.filterNotNull().filter { !lensWasGuessed(it, 4, 3) }.sorted()
        return believed.getOrNull((believed.size - 1) / 2)
    }

    /**
     * The focal length in pixels of a [width] x [height] photo whose own tag is [own]: from the tag
     * when it is believable, otherwise from [shared] (see [sharedFocal35]), otherwise the field-of-view
     * guess [PanoGeometry.focalPixels] makes for no tag.
     */
    internal fun focalFor(own: Int?, shared: Int?, width: Int, height: Int): Double =
        PanoGeometry.focalPixels(if (lensWasGuessed(own, width, height)) shared else own, width, height)

    /**
     * True when a photo of this size is not the 4:3 or 3:2 frame a phone or camera lens covers (16:9
     * video-like modes, a square, a photo cropped in an editor with its EXIF kept). The 35 mm tag is
     * relative to the lens's whole frame, [PanoGeometry.focalPixels] applies it to the diagonal of the
     * photo as stored, and for a crop that diagonal is smaller: the pixel focal length comes out too
     * short (about 8% for 16:9), lines bend a little and the overlaps do not fit exactly. Unmeasured
     * on a real photo; the person is told.
     */
    internal fun looksCropped(width: Int, height: Int): Boolean {
        val ratio = max(width, height).toDouble() / min(width, height)
        return abs(ratio / (4.0 / 3.0) - 1.0) > ASPECT_TOLERANCE && abs(ratio / 1.5 - 1.0) > ASPECT_TOLERANCE
    }

    /**
     * Whether photos of [sizes] with focal lengths [focals] (all in their own pixels) can share one
     * cylinder: the same size within [SIZE_TOLERANCE], and the same lens, judged by focal length over
     * the diagonal (what a field of view is) within [LENS_TOLERANCE]. A different lens or a portrait
     * among landscapes puts the photos on cylinders of different radius, which a shift cannot join.
     */
    internal fun sameCamera(sizes: List<PanoSize>, focals: List<Double>): Boolean {
        val first = sizes.first()
        val firstView = focals.first() / hypot(first.width.toDouble(), first.height.toDouble())
        for (i in sizes.indices) {
            val s = sizes[i]
            if (abs(s.width - first.width) > SIZE_TOLERANCE * first.width) return false
            if (abs(s.height - first.height) > SIZE_TOLERANCE * first.height) return false
            val view = focals[i] / hypot(s.width.toDouble(), s.height.toDouble())
            if (abs(view / firstView - 1.0) > LENS_TOLERANCE) return false
        }
        return true
    }

    /**
     * The photo [pixels] warped onto the cylinder of radius [focal] (in the pixels of [pixels]), as a
     * grey picture of [PanoGeometry.warpedSize] width, for the aligner and the exposure solver.
     * The geometry is [PanoCompositor]'s: pixel centres at half-integers, the warped image's centre at
     * half its width, column `u` samples plane column `f*tan(u/f)` and row `v/cos(u/f)`, bilinear.
     * Samples outside the photo (the cylinder's corners) repeat its edge pixel: the aligner needs a
     * rectangle, and the compositor treats those pixels as outside.
     */
    internal fun warpToGrey(pixels: PanoPixels, focal: Double): GrayImage {
        val w = pixels.width
        val h = pixels.height
        val argb = pixels.argb
        val warped = PanoGeometry.warpedSize(w, h, focal).width
        val luma = FloatArray(w * h)
        for (i in luma.indices) {
            val p = argb[i]
            luma[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) / 256f
        }
        val x0 = IntArray(warped)
        val wx = FloatArray(warped)
        val stretch = DoubleArray(warped)
        for (i in 0 until warped) {
            val theta = ((i + 0.5) - warped / 2.0) / focal
            val a = theta.coerceIn(-MAX_ANGLE, MAX_ANGLE)
            val x = (focal * tan(a) + w / 2.0 - 0.5).coerceIn(0.0, (w - 1).toDouble())
            val left = min(floor(x).toInt(), max(0, w - 2))
            x0[i] = left
            wx[i] = (x - left).toFloat().coerceIn(0f, 1f)
            stretch[i] = 1.0 / cos(a)
        }
        val out = ByteArray(warped * h)
        for (row in 0 until h) {
            val v = (row + 0.5) - h / 2.0
            for (i in 0 until warped) {
                val y = (v * stretch[i] + h / 2.0 - 0.5).coerceIn(0.0, (h - 1).toDouble())
                val top = min(floor(y).toInt(), max(0, h - 2))
                val wy = (y - top).toFloat().coerceIn(0f, 1f)
                val a = top * w + x0[i]
                val b = if (h > 1) a + w else a
                val right = if (w > 1) 1 else 0
                val upper = luma[a] + (luma[a + right] - luma[a]) * wx[i]
                val lower = luma[b] + (luma[b + right] - luma[b]) * wx[i]
                out[row * warped + i] = (upper + (lower - upper) * wy + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(warped, h, out)
    }

    private const val MAX_ANGLE = Math.PI / 2 - 1e-3

    // ---- the steps ---------------------------------------------------------------------------

    /** What step 2 learns about one photo. */
    private class Shot(
        val asset: MediaAsset,
        val width: Int,
        val height: Int,
        val focal: Double,
        val guessed: Boolean,
        /** Working pixels over full-resolution pixels. */
        val scale: Double,
        val grey: GrayImage,
    )

    /** One photo as the compositor reads it: the host decodes it at the size asked for. */
    private class HostSource(
        val asset: MediaAsset,
        val host: PanoramaHost,
        override val width: Int,
        override val height: Int,
        override val focal: Double,
    ) : PanoSource {
        // Blocking by contract (the compositor calls this on an IO thread, one photo ahead). The
        // decode itself is suspending and runs on the IO dispatcher, so this thread only waits.
        override fun decode(targetWidth: Int, targetHeight: Int): PanoPixels? = runBlocking {
            val limits = DecodeLimits(maxLongEdge = max(targetWidth, targetHeight), maxPixels = targetWidth.toLong() * targetHeight)
            host.decode(asset, limits)?.pixels
        }
    }

    /** The bar, kept rising and quiet. */
    private class Progress(private val sink: (Float) -> Unit) {
        private var shown = 0f

        @Synchronized
        fun report(value: Float) {
            val v = value.coerceIn(0f, 1f)
            if (v >= shown + PROGRESS_STEP || (v >= 1f && shown < 1f)) {
                shown = v
                sink(v)
            }
        }

        fun between(from: Float, to: Float, fraction: Float) = report(from + (to - from) * fraction.coerceIn(0f, 1f))
    }

    private class Steps(
        val request: PanoramaRequest,
        val host: PanoramaHost,
        val sink: PanoramaSink,
        val progress: Progress,
        val job: Job?,
    ) {
        val assets get() = request.assets
        val shouldStop: () -> Boolean = { job?.isActive == false }

        suspend fun go(): PanoramaOutcome {
            if (assets.size !in PanoGeometry.MIN_PHOTOS..PanoGeometry.MAX_PHOTOS) return PanoramaOutcome.Failed(PanoCopy.NEED_PHOTOS)
            // 1. refuse before reading anything
            if (!assets.all(host::mayExport)) return PanoramaOutcome.Failed(PanoCopy.PROTECTED)
            progress.report(0f)

            // 2. read small. The tags are read first: a photo without one borrows the lens of the
            // others, and the warp of its grey copy needs that.
            val tags = ArrayList<Int?>(assets.size)
            for (asset in assets) {
                job?.ensureActive()
                tags += host.focal35mm(asset)
            }
            val shared = sharedFocal35(tags)
            val shots = ArrayList<Shot>(assets.size)
            for ((i, asset) in assets.withIndex()) {
                job?.ensureActive()
                val decoded = host.decode(asset, DecodeLimits(WORKING_EDGE, WORKING_PIXELS))
                    ?: return PanoramaOutcome.Failed(unreadable(i))
                val focal35 = tags[i]
                val width = decoded.sourceWidth
                val height = decoded.sourceHeight
                val focal = focalFor(focal35, shared, width, height)
                val scale = decoded.pixels.width.toDouble() / width
                shots += Shot(asset, width, height, focal, lensWasGuessed(focal35, width, height), scale, warpToGrey(decoded.pixels, focal * scale))
                progress.between(0f, READ_END, (i + 1f) / assets.size)
            }
            if (!sameCamera(shots.map { PanoSize(it.width, it.height) }, shots.map { it.focal })) {
                return PanoramaOutcome.Failed(PanoCopy.MIXED)
            }

            // 3. align and lay out
            val offsets = ArrayList<Offset>(shots.size - 1)
            for (k in 0 until shots.size - 1) {
                job?.ensureActive()
                offsets += PairAligner.align(shots[k].grey, shots[k + 1].grey, shouldStop = shouldStop)
                progress.between(READ_END, ALIGN_END, (k + 1f) / (shots.size - 1))
            }
            val layout = PanoLayout.layout(shots.map { PanoSize(it.grey.width, it.grey.height) }, offsets)
            if (!layout.complete) {
                val none = layout.breaks.size == offsets.size
                return PanoramaOutcome.Failed(if (none) PanoCopy.NO_PAIR else PanoCopy.cannotJoin((layout.cannotJoin ?: 1) + 1))
            }

            // 4. plan
            val gains = ExposureGain.compute(shots.map { it.grey }, layout.positions, shouldStop).gains
            val sources = shots.mapIndexed { i, s -> HostSource(assets[i], host, s.width, s.height, s.focal) }
            val positions = layout.scaledPositions(1.0 / shots.first().scale)
            val ladder = PanoGeometry.ladder(PanoCompositor.nativeSizes(sources), positions, request.maxPixels)
            progress.report(PLAN_END)

            // 5 and 6. compose and publish, one rung at a time
            val notes = ArrayList<String>()
            if (shots.any { it.guessed }) notes += PanoCopy.LENS_GUESSED
            else if (shots.any { looksCropped(it.width, it.height) }) notes += PanoCopy.CROPPED
            if (layout.drifting) notes += PanoCopy.DRIFTING
            for ((rung, plan) in ladder.withIndex().drop(startRung(ladder, sources, request.heapBytes))) {
                var canvas: PanoramaCanvas? = null
                try {
                    canvas = sink.newCanvas(plan.canvasWidth, plan.canvasHeight)
                    val composed = try {
                        PanoCompositor.compose(
                            sources, positions, plan, canvas, gains,
                            shouldStop = shouldStop,
                            onProgress = { progress.between(PLAN_END, COMPOSE_END, it) },
                        )
                    } catch (e: PanoSourceUnavailableException) {
                        return PanoramaOutcome.Failed(unreadable(e.index))
                    }
                    val crop = composed.crop ?: return PanoramaOutcome.Failed(PanoCopy.NO_PICTURE)
                    job?.ensureActive()
                    // The last look before anything is written (TRAPS #33); the sink looks once more.
                    val allowed = { assets.all(host::mayExport) }
                    if (!allowed()) return PanoramaOutcome.Failed(PanoCopy.PROTECTED)
                    val uri = try {
                        sink.publish(canvas, crop, assets.first().dateTakenMillis, assets.first().displayName, allowed) {
                            progress.between(COMPOSE_END, PUBLISH_END, it)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PanoramaProtectedException) {
                        throw e
                    } catch (e: Exception) {
                        throw PanoramaSaveException(e)
                    }
                    progress.report(1f)
                    if (rung > 0) notes += PanoCopy.SAVED_SMALLER
                    return PanoramaOutcome.Done(uri, crop.width, crop.height, rung > 0, notes)
                } catch (e: OutOfMemoryError) {
                    // Next rung, on a new canvas; the partly drawn one is released below.
                } finally {
                    canvas?.release()
                }
            }
            return PanoramaOutcome.Failed(PanoCopy.OUT_OF_MEMORY)
        }

        /**
         * A photo that would not decode after the start check passed. If the host no longer lets it be
         * exported it was locked, trashed or deleted since the picker opened, and the host cannot say
         * which, so the sentence names the photo and claims no reason; otherwise it is simply unreadable.
         */
        private fun unreadable(index: Int): String =
            if (host.mayExport(assets[index])) PanoCopy.cannotRead(index + 1) else PanoCopy.noLongerAvailable(index + 1)
    }
}

/** What a panorama is made from: the photos in sweep order (left to right or right to left), and the size cap. */
class PanoramaRequest(
    val assets: List<MediaAsset>,
    /** The canvas budget in pixels; [PanoGeometry.planPlaced] clamps it to the editor's export ceilings. */
    val maxPixels: Long = PanoGeometry.DEFAULT_MAX_PIXELS,
    /** The Java heap the decoded photos may count on ([PanoramaJob.startRung]); the tests say how small the phone's is. */
    val heapBytes: Long = Runtime.getRuntime().maxMemory(),
)

/** How a run ended, unless it was cancelled. */
sealed interface PanoramaOutcome {
    /**
     * A panorama was saved at [uri] (a content URI string), [width] x [height] pixels. [notes] are
     * sentences the screen should show with it (a guessed lens, a smaller size than planned).
     */
    class Done(
        val uri: String,
        val width: Int,
        val height: Int,
        val steppedDown: Boolean,
        val notes: List<String>,
    ) : PanoramaOutcome {
        val message: String get() = (listOf(PanoCopy.SAVED) + notes).joinToString(" ")
    }

    /** Nothing was saved; [message] is one [PanoCopy] sentence for the person. */
    class Failed(val message: String) : PanoramaOutcome
}

/** A [PanoramaOutcome.Failed] as the failure of a [JobRunner] job. */
class PanoramaFailure(message: String) : Exception(message)

/** The sink found, right before creating the file, that a photo may no longer be exported. */
class PanoramaProtectedException : IOException("a photo became protected before the panorama was saved")

/** The sink could not save the file because the phone is full (or says so: ENOSPC). */
class PanoramaNoSpaceException(cause: Throwable? = null) : IOException("the phone has no space left for the panorama", cause)

/** The sink could not save the file. */
internal class PanoramaSaveException(cause: Throwable) : Exception(cause)

/** A photo decoded for the job: its pixels, and the upright size of the original they were made from. */
class PanoDecoded(val pixels: PanoPixels, val sourceWidth: Int, val sourceHeight: Int)

/**
 * What the job needs from the phone's photo library. The real one is [ExperimentPanoramaHost], which
 * is the Experimental host's own [ExperimentEnv.mayExport] and [ExperimentEnv.decode]; the tests
 * supply procedural photos.
 */
interface PanoramaHost {
    /** Whether the photo's pixels may be read and exported right now. Asked often; must be cheap. */
    fun mayExport(asset: MediaAsset): Boolean

    /** The photo upright, no larger than [limits], never enlarged; null if it may not be read or cannot be. */
    suspend fun decode(asset: MediaAsset, limits: DecodeLimits): PanoDecoded?

    /** The EXIF `FocalLengthIn35mmFilm`, or null when absent or the photo may not be read. */
    suspend fun focal35mm(asset: MediaAsset): Int?
}

/** A [PanoCanvas] the job can give back when it is done with it (a Bitmap is recycled). */
interface PanoramaCanvas : PanoCanvas {
    fun release()
}

/** Where the finished panorama goes: a canvas to draw on, and the one place a file is created. */
interface PanoramaSink {
    /** A NEW all-transparent canvas. May throw [OutOfMemoryError]; the job then takes a smaller size. */
    fun newCanvas(width: Int, height: Int): PanoramaCanvas

    /**
     * Save [crop] of [canvas] as a new image, dated [dateTakenMillis], named after [sourceName], and
     * return its content URI. [stillAllowed] must be asked once more right before the file is created;
     * when it says no, throw [PanoramaProtectedException] and create nothing. On any failure or
     * cancellation no row or file may remain. [onProgress] is 0..1 over the sink's own work.
     */
    suspend fun publish(
        canvas: PanoramaCanvas,
        crop: AutoCrop.CropRect,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String
}

/**
 * [PanoramaHost] over the Experimental host's [ExperimentEnv]: every read goes through
 * [ExperimentEnv.decode] (which is `decodeUpright` behind the [ExperimentEnv.mayExport] check) and
 * the focal length is read from EXIF only for a photo that may be exported.
 */
class ExperimentPanoramaHost(private val env: ExperimentEnv) : PanoramaHost {
    override fun mayExport(asset: MediaAsset): Boolean = env.mayExport(asset)

    override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): PanoDecoded? {
        // A file that vanished or cannot be opened throws from the decoder; to the job that is "cannot be read".
        val decoded = try {
            env.decode(asset, limits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bitmap = decoded.bitmap
            try {
                val argb = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PanoDecoded(PanoPixels(bitmap.width, bitmap.height, argb), decoded.sourceWidth, decoded.sourceHeight)
            } finally {
                recycle(bitmap)
            }
        }
    }

    override suspend fun focal35mm(asset: MediaAsset): Int? {
        if (!env.mayExport(asset)) return null
        return withContext(Dispatchers.IO) {
            // Read here, not through the viewer's detail reader: this needs one tag, not the whole card.
            runCatching {
                env.context.contentResolver.openInputStream(asset.contentUri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }
                }
            }.getOrNull()
        }
    }

    private fun recycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
