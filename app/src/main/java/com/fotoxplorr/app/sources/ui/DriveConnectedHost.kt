package com.fotoxplorr.app.sources.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.sources.drives.DriveChoice
import com.fotoxplorr.app.sources.drives.DriveRuntime
import com.fotoxplorr.app.sources.drives.DriveVolume
import com.fotoxplorr.app.sources.drives.DriveVolumeSource
import com.fotoxplorr.app.sources.runtime.AddResult
import com.fotoxplorr.app.sources.runtime.SourceJobs
import com.fotoxplorr.app.sources.runtime.SourcesRuntime
import com.fotoxplorr.app.sources.runtime.TreeRelations
import kotlinx.coroutines.launch

/**
 * The drive lifecycle's one place in the activity. While it is on screen it listens for drives
 * (the volume callback on Android 11 and later, the mount broadcasts before), keeps their sources
 * OFFLINE or ONLINE, and draws the "Drive connected" strip over the top of the content when an
 * unknown drive appears. It draws nothing otherwise, scans nothing, and asks for no permission until
 * the person presses Add, which opens the system folder picker pointed at the drive.
 */
@Composable
fun DriveConnectedHost(runner: JobRunner?) {
    val context = LocalContext.current
    val controller = remember(context) { DriveRuntime.controller(context) }
    val source = remember(context) { DriveVolumeSource(context) }
    val runtime = remember(context) { SourcesRuntime.get(context) }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<DriveVolume?>(null) }

    DisposableEffect(runner) {
        DriveRuntime.setRunner(runner)
        onDispose { DriveRuntime.setRunner(null) }
    }
    DisposableEffect(controller, source) {
        controller.start()
        source.start(controller::dispatch)
        onDispose { source.stop() }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val volume = picking
        picking = null
        val uri: Uri? = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK }
        if (volume == null) return@rememberLauncherForActivityResult
        if (uri == null) {
            controller.pickerClosed(volume.uuid, added = false)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val atRoot = TreeRelations.parse(uri.toString())?.second?.endsWith(":") == true
            val added = runtime.addSource(uri.toString(), volume.label.takeIf { atRoot })
            controller.pickerClosed(volume.uuid, added = added is AddResult.Added)
            if (added is AddResult.Added) {
                val name = runtime.sourceName(added.sourceRow) ?: volume.label
                if (runner != null) SourceJobs.launch(runner, runtime, added.sourceRow, name) else runtime.index(added.sourceRow)
            }
        }
    }
    LaunchedEffect(controller, source) {
        controller.pickerRequests.collect { volume ->
            picking = volume
            runCatching { picker.launch(source.pickerIntent(volume)) }.onFailure {
                picking = null
                controller.pickerClosed(volume.uuid, added = false)
            }
        }
    }

    val offers by controller.offers.collectAsStateWithLifecycle()
    val next = offers.firstOrNull() ?: return
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), contentAlignment = Alignment.TopCenter) {
        DriveOfferCard(
            volume = next,
            actions = DriveOfferActions(
                onAdd = { controller.choose(next.uuid, DriveChoice.ADD) },
                onNotNow = { controller.choose(next.uuid, DriveChoice.NOT_NOW) },
                onNever = { controller.choose(next.uuid, DriveChoice.NEVER) },
            ),
        )
    }
}
