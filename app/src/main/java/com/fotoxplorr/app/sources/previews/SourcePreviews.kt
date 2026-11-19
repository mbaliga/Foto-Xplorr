package com.fotoxplorr.app.sources.previews

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.core.index.sources.SourceState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The process's one offline previews service: the [store], the person's [settings], the [writer]
 * and which sources are OFFLINE right now. OFFLINE is not REVOKED: a revoked source is hidden by
 * the merge and never reaches a tile, an offline one is listed and its tiles show the stored preview.
 *
 * Building it opens no file and no database; the offline set stays empty (and `fotoz-sources.db`
 * is never opened for it) until [trackStates] is called.
 */
class SourcePreviews(
    val store: SourcePreviewStore,
    val settings: PreviewSettings,
    val writer: PreviewWriter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val offline = MutableStateFlow<Set<Long>>(emptySet())

    /** Source rows whose drive or folder cannot be read right now. */
    val offlineRows: StateFlow<Set<Long>> = offline.asStateFlow()

    val pass: PreviewPass = PreviewPass(writer, store, settings)

    fun setOffline(rows: Set<Long>) { offline.value = rows }

    private val inFlight = HashSet<String>()
    private var tracking = false

    /** Follows the sources' states. Safe to call twice. */
    @Synchronized
    fun trackStates(runtime: SourcesRuntime) {
        if (tracking) return
        tracking = true
        scope.launch {
            runtime.statuses().map { list -> list.filter { it.info.state == SourceState.OFFLINE }.map { it.info.row }.toSet() }
                .collect { offline.value = it }
        }
    }

    /** Fire and forget: a tile of a source asset decoded, so its file is readable now. Never on the main thread. */
    fun captureAsync(asset: MediaAsset) {
        val key = asset.sourceRef ?: return
        synchronized(inFlight) { if (!inFlight.add(key)) return }
        scope.launch {
            try { writer.capture(asset) } finally { synchronized(inFlight) { inFlight.remove(key) } }
        }
    }

    fun clearAsync(sourceRow: Long) { scope.launch { store.clear(sourceRow) } }

    fun setCapAsync(sourceRow: Long, capBytes: Long) {
        settings.setCapBytes(sourceRow, capBytes)
        scope.launch { store.enforceCap(sourceRow) }
    }

    companion object {
        @Volatile private var instance: SourcePreviews? = null

        fun get(context: Context): SourcePreviews = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): SourcePreviews {
            val settings = PrefsPreviewSettings(app)
            // noBackupFilesDir: rebuildable cache that must never ride into a cloud backup or a device transfer.
            val store = SourcePreviewStore(File(app.noBackupFilesDir, "source-previews"), capOf = settings::capBytes)
            val previews = SourcePreviews(store, settings, PreviewWriter(app, store, settings))
            previews.trackStates(SourcesRuntime.get(app))
            return previews
        }
    }
}

/** Lets a test (or a preview) supply the service; null means "use the process's own, for source assets only". */
val LocalSourcePreviews = staticCompositionLocalOf<SourcePreviews?> { null }
