package com.fotoxplorr.app.pano

import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.targetSize
import com.fotoxplorr.core.model.MediaId
import java.util.Collections
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Procedural stand-ins for a phone and its photo library, for the panorama job tests: a CYLINDER
 * scene photographed through a pinhole camera at known yaw steps (the geometry the compositor tests
 * proved), with the imperfections of a handheld sweep ([SyntheticPanoScene.Shot]: gain, bias, noise,
 * vignetting, roll, a moving blob, a near layer for parallax) and vertical jitter. The truth is known
 * by construction. There are no real photographs in the repository; passing on these is necessary,
 * not sufficient.
 */
internal class CylinderRig(
    val photoW: Int = 640,
    val photoH: Int = 480,
    val count: Int = 5,
    val step: Int = 400,
    val tops: List<Int> = listOf(6, 0, 10, 2, 8),
    /** The lens the host reports (35 mm equivalent), or null for a photo that does not say. */
    val f35: Int? = 28,
    /** The focal length the CAMERA really had, in photo pixels; defaults to what [f35] says. */
    cameraFocal: Double? = null,
    val seed: Int = 5,
) {
    val f: Double = cameraFocal ?: PanoGeometry.focalPixels(f35, photoW, photoH)
    val warpedW: Int = PanoGeometry.warpedSize(photoW, photoH, f).width
    val lefts: List<Int> = List(count) { it * step }
    val topsUsed: List<Int> = List(count) { tops[it % tops.size] }
    val fullW: Int = lefts.last() + warpedW
    val fullH: Int = photoH + topsUsed.max()
    val scene: ColourScene by lazy { ColourScene.of(fullW, fullH, seed) }
    val near: SyntheticPanoScene.NearLayer by lazy { SyntheticPanoScene.nearLayer(fullW, fullH, seed) }

    /** Where each photo truly sits on the native cylinder canvas. */
    fun truePositions() = lefts.indices.map { PanoPoint(lefts[it], topsUsed[it]) }

    /** Photo [i] as a pinhole camera takes it at [tw] x [th], with [shot]'s imperfections; [withNear] adds the parallax layer. */
    fun render(i: Int, tw: Int, th: Int, shot: SyntheticPanoScene.Shot = SyntheticPanoScene.Shot(), withNear: Boolean = false): PanoPixels {
        val s = tw.toDouble() / photoW
        val fPix = f * s
        val centreU = lefts[i] + warpedW / 2.0
        val centreV = topsUsed[i] + photoH / 2.0
        val random = Random(shot.seed * 7919L + i)
        val angle = Math.toRadians(shot.rollDegrees)
        val c = cos(angle)
        val sn = sin(angle)
        val maxR2 = (tw / 2.0) * (tw / 2.0) + (th / 2.0) * (th / 2.0)
        val rgb = FloatArray(3)
        val out = IntArray(tw * th)
        for (r in 0 until th) {
            val ly = r + 0.5 - th / 2.0
            for (col in 0 until tw) {
                val lx = col + 0.5 - tw / 2.0
                val x = lx * c - ly * sn + shot.shear * ly
                val y = lx * sn + ly * c
                val u = centreU + f * atan(x / fPix)
                val v = centreV + f * y / hypot(x, fPix)
                scene.sample(u - 0.5, v - 0.5, rgb)
                if (withNear) {
                    val nx = u - 0.5 + shot.nearShift
                    val ix = Math.round(nx).toInt().coerceIn(0, near.canvas.width - 1)
                    val iy = Math.round(v - 0.5).toInt().coerceIn(0, near.canvas.height - 1)
                    if (near.covers[iy * near.canvas.width + ix]) {
                        val g = near.canvas.sample(nx, v - 0.5)
                        rgb[0] = g; rgb[1] = g; rgb[2] = g
                    }
                }
                val blob = shot.blob
                if (blob != null) {
                    val ex = (col / s - blob.cx) / blob.rx
                    val ey = (r / s - blob.cy) / blob.ry
                    val e = ex * ex + ey * ey
                    if (e < 1.0) {
                        val alpha = if (e < 0.8) 1f else (1f - ((e - 0.8) / 0.2).toFloat())
                        for (ch in 0 until 3) rgb[ch] = alpha * blob.level + (1 - alpha) * rgb[ch]
                    }
                }
                val vignette = 1.0 - shot.vignette * (lx * lx + ly * ly) / maxR2
                val noise = if (shot.noiseSigma > 0) random.nextGaussian() * shot.noiseSigma else 0.0
                out[r * tw + col] = ColourScene.pack(
                    (rgb[0] * shot.gain + shot.bias) * vignette + noise,
                    (rgb[1] * shot.gain + shot.bias) * vignette + noise,
                    (rgb[2] * shot.gain + shot.bias) * vignette + noise,
                )
            }
        }
        return PanoPixels(tw, th, out)
    }

    fun assets(): List<MediaAsset> = List(count) { asset(it) }

    fun asset(i: Int, name: String = "IMG_%04d.jpg".format(i + 1)): MediaAsset = MediaAsset(
        id = MediaId(100L + i), contentUriString = "content://fake/$i", displayName = name, mimeType = "image/jpeg",
        bucketName = null, dateTakenMillis = 1_700_000_000_000L + i * 1000L, dateModifiedSeconds = 0,
        width = photoW, height = photoH, sizeBytes = 0, relativePath = null, isFavorite = false, isTrashed = false,
    )
}

/**
 * A [PanoramaHost] over a [render] function: photo [i] at the size the decoder's limits ask for, the
 * way `decodeUpright` sizes it ([targetSize]). Records every decode, can protect or break photos, and
 * runs [onDecode] on each call (the place a test cancels the job or changes the world).
 */
