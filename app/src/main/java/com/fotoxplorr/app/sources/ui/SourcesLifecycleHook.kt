package com.fotoxplorr.app.sources.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.sources.runtime.SourceJobs
import com.fotoxplorr.app.sources.runtime.SourcesRuntime

/**
 * Once per launch: brings the sources' states in line with the grants Android still holds, and
 * starts the reads of sources that asked to be read whenever they are there (default off) if the
 * person's own background rules allow it. Draws nothing and, with no sources, opens nothing.
 */
@Composable
fun SourcesLifecycleHook(runner: JobRunner?) {
    val context = LocalContext.current
    LaunchedEffect(runner) {
        if (runner != null) SourceJobs.startOnConnect(context, runner, SourcesRuntime.get(context))
    }
}
