@file:OptIn(ExperimentalLayoutApi::class)

package com.fotoxplorr.app.wall

import android.content.Context
import android.content.pm.PackageManager
import android.print.PrintManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobHandle
import com.fotoxplorr.app.jobs.RunningJob
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.ui.RoomStyle
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the hang panel can do about a print, and what it is doing now. [running] and [progress]
 * come from the app's job list, so the same job shows in the activity shade and the notification
 * with its Cancel; [canPrint] is whether this device has system printing at all.
 */
internal class PrintActions(
    val running: Boolean,
    val progress: Float?,
    val canPrint: Boolean,
    val onSave: (WallFrame) -> Unit,
    val onPrint: (WallFrame) -> Unit,
    val onCancel: () -> Unit,
)

/** What the controls hold between taps and must let go of when the screen is left. */
private class PrintHolder {
    var handle: JobHandle? = null
    var adapter: PhotoPrintAdapter? = null
}

private val NO_JOBS = MutableStateFlow<List<RunningJob>>(emptyList())

/**
 * The actions behind the print controls, or null when this host has no job list to run an export
 * on (then the controls are not shown at all).
 *
 * Saving runs as a job on the app's `JobRunner` (found through the view-model store, as the backup
 * rows do), kind `EXPORTING`, so it has progress, a notification and Cancel. Printing opens the
 * platform print dialog with a [PhotoPrintAdapter].
 *
 * Both read pixels only through [env] (`decode`, behind `mayExport`), and both are tied to this
 * screen: leaving it cancels a running export and shuts a print adapter, because the live "is this
 * photo still allowed" check belongs to the host composition that is going away. Nothing here is
 * saved across process death; an export in flight is simply gone, and it never half-writes (the
 * library row is only completed at the end).
 */
@Composable
internal fun rememberPrintActions(env: ExperimentEnv, assets: Map<Long, MediaAsset>): PrintActions? {
    val owner = LocalViewModelStoreOwner.current
    val uiContext = LocalContext.current
    val runner = remember(owner) { runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull() }
    val jobs by (runner?.jobs ?: NO_JOBS).collectAsState()
    val job = jobs.firstOrNull { it.title == WallCopy.PRINT_JOB_TITLE }
    val latestEnv by rememberUpdatedState(env)
    val latestAssets by rememberUpdatedState(assets)
    val exporter = remember { PrintExporter(env.context) }
    val held = remember { PrintHolder() }
    val canPrint = remember(uiContext) { uiContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING) }
    DisposableEffect(Unit) {
        onDispose {
            held.handle?.cancel()
            held.adapter?.close()
        }
    }
    if (runner == null) return null

    fun sourceFor(asset: MediaAsset) = PrintSource(
        asset = asset,
        mayExport = { latestEnv.mayExport(asset) },
        decode = { limits -> latestEnv.decode(asset, limits) },
    )

    return PrintActions(
        running = job != null,
        progress = job?.progress,
        canPrint = canPrint,
        onSave = save@{ frame ->
            val asset = latestAssets[frame.assetId] ?: return@save
            if (runner.jobs.value.any { it.title == WallCopy.PRINT_JOB_TITLE }) return@save
            val size = PrintSize(frame.wCm.toDouble(), frame.hCm.toDouble())
            held.handle = runner.launch(title = WallCopy.PRINT_JOB_TITLE, kind = ActivityKind.EXPORTING) { onProgress ->
                exporter.save(sourceFor(asset), size, onProgress).map { WallCopy.SAVED }
            }
        },
        onPrint = print@{ frame ->
            val asset = latestAssets[frame.assetId] ?: return@print
            val manager = uiContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return@print
            val size = PrintSize(frame.wCm.toDouble(), frame.hCm.toDouble())
            held.adapter?.close()
            val adapter = PhotoPrintAdapter(WallCopy.PRINT_DOC_NAME, size) { placed, use ->
                exporter.render(sourceFor(asset), placed, PrintPlanner.TARGET_DPI) { bitmap, _ -> use(bitmap) }
            }
            held.adapter = adapter
            manager.print(WallCopy.PRINT_DOC_NAME, adapter, null)
        },
        onCancel = { held.handle?.cancel() },
    )
}

/**
 * "Print it": what the file will be (pixels and dpi, from the same plan the export uses), a Save
 * button, a Print button where the device can print, and while a file is being made a progress bar
 * and Cancel. The picture the numbers are for is the selected one. Every control is a button with
 * words, so there is nothing to reach by touch gesture alone.
 */
@Composable
internal fun ExportControls(frame: WallFrame, picture: HungPicture?, actions: PrintActions) {
    val decoded = picture?.decoded
    val plan = decoded?.let { PrintPlanner.plan(it.sourceWidth, it.sourceHeight, PrintSize(frame.wCm.toDouble(), frame.hCm.toDouble())) }
    Text(
        WallCopy.PRINT_HEADER,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { heading() },
    )
    if (plan != null) Text(WallCopy.fileWillBe(plan.outW, plan.outH, plan.dpi), style = MaterialTheme.typography.bodySmall)
    if (actions.running) {
        val making = WallCopy.MAKING
        if (actions.progress != null) {
            LinearProgressIndicator(
                progress = { actions.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = making },
                color = Color.White,
                trackColor = RoomStyle.InkFaint,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = making },
                color = Color.White,
                trackColor = RoomStyle.InkFaint,
            )
        }
        Text(making, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkMuted)
        WallButton(WallCopy.CANCEL, actions.onCancel)
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WallButton(WallCopy.SAVE_PRINT, { actions.onSave(frame) }, enabled = plan != null, primary = true)
            if (actions.canPrint) WallButton(WallCopy.PRINT, { actions.onPrint(frame) }, enabled = plan != null)
        }
    }
    Text(WallCopy.PLACE_LEFT_OUT, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
    if (actions.canPrint) Text(WallCopy.PRINT_REAL_SIZE, style = MaterialTheme.typography.bodySmall, color = RoomStyle.InkFaint)
}
