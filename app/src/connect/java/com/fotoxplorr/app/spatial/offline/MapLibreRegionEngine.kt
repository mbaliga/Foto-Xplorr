package com.fotoxplorr.app.spatial.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fotoxplorr.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext

/**
 * The Connect build's [OfflineRegionEngine]: saved map areas kept in the map library's own offline
 * database, fetched by its own downloader, one region per [RegionRecord].
 *
 * Everything the engine decides is here and runs against an [OfflineBackend] and a [RequestCap],
 * so a JVM test covers it with fakes. What the real library does is NOT verified: no line of this
 * has run on a device (see [MapLibreOfflineBackend] and the device checks in ADR-015).
 *
 * Rules, in the order they matter:
 * - [create] stores a paused region and fetches nothing. [resume] is the only call that can use the
 *   network, and the caller makes it only from an explicit tap (TRAPS #61, #71). [list] and
 *   [progress] never start a download.
 * - The provider's limit of four requests in flight is installed by [resume], for as long as any
 *   region is downloading or has been told to stop and has not yet said so. It is taken off again
 *   only after the last such region has reported itself stopped, or after [SETTLE_MILLIS] if it
 *   never does: [OfflineBackend.setActive] with false only posts the request to the library's own
 *   thread, and a request the downloader still starts in that window must not go out uncapped.
 *   Live panning is not throttled outside a download and those few seconds after it. If
 *   installing fails, [resume] refuses and fetches nothing, and [throttleInstalled] turns false so
 *   the button disables.
 * - [pause] keeps the region and everything it fetched. Only [delete] removes data.
 * - Regions persist in the library's database across process death. After a relaunch [list] returns
 *   them, none running; the pane shows each unfinished one as paused and resumable (a stopped area
 *   with no recorded reason reads as [PauseReason.RESTARTED]). A region whose stored metadata this
 *   app cannot read is left alone and not listed: it may belong to something else, and it was never
 *   shown to the person (TRAPS #64).
 *
 * [throttleInstalled] here means "the limit can be applied and [resume] will apply it": the cap is
 * deliberately NOT left installed between downloads, so it is probed once when the engine is built
 * (install, then restore at once, no request is made) and again for real on every [resume]. The
 * request cap starts the map library itself before it touches the library's HTTP hook
 * ([MapLibreRequestCap]); a probe that ran first would fail the library's HTTP class for the process.
 *
 * It is also false in a release build until [CAP_VERIFIED_ON_DEVICE] is set: nobody has yet seen
 * the native downloader go through the capped client, and a download that ignores the limit breaks
 * the provider's terms (ADR-015). Debug builds are allowed so that the check can be done.
 *
 * Progress figures are the library's own counts; they are not this package's estimates. They count
 * every resource (style, sprites, glyphs, tiles), which is what [DownloadGuard] compares to the
 * byte cap. Whether the provider counts the same bytes is unverified.
 */
