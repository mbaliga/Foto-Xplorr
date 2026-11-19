package com.fotoxplorr.app.sources.runtime

import android.content.Context
import com.fotoxplorr.app.background.WorkRuleEvaluator
import com.fotoxplorr.app.background.WorkRulesStore
import com.fotoxplorr.app.background.WorkVerdict
import com.fotoxplorr.app.background.readDeviceState
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.JobRunner

/**
 * Puts an index on the app's [JobRunner], so it shows in the activity shade with progress and a
 * stop button and keeps the foreground service alive (SCANNING). Resuming is not a separate
 * action: the next index of a source continues the checkpoint a stopped or killed one left.
 */
object SourceJobs {
    /** Starts indexing [sourceRow]; null when it is already being indexed (no second job for one source). */
    fun launch(runner: JobRunner, runtime: SourcesRuntime, sourceRow: Long, name: String): JobHandle? {
        if (runtime.isIndexing(sourceRow)) return null
        return runner.launch<IndexOutcome>(
            title = SourceJobCopy.title(name),
            kind = ActivityKind.SCANNING,
        ) { onProgress ->
            runCatching { runtime.index(sourceRow) { p -> onProgress(p.fraction) } }
        }
    }

    /**
     * The sources that asked to be read whenever they are there (default off, ADR-020 Q12), if the
     * person's own background rules allow heavy work now. Started once per app launch.
     */
    suspend fun startOnConnect(context: Context, runner: JobRunner, runtime: SourcesRuntime) {
        runtime.refreshStates()
        val candidates = runtime.indexOnConnectCandidates()
        if (candidates.isEmpty()) return
        val rules = WorkRulesStore(context.applicationContext).observe().value
        if (WorkRuleEvaluator.evaluate(rules, readDeviceState(context.applicationContext)) !is WorkVerdict.Allowed) return
        for (row in candidates) launch(runner, runtime, row, runtime.sourceName(row) ?: continue)
    }
}

/** The one sentence the job shows in the shade. */
object SourceJobCopy {
    fun title(sourceName: String) = "Reading $sourceName"
}
