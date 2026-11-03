package com.fotoxplorr.app.basemap

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.app.ui.RoomRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Data > Maps: import a raster MBTiles map file, see its size, delete it. The system
 * document picker needs no permission; the file is copied into app-private storage (a street map
 * can be hundreds of megabytes, so the size is shown). Vector MBTiles and PMTiles are refused with
 * a plain sentence. The map draws these tiles when zoomed in past the bundled world's detail.
 */
@Composable
fun ImportedMapFileRows() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(MapFileStore.sizeBytes(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { MapFileStore.import(context, uri) }
            message = when (result) {
                is MapFileStore.Result.Imported -> MapFileCopy.DONE
                is MapFileStore.Result.Rejected -> result.message
            }
            size = MapFileStore.sizeBytes(context)
            busy = false
        }
    }
    RoomRow(
        leadingIcon = Icons.Outlined.UploadFile,
        label = MapFileCopy.IMPORT,
        caption = message ?: size?.let { "${MapFileCopy.TITLE}: ${MapFileCopy.size(it)}. ${MapFileCopy.SUPPORTED}" }
            ?: "${MapFileCopy.NONE} ${MapFileCopy.SUPPORTED}",
        enabled = !busy,
        onClick = { picker.launch(arrayOf("*/*")) },
    )
    if (size != null) {
        RoomRow(
            leadingIcon = Icons.Outlined.DeleteOutline,
            label = MapFileCopy.DELETE,
            caption = "Frees ${MapFileCopy.size(size ?: 0)}.",
            enabled = !busy,
            onClick = {
                MapFileStore.delete(context)
                size = null
                message = null
            },
        )
    }
}
