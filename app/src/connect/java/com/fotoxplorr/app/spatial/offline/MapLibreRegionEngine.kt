package com.fotoxplorr.app.spatial.offline

import android.content.Context
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
 *   region is downloading, and removed again when the last one stops, pauses, completes or is
 *   deleted. Live panning is never throttled. If installing fails, [resume] refuses and fetches
 *   nothing, and [throttleInstalled] turns false so the button disables.
 * - [pause] keeps the region and everything it fetched. Only [delete] removes data.
 * - Regions persist in the library's database across process death. After a relaunch [list] returns
 *   them, none running; the caller turns each unfinished one into a paused, resumable entry (see
 *   [OfflineRegionReconcile]). A region whose stored metadata this app cannot read is left alone
 *   and not listed: it may belong to something else, and it was never shown to the person (TRAPS #64).
 *
 * [throttleInstalled] here means "the limit can be applied and [resume] will apply it": the cap is
 * deliberately NOT left installed between downloads, so it is probed once when the engine is built
 * (install, then restore at once, no request is made) and again for real on every [resume].
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
    private var capApplied = false

    @Volatile
    private var capUsable: Boolean = probeCap()

    override val throttleInstalled: Boolean get() = capUsable

    private fun probeCap(): Boolean {
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
                    runCatching { backend.listen(id, null) }
                    release(id)
                    throw e
                }
                EngineResult.Ok(Unit)
            }
        }
    }

    override suspend fun pause(id: Long): EngineResult<Unit> {
        if (synchronized(lock) { id !in feeds }) return EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        return guarded {
            withContext(dispatcher) {
                try {
                    backend.setActive(id, false)
                    backend.listen(id, null)
                    // The numbers the pause stopped at, without the error that came with the last
                    // reading, so what the person sees on Resume starts from the truth.
                    runCatching { backend.status(id) }.getOrNull()?.let { publish(id, it.toProgress()) }
                } finally {
                    release(id)
                }
                EngineResult.Ok(Unit)
            }
        }
    }

    override suspend fun delete(id: Long): EngineResult<Unit> {
        if (synchronized(lock) { id !in feeds }) return EngineResult.Failure(EngineFailure.NO_SUCH_REGION)
        return guarded {
            withContext(dispatcher) {
                try {
                    runCatching { backend.setActive(id, false) }
                    backend.listen(id, null)
                    backend.delete(id)
                } finally {
                    release(id)
                }
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

    /** Region [id] is no longer downloading; the last one out puts the library's own client back. */
    private fun release(id: Long) {
        synchronized(lock) {
            running -= id
            if (running.isEmpty() && capApplied) {
                capApplied = false
                try {
                    cap.restore()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun listenerFor(id: Long) = object : BackendListener {
        override fun onStatus(status: BackendStatus) {
            if (synchronized(lock) { id !in running }) return // a reading that arrived after the pause
            publish(id, status.toProgress())
            if (status.complete) {
                // The library stops by itself when a region is complete; make that explicit and let go of the cap.
                runCatching {
                    backend.setActive(id, false)
                    backend.listen(id, null)
                }
                release(id)
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

        private const val FEED_BUFFER = 64

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
                    cap = MapLibreRequestCap,
                ).also { shared = it }
            }
    }
}
