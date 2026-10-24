package com.fotoxplorr.app.recognition

import android.content.Context
import android.graphics.Bitmap
import com.fotoxplorr.app.lift.canLiftSubject
import com.fotoxplorr.app.lift.catchingResult
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.textselect.TextLayout
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The word-level text layout of the photo being viewed, made on demand and held in memory only.
 *
 * Why this exists: the library index keeps whole-block boxes read at 640 px, which is enough to
 * search by and to select a block, but a finger cannot select a word from it. Selecting words
 * needs a second pass over a larger decode of ONE photo, and that is this class. It writes nothing
 * to disk and nothing back to the recognition store (persisting words would mean a schema change
 * and a library-wide re-read), and it never logs text.
 *
 * - One layout per (photo, [recognitionRevision]): a replaced file is a new key, so a stale layout
 *   is never served for the new pixels. A small LRU ([capacity] layouts) lets swiping back to a
 *   photo skip the pass.
 * - Single-flight per key: callers asking for the same photo while its pass runs share it. A pass
 *   whose last caller leaves (a swipe to the next photo) is cancelled rather than finished for
 *   nobody. Passes run one at a time.
 * - The recogniser is created on the first pass and closed by [release], which the viewer calls on
 *   exit; [release] also drops every cached layout, since they hold the text of the photos viewed.
 *   The provider stays usable afterwards (the next pass builds a new recogniser).
 * - Nothing here throws for a photo that cannot be read: [layoutFor] returns null, and the caller
 *   keeps the block-only layout from [blockOnly]. Cancellation of the CALLER is the one exception
 *   that propagates.
 *
 * The two OCR sources can disagree (the index sees 640 px, this sees 2048 px), so a photo can be
 * found by a word the selection layer does not show. That is accepted to avoid a library-wide
 * re-read.
 */