internal class FakeHost(
    val assets: List<MediaAsset>,
    private val fullSizes: (Int) -> PanoSize,
    private val focal35: (Int) -> Int?,
    private val render: (index: Int, width: Int, height: Int) -> PanoPixels,
) : PanoramaHost {
    class Call(val index: Int, val limits: DecodeLimits)

    val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())
    val focalReads = AtomicInteger(0)
    val protectedIds: MutableSet<MediaId> = Collections.synchronizedSet(HashSet())
    val unreadable: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /** Called at the start of every decode, with the index and how many decodes have been asked so far (1-based). */
    @Volatile var onDecode: (index: Int, count: Int) -> Unit = { _, _ -> }

    /** Photos in this set decode as null only once the working pass is over (a file that vanished mid-job). */
    val unreadableAfterWorking: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /** Decodes asked at the working size (the first pass). */
    val workingCalls: List<Call> get() = synchronized(calls) { calls.filter { it.limits.maxLongEdge == PanoramaJob.WORKING_EDGE } }

    override fun mayExport(asset: MediaAsset): Boolean = asset.id !in protectedIds

    override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): PanoDecoded? {
        val index = assets.indexOfFirst { it.id == asset.id }
        val count: Int
        synchronized(calls) { calls += Call(index, limits); count = calls.size }
        onDecode(index, count)
        if (!mayExport(asset) || index in unreadable) return null
        val working = limits.maxLongEdge == PanoramaJob.WORKING_EDGE
        if (!working && index in unreadableAfterWorking) return null
        val full = fullSizes(index)
        val (tw, th) = targetSize(full.width, full.height, limits)
        return PanoDecoded(render(index, tw, th), full.width, full.height)
    }

    override suspend fun focal35mm(asset: MediaAsset): Int? {
        focalReads.incrementAndGet()
        if (!mayExport(asset)) return null
        return focal35(assets.indexOfFirst { it.id == asset.id })
    }
}

/** A [PanoramaCanvas] on a heap array that remembers it was handed back. */
internal class FakeCanvas(width: Int, height: Int) : PanoramaCanvas {
    val inner = IntArrayCanvas(width, height)
    @Volatile var released = false
    override val width: Int get() = inner.width
    override val height: Int get() = inner.height
    override fun read(x: Int, y: Int, w: Int, h: Int, into: IntArray) = inner.read(x, y, w, h, into)
    override fun write(x: Int, y: Int, w: Int, h: Int, from: IntArray) = inner.write(x, y, w, h, from)
    override fun release() { released = true }
}

/** A [PanoramaSink] that keeps what it was asked to publish, and can run out of memory or fail on demand. */
internal class FakeSink : PanoramaSink {
    class Published(val canvas: FakeCanvas, val crop: AutoCrop.CropRect, val dateTakenMillis: Long, val sourceName: String, val pixels: IntArray)

    val canvases: MutableList<FakeCanvas> = Collections.synchronizedList(ArrayList())
    val published: MutableList<Published> = Collections.synchronizedList(ArrayList())

    /** A canvas with more pixels than this throws [OutOfMemoryError] when asked for. */
    @Volatile var oomAbove: Long = Long.MAX_VALUE

    /** Thrown from [publish] instead of saving. */
    @Volatile var failWith: Exception? = null

    /** Run inside [publish], just before the sink's own look at `stillAllowed`. */
    @Volatile var beforeLook: () -> Unit = {}

    /** A careless sink that never asks `stillAllowed`: the job's own look must then be the one that catches it. */
    @Volatile var ignoresLook: Boolean = false

    override fun newCanvas(width: Int, height: Int): PanoramaCanvas {
        if (width.toLong() * height > oomAbove) throw OutOfMemoryError("test: a ${width}x$height canvas does not fit")
        return FakeCanvas(width, height).also { canvases += it }
    }

    override suspend fun publish(
        canvas: PanoramaCanvas,
        crop: AutoCrop.CropRect,
        dateTakenMillis: Long,
        sourceName: String,
        stillAllowed: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): String {
        onProgress(0f)
        failWith?.let { throw it }
        beforeLook()
        if (!ignoresLook && !stillAllowed()) throw PanoramaProtectedException()
        onProgress(1f)
        val fake = canvas as FakeCanvas
        published += Published(fake, crop, dateTakenMillis, sourceName, fake.inner.pixels.copyOf())
        return "content://fake/published/${published.size}"
    }
}

/** A host over a [CylinderRig], every photo taken with [shots] (by index) and [withNear] parallax. */
internal fun rigHost(
    rig: CylinderRig,
    shots: (Int) -> SyntheticPanoScene.Shot = { SyntheticPanoScene.Shot() },
    withNear: Boolean = false,
    focal35: (Int) -> Int? = { rig.f35 },
): FakeHost = FakeHost(
    assets = rig.assets(),
    fullSizes = { PanoSize(rig.photoW, rig.photoH) },
    focal35 = focal35,
    render = { i, w, h -> rig.render(i, w, h, shots(i), withNear) },
)

/** The canvas of the one panorama [sink] was given, compared with the rig's scene over the crop. */
internal fun errorAgainstScene(rig: CylinderRig, sink: FakeSink, constant: Double = 1.0): Error {
    val p = sink.published.single()
    // The canvas is the native cylinder canvas at scale 1 (these rigs are small); it starts at the
    // true smallest x (0, the first photo's left) and the true smallest top.
    val top = rig.topsUsed.min()
    return compareToTruth(p.canvas.inner, p.crop) { x, y, into -> rig.scene.expected(x, y + top, constant, into) }
}
