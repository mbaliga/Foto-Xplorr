package com.fotoxplorr.app.sources.drives

import android.content.Context
import com.fotoxplorr.app.background.WorkRuleEvaluator
import com.fotoxplorr.app.background.WorkRulesStore
import com.fotoxplorr.app.background.WorkVerdict
import com.fotoxplorr.app.background.readDeviceState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.sources.previews.DrivePreviewMapping
import com.fotoxplorr.app.sources.previews.SourcePreviews
import com.fotoxplorr.app.sources.runtime.SourceJobs
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.core.index.sources.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * [DriveSourcePort] over the real [SourcesRuntime]. Reachability is only a state column; a read
 * starts when the drive's own setting or the source's "read again when it is there" asks for it AND
 * the person's background rules allow heavy work now. [runner] is the activity's job runner, set by
 * the host while it is on screen; without one a read simply waits for the next time.
 */
class RuntimeDriveSourcePort(
    private val appContext: Context,
    private val runtime: SourcesRuntime,
    /** A recognised drive is back: maps its remembered preview policy onto its sources' keep and cap. */
    private val onReconnected: suspend (uuid: String, rows: List<Long>) -> Unit = { _, _ -> },
    private val runner: () -> JobRunner?,
) : DriveSourcePort {
    override fun knownVolumes(): Flow<Set<String>> = runtime.statuses().map { list ->
        list.filter { it.info.kind == SourceKind.SAF_TREE || it.info.kind == SourceKind.USB_SAF }
            .mapNotNullTo(HashSet()) { DriveIds.normalise(it.info.volumeUuid) }
    }.distinctUntilChanged()

    override suspend fun setReachable(uuid: String, reachable: Boolean, indexNow: Boolean) {
        val rows = runtime.setVolumeReachable(uuid, reachable)
        if (!reachable || rows.isEmpty()) return
        runCatching { onReconnected(uuid, rows) }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }
        val wanted = if (indexNow) rows else runtime.indexOnConnectCandidates().filter { it in rows }
        val jobs = runner() ?: return
        if (wanted.isEmpty()) return
        val rules = WorkRulesStore(appContext).observe().value
        if (WorkRuleEvaluator.evaluate(rules, readDeviceState(appContext)) !is WorkVerdict.Allowed) return
        for (row in wanted) SourceJobs.launch(jobs, runtime, row, runtime.sourceName(row) ?: continue)
    }

    override suspend fun stopIndex(uuid: String) {
        val wanted = DriveIds.normalise(uuid)
        // Stopping a source that is not running does nothing.
        for (s in runtime.statuses().first()) if (DriveIds.normalise(s.info.volumeUuid) == wanted) runtime.stopIndex(s.info.row)
    }
}

/** The process's one drive lifecycle. Building it opens nothing and reads no drive. */
object DriveRuntime {
    @Volatile private var controller: DriveController? = null
    @Volatile private var jobRunner: JobRunner? = null

    fun controller(context: Context): DriveController = controller ?: synchronized(this) {
        controller ?: build(context.applicationContext).also { controller = it }
    }

    /** The host tells the lifecycle which runner to read on (null when it leaves the screen). */
    fun setRunner(runner: JobRunner?) {
        jobRunner = runner
    }

    internal fun forgetInstance() = synchronized(this) { controller = null }

    private fun build(app: Context): DriveController {
        val store = PrefsDriveSettingsStore(app)
        return DriveController(
            store = store,
            port = RuntimeDriveSourcePort(
                app, SourcesRuntime.get(app),
                onReconnected = { uuid, rows ->
                    val previews = SourcePreviews.get(app)
                    DrivePreviewMapping.apply(store.get(uuid).previews, rows, previews.settings) { previews.store.enforceCap(it) }
                },
            ) { jobRunner },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
}