class TextLayoutProvider internal constructor(
    private val engine: Engine,
    private val capacity: Int = DEFAULT_CAPACITY,
    dispatcher: CoroutineContext = Dispatchers.Default,
) {
    /** The production provider: 2048 px decode, bundled ML Kit Latin recogniser. */
    constructor(context: Context) : this(DecodingEngine(context.applicationContext) { MlKitWordRecogniser() })

    /** What actually reads a photo; faked in tests. */
    internal interface Engine {
        /** The word-level layout of [asset], or null when it cannot be read. May throw for a cancelled caller only. */
        suspend fun read(asset: MediaAsset): TextLayout?

        /** Free whatever [read] holds between photos (the recogniser). Safe to call twice. */
        fun close()
    }

    private data class Key(val id: MediaId, val revision: Long)

    private class Flight(val result: Deferred<TextLayout?>) {
        var waiters = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    // One pass at a time, and no close under a running pass.
    private val engineLock = Mutex()

    // Both guarded by [lock]. Access-ordered, so the eldest entry is the least recently used.
    private val cache = object : LinkedHashMap<Key, TextLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayout>?): Boolean = size > capacity
    }
    private val flights = HashMap<Key, Flight>()

    /** The layout already held for [asset], or null. Does not start a pass. */
    fun peek(asset: MediaAsset): TextLayout? = synchronized(lock) { cache[keyOf(asset)] }

    /**
     * The word-level layout of [asset]'s pixels, from memory or a fresh pass; null for a photo
     * that is not a still raster image, cannot be decoded, or whose pass failed. A photo with no
     * text gives an EMPTY layout, not null, so the caller can say "no selectable text found" and
     * not retry. [isAnimated] is the caller's knowledge (an asset alone cannot tell a still GIF).
     */
    suspend fun layoutFor(asset: MediaAsset, isAnimated: Boolean = false): TextLayout? {
        if (!canLiftSubject(asset, isAnimated)) return null
        val key = keyOf(asset)
        val flight = join(key, asset) ?: return synchronized(lock) { cache[key] }
        try {
            return flight.result.await()
        } catch (e: CancellationException) {
            // Our own cancellation goes up; a pass that was cancelled under a caller that is still
            // alive (release() ran while it waited) is simply "no layout".
            if (!currentCoroutineContext().isActive) throw e
            return null
        } finally {
            leave(key, flight)
        }
    }

    /**
     * Forget everything and close the recogniser: the viewer was left. Cancels passes in flight,
     * drops every cached layout and closes the engine once those passes have stopped. Returns the
     * job doing the closing (a caller that does not care ignores it).
     */
    fun release(): Job {
        synchronized(lock) {
            flights.values.forEach { it.result.cancel() }
            flights.clear()
            cache.clear()
        }
        // After the cancelled passes have let go of the engine: closing a recogniser under a
        // running pass would fail that pass for no reason.
        return scope.launch { engineLock.withLock { engine.close() } }
    }

    private fun keyOf(asset: MediaAsset) = Key(asset.id, asset.recognitionRevision())

    /** Registers a waiter on [key]'s pass (starting it if needed); null when the layout is already cached. */
    private fun join(key: Key, asset: MediaAsset): Flight? = synchronized(lock) {
        if (cache[key] != null) return@synchronized null
        val flight = flights[key] ?: Flight(scope.async { run(key, asset) }).also { created ->
            flights[key] = created
            // Registered after the flight is in the map, so a pass that finishes at once still removes it.
            created.result.invokeOnCompletion {
                synchronized(lock) { if (flights[key] === created) flights.remove(key) }
            }
        }
        flight.also { it.waiters += 1 }
    }

    private fun leave(key: Key, flight: Flight) = synchronized(lock) {
        flight.waiters -= 1
        if (flight.waiters <= 0 && !flight.result.isCompleted) {
            flight.result.cancel()
            if (flights[key] === flight) flights.remove(key)
        }
    }

    private suspend fun run(key: Key, asset: MediaAsset): TextLayout? {
        // A pass that throws (anything but its caller's cancellation) is "no layout", not a crash.
        val layout = engineLock.withLock { catchingResult { engine.read(asset) }.getOrNull() }
        if (layout != null) {
            synchronized(lock) {
                // A release() that ran during the pass already cleared the cache; do not refill it.
                if (flights.containsKey(key)) cache[key] = layout
            }
        }
        return layout
    }

    companion object {
        const val DEFAULT_CAPACITY = 8

        /**
         * The layout available before (or without) a word-level pass: the stored whole-block boxes
         * of [blocks] as one tappable word each, in a [frameWidth] x [frameHeight] frame that must
         * be the photo's UPRIGHT size (the stored boxes are normalised to the upright decode, never
         * to MediaStore's width and height; TRAPS 48). Empty for no blocks.
         */
        fun blockOnly(blocks: List<TextBlock>, frameWidth: Int, frameHeight: Int): TextLayout =
            TextLayout.fromLegacyBlocks(blocks, frameWidth, frameHeight)
    }
}

/**
 * Decodes the photo upright at up to 2048 px on the long edge and runs [recogniserFactory]'s
 * recogniser over it, creating the recogniser on first use. 2048 px is the size small print
 * survives at; the pass is for ONE photo, so the 12 MB decode is freed the moment it finishes.
 */
internal class DecodingEngine(
    private val appContext: Context,
    private val recogniserFactory: () -> WordRecogniser,
) : TextLayoutProvider.Engine {
    private val holder = Any()
    private var recogniser: WordRecogniser? = null

    override suspend fun read(asset: MediaAsset): TextLayout? {
        // decodeUpright can throw before its own runCatching (a revoked grant, a corrupt EXIF
        // block, running out of memory), so it is guarded here like every other decode (TRAPS 49).
        val decoded = catchingResult { decodeUpright(appContext, asset.contentUri, LIMITS) }.getOrNull() ?: return null
        var bitmap: Bitmap = decoded.bitmap
        try {
            return catchingResult {
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    if (copy !== bitmap) bitmap.recycle()
                    bitmap = copy
                }
                val blocks = recogniser().recognise(bitmap)
                TextLayout.build(blocks, bitmap.width, bitmap.height)
            }.getOrNull()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        val held = synchronized(holder) { recogniser.also { recogniser = null } }
        if (held != null) catchingResult { held.close() }
    }

    private fun recogniser(): WordRecogniser = synchronized(holder) {
        recogniser ?: recogniserFactory().also { recogniser = it }
    }

    private companion object {
        /** About 3.1 MP at 4:3, so a 2048 x 1536 frame is not shrunk. */
        val LIMITS = DecodeLimits(maxLongEdge = 2048, maxPixels = 3_200_000L)
    }
}