internal class MapLibreRegionEngine(
    private val backend: OfflineBackend,
    private val cap: RequestCap,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val styleUrl: String = LIBERTY_STYLE_URL,
    private val releaseGateOpen: Boolean = downloadsAllowed(CAP_VERIFIED_ON_DEVICE, BuildConfig.DEBUG),
    /** Runs a block on the main thread after a delay; a test replaces it with a clock it controls. */
    private val later: (delayMillis: Long, block: () -> Unit) -> Unit = ::postOnMainThread,
) : OfflineRegionEngine {

    private sealed interface FeedItem {
        data class Reading(val progress: RegionProgress) : FeedItem
        data object End : FeedItem
    }

    private class Feed {
        val items = MutableSharedFlow<FeedItem>(
            replay = 1,
            extraBufferCapacity = FEED_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        var last: RegionProgress = RegionProgress.EMPTY
    }

    private val lock = Any()
    private val feeds = HashMap<Long, Feed>()
    private val running = HashSet<Long>()

    /**
     * Regions told to stop that have not yet said so, each with the number of that stop. A later
     * stop or a resume of the same region changes the number, which turns an earlier stop's reading
     * and its timer into no-ops.
     */
    private val stopping = HashMap<Long, Int>()
    private var stopCount = 0
    private var capApplied = false

    @Volatile
    private var capUsable: Boolean = probeCap()

    override val throttleInstalled: Boolean get() = capUsable

    private fun probeCap(): Boolean {
        // A release build offers no download until the cap is confirmed on a device (ADR-015), and
        // does not touch the library's HTTP hook for nothing.
        if (!releaseGateOpen) return false
        val worked = try {
            cap.install()
        } catch (e: Exception) {
            false
        }
        try {
            cap.restore()
        } catch (_: Exception) {
        }
        return worked
    }

    override suspend fun list(): EngineResult<List<StoredRegion>> = guarded {
        withContext(dispatcher) {
            val stored = ArrayList<StoredRegion>()
            for (region in backend.list()) {
                val record = region.metadata?.let { RegionRecord.decode(it.toString(Charsets.UTF_8)) } ?: continue
                val progress = region.status.toProgress()
                val isRunning = synchronized(lock) { region.id in running }
                if (!isRunning) publish(region.id, progress)
                stored += StoredRegion(region.id, record, progress, running = isRunning)
            }
            EngineResult.Ok(stored)
        }
    }

    override suspend fun create(record: RegionRecord): EngineResult<StoredRegion> {
        if (!canDownload(record.box)) {
            return EngineResult.Failure(EngineFailure.ENGINE_ERROR, "area crosses the date line")
        }
        return guarded {
            withContext(dispatcher) {
                val region = backend.create(
                    RegionDefinition(styleUrl, record.box, record.minZoom, record.maxZoom),
                    record.encode().toByteArray(Charsets.UTF_8),
                )
                val progress = region.status.toProgress()
                publish(region.id, progress)
                EngineResult.Ok(StoredRegion(region.id, record, progress, running = false))
            }
        }
    }

    override suspend fun resume(id: Long): EngineResult<Unit> {
        if (!capUsable) return EngineResult.Failure(EngineFailure.THROTTLE_NOT_INSTALLED)
        if (synchronized(lock) { id !in feeds }) return EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        return guarded {
            withContext(dispatcher) {
                if (!applyCapFor(id)) {
                    return@withContext EngineResult.Failure(EngineFailure.THROTTLE_NOT_INSTALLED)
                }
                try {
                    backend.listen(id, listenerFor(id))
                    backend.setActive(id, true)
                } catch (e: Exception) {
                    // Nothing was started: listen is the first call and setActive the last, and a
                    // refused setActive started no download. So the cap can go at once, unless an
                    // earlier stop of this region is still being waited for.
                    runCatching { backend.listen(id, null) }
                    release(id)
                    throw e
                }
                synchronized(lock) { stopping -= id } // running again: that stop no longer applies
                EngineResult.Ok(Unit)
            }
        }
    }

    override suspend fun pause(id: Long): EngineResult<Unit> {
        if (synchronized(lock) { id !in feeds }) return EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        return guarded {
            withContext(dispatcher) {
                // Waiting for the stop is armed first, so every way out of here, a throw included,
                // ends with the cap taken off at the latest after SETTLE_MILLIS.
                beginStop(id)
                backend.setActive(id, false)
                // The numbers the pause stopped at, without the error that came with the last
                // reading, so what the person sees on Resume starts from the truth.
                runCatching { backend.status(id) }.getOrNull()?.let { publish(id, it.toProgress()) }
                EngineResult.Ok(Unit)
            }
        }
    }

    override suspend fun delete(id: Long): EngineResult<Unit> {
        if (synchronized(lock) { id !in feeds }) return EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        return guarded {
            withContext(dispatcher) {
                // A deleted region reports nothing more, so the cap comes off on the timer; the
                // library's delete callback is not taken as proof that its downloader has stopped.
                beginStop(id)
                runCatching { backend.setActive(id, false) }
                backend.delete(id)
                val feed = synchronized(lock) { feeds.remove(id) }
                feed?.items?.tryEmit(FeedItem.End)
                EngineResult.Ok(Unit)
            }
        }
    }

    override fun progress(id: Long): Flow<RegionProgress> {
        val feed = synchronized(lock) { feeds[id] } ?: return emptyFlow()
        return feed.items.takeWhile { it is FeedItem.Reading }.map { (it as FeedItem.Reading).progress }
    }

    // ---- internals ----

    /** Stores [progress] as the newest reading of region [id] and tells whoever is collecting. */
    private fun publish(id: Long, progress: RegionProgress) {
        val feed = synchronized(lock) { feeds.getOrPut(id) { Feed() }.also { it.last = progress } }
        feed.items.tryEmit(FeedItem.Reading(progress))
    }

    /**
     * Marks [id] as downloading and makes sure the request limit is in force. False, with [id] not
     * marked and the engine no longer claiming a usable cap, when installing it failed.
     */
    private fun applyCapFor(id: Long): Boolean {
        synchronized(lock) {
            if (!capApplied) {
                val worked = try {
                    cap.install()
                } catch (e: Exception) {
                    false
                }
                if (!worked) {
                    capUsable = false
                    return false
                }
                capApplied = true
            }
            running += id
            return true
        }
    }

    /**
     * Region [id] never started downloading, or no longer counts as running: the last one out puts
     * the library's own client back, unless a region is still being waited for ([beginStop]).
     */
    private fun release(id: Long) {
        synchronized(lock) {
            running -= id
            restoreIfIdle()
        }
    }

    /** Puts the library's own client back when nothing is downloading and nothing is still stopping. Holds [lock]. */
    private fun restoreIfIdle() {
        if (running.isEmpty() && stopping.isEmpty() && capApplied) {
            capApplied = false
            try {
                cap.restore()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Region [id] is about to be told to stop. Its late readings are ignored from here on, the cap
     * stays in force, and the stop ends ([finishStop]) when the library reports the region stopped
     * or [SETTLE_MILLIS] pass, whichever comes first. Call before [OfflineBackend.setActive], so a
     * reading that comes back at once is not missed.
     */
    private fun beginStop(id: Long) {
        val number = synchronized(lock) {
            running -= id
            (++stopCount).also { stopping[id] = it }
        }
        try {
            // The library delivers readings of a stopped region only to a listener asked for them.
            backend.listen(id, stoppedListener(id, number))
        } catch (_: Exception) {
            // Nothing will listen; the timer below ends the stop.
        }
        later(SETTLE_MILLIS) { finishStop(id, number) }
    }

    /** Ends stop [number] of region [id] unless a newer stop or a resume replaced it. */
    private fun finishStop(id: Long, number: Int) {
        synchronized(lock) {
            if (stopping[id] != number) return
            stopping -= id
            runCatching { backend.listen(id, null) }
            restoreIfIdle()
        }
    }

    /** Waits for the reading that says region [id] has stopped; everything else is ignored. */
    private fun stoppedListener(id: Long, number: Int) = object : BackendListener {
        override fun onStatus(status: BackendStatus) {
            if (status.stopped) finishStop(id, number)
        }

        override fun onError(kind: DownloadErrorKind) = Unit
    }

    private fun listenerFor(id: Long) = object : BackendListener {
        override fun onStatus(status: BackendStatus) {
            if (synchronized(lock) { id !in running }) return // a reading that arrived after the pause
            publish(id, status.toProgress())
            if (status.complete) {
                // The library stops by itself when a region is complete; make that explicit, and let
                // go of the cap once it says so, as for a pause.
                beginStop(id)
                runCatching { backend.setActive(id, false) }
            }
        }

        override fun onError(kind: DownloadErrorKind) {
            if (synchronized(lock) { id !in running }) return
            val last = synchronized(lock) { feeds[id]?.last } ?: return
            publish(id, last.copy(error = kind))
        }
    }

    private inline fun <T> guarded(block: () -> EngineResult<T>): EngineResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineResult.Failure(EngineFailure.ENGINE_ERROR, e.message)
    }

    companion object {
        /**
         * The Liberty style the Places map already shows (the same address as `OPEN_FREE_MAP_STYLE`
         * in `RichPhotoMapScreen`, which is private to that file: keep the two equal). A region
         * saves the resources of exactly this style; the dark style and the terrain shading are
         * not part of it.
         */
        const val LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        /** Stored in [RegionRecord.styleId]: an id, not an address. */
        const val LIBERTY_STYLE_ID = "liberty"

        /**
         * Whether the downloader is trusted with an area crossing the 180th meridian. It needs an
         * east edge past 180, which the library's tile cover may or may not wrap; not verified, so
         * such areas are refused rather than risk fetching the wrong place. Flip after the device check.
         */
        const val ANTIMERIDIAN_VERIFIED: Boolean = false

        fun canDownload(box: GeoBox): Boolean = ANTIMERIDIAN_VERIFIED || !box.crossesAntimeridian

        /**
         * Whether anyone has watched the native downloader obey the four-request limit: run a debug
         * build behind a logging proxy, download an area, and count the requests in flight (ADR-015).
         * Until it is true, a release build offers no download. Flip it only after that check.
         */
        const val CAP_VERIFIED_ON_DEVICE: Boolean = false

        fun downloadsAllowed(verified: Boolean, debugBuild: Boolean): Boolean = verified || debugBuild

        private const val FEED_BUFFER = 64

        /**
         * How long the cap is kept after a region was told to stop and has not said it stopped. Long
         * enough for the library's own thread to act on the request (it normally answers within a
         * moment), short enough that the live map is throttled for only a few seconds. A guess,
         * never measured.
         */
        const val SETTLE_MILLIS: Long = 5_000L

        private fun postOnMainThread(delayMillis: Long, block: () -> Unit) {
            Handler(Looper.getMainLooper()).postDelayed(block, delayMillis)
        }

        @Volatile
        private var shared: MapLibreRegionEngine? = null

        /**
         * The one engine of the process. It must be one: the request limit and the set of running
         * regions are process-wide state, and two engines would each think they owned the cap.
         */
        fun shared(context: Context): OfflineRegionEngine =
            shared ?: synchronized(this) {
                shared ?: MapLibreRegionEngine(
                    backend = MapLibreOfflineBackend(context.applicationContext),
                    cap = MapLibreRequestCap(context),
                ).also { shared = it }
            }
    }
}
