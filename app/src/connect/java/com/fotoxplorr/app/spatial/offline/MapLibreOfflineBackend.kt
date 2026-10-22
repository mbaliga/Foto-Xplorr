package com.fotoxplorr.app.spatial.offline

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [OfflineBackend] over MapLibre 13.0.2's own `OfflineManager`. Thin on purpose: every rule lives
 * in [MapLibreRegionEngine]. Written against the library's bytecode (method shapes read with
 * javap), never run: there is no device or emulator in the build environment, and the library's
 * native code does not load on a JVM, so this class has no unit test. Its device checks are the
 * first thing to do (ADR-015).
 *
 * Call it only on the main thread. It keeps the `OfflineRegion` objects it has handed out, keyed by
 * id, because the library wants the same object back for `setObserver` and `setDownloadState`;
 * [list] refreshes them.
 *
 * What it asks for: the Liberty style, a tile pyramid over the box and zoom range, pixel ratio of
 * the screen (so the sprite sheet fetched is the one the live map will ask for), and no ideographic
 * glyph ranges (they are large; place names in Chinese, Japanese and Korean will therefore be
 * missing from a saved area). Whether skipping them is enough to stay under the byte cap is a
 * guess until measured.
 */
internal class MapLibreOfflineBackend(context: Context) : OfflineBackend {

    private val appContext = context.applicationContext
    private val pixelRatio: Float = appContext.resources.displayMetrics.density.coerceIn(1f, MAX_PIXEL_RATIO)
    private val regions = HashMap<Long, OfflineRegion>()

    private val manager: OfflineManager by lazy {
        try {
            MapLibre.getInstance(appContext)
            OfflineManager.getInstance(appContext)
        } catch (e: Throwable) {
            throw BackendException("The map library could not start.", e)
        }
    }

    override suspend fun list(): List<BackendRegion> {
        val found = suspendCancellableCoroutine { cont ->
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    if (cont.isActive) cont.resume(offlineRegions?.toList().orEmpty())
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWithException(BackendException(error))
                }
            })
        }
        found.forEach { regions[it.id] = it }
        return found.map { BackendRegion(it.id, it.metadata, statusOf(it)) }
    }

    override suspend fun create(definition: RegionDefinition, metadata: ByteArray): BackendRegion {
        val box = definition.box
        // Unwrapped longitudes are how the library describes a box over the date line; the engine
        // refuses those boxes until that is verified, so this is the plain case.
        val bounds = LatLngBounds.from(box.north, box.east, box.south, box.west)
        val library = OfflineTilePyramidRegionDefinition(
            definition.styleUrl,
            bounds,
            definition.minZoom.toDouble(),
            definition.maxZoom.toDouble(),
            pixelRatio,
            false,
        )
        val region = suspendCancellableCoroutine { cont ->
            manager.createOfflineRegion(
                library,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        if (cont.isActive) cont.resume(offlineRegion)
                    }

                    override fun onError(error: String) {
                        if (cont.isActive) cont.resumeWithException(BackendException(error))
                    }
                },
            )
        }
        regions[region.id] = region
        return BackendRegion(region.id, metadata, statusOf(region))
    }

    override suspend fun status(id: Long): BackendStatus = statusOf(known(id))

    override fun listen(id: Long, listener: BackendListener?) {
        val region = known(id)
        region.setObserver(
            if (listener == null) {
                SILENT
            } else {
                object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) = listener.onStatus(status.toBackend())

                    override fun onError(error: OfflineRegionError) = listener.onError(kindOf(error.reason))

                    override fun mapboxTileCountLimitExceeded(limit: Long) = listener.onError(DownloadErrorKind.OTHER)
                }
            },
        )
    }

    override fun setActive(id: Long, active: Boolean) {
        known(id).setDownloadState(if (active) OfflineRegion.STATE_ACTIVE else OfflineRegion.STATE_INACTIVE)
    }

    override suspend fun delete(id: Long) {
        val region = known(id)
        suspendCancellableCoroutine { cont ->
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(error: String) {
                    if (cont.isActive) cont.resumeWithException(BackendException(error))
                }
            })
        }
        regions.remove(id)
        // The database keeps the freed pages until it is packed; ask for that, and do not wait for
        // it or fail the delete if it cannot be done.
        runCatching {
            manager.packDatabase(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) = Unit
            })
        }
    }

    private suspend fun statusOf(region: OfflineRegion): BackendStatus =
        suspendCancellableCoroutine { cont ->
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (cont.isActive) cont.resume(status?.toBackend() ?: BackendStatus.EMPTY)
                }

                override fun onError(error: String?) {
                    if (cont.isActive) cont.resumeWithException(BackendException(error ?: "status failed"))
                }
            })
        }

    private fun known(id: Long): OfflineRegion =
        regions[id] ?: throw BackendException("No saved area $id.")

    private fun OfflineRegionStatus.toBackend() = BackendStatus(
        completedResourceCount = completedResourceCount,
        completedResourceSize = completedResourceSize,
        completedTileCount = completedTileCount,
        requiredResourceCount = requiredResourceCount,
        requiredCountIsPrecise = isRequiredResourceCountPrecise,
        complete = isComplete,
    )

    private fun kindOf(reason: String?): DownloadErrorKind = when (reason) {
        OfflineRegionError.REASON_NOT_FOUND -> DownloadErrorKind.NOT_FOUND
        OfflineRegionError.REASON_SERVER -> DownloadErrorKind.SERVER
        OfflineRegionError.REASON_CONNECTION -> DownloadErrorKind.CONNECTION
        else -> DownloadErrorKind.OTHER
    }

    private companion object {
        /** The sprite sheet comes in 1x and 2x; asking for more than 2 changes nothing and is not tested. */
        const val MAX_PIXEL_RATIO = 2f

        /** Stands in when nobody is listening, so a stopped region's observer is never left dangling. */
        val SILENT = object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = Unit
            override fun onError(error: OfflineRegionError) = Unit
            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
        }
    }
}
