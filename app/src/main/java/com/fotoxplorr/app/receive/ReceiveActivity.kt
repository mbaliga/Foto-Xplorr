package com.fotoxplorr.app.receive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fotoxplorr.app.FotoXplorrActivity
import com.fotoxplorr.app.gallery.GalleryPreferences
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.openwith.withSafeExtras
import com.fotoxplorr.app.recognition.TextLayoutProvider
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.crashRecoveryStyle
import com.fotoxplorr.app.viewer.ViewerScreen
import com.fotoxplorr.app.viewer.buildExternalAsset
import dev.aarso.crashrecovery.CrashRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The target of `ACTION_SEND_MULTIPLE` (WP3.1): several photos or videos shared into Fotoz. A
 * single `ACTION_SEND` still goes to [FotoXplorrActivity], whose filter this unit does not touch.
 *
 * It asks one thing, "View" (the default) or "Save to library":
 *  - **View** shows them read-only in the viewer as a swipe run, built straight from the uris like
 *    `ExternalViewerActivity` does and never written to any store; nothing is copied.
 *  - **Save to library** copies each into `Pictures/Fotoz/` (videos `Movies/Fotoz/`) as a new file
 *    through [ReceiveWriter], with progress, a Cancel that leaves no half file, a result that counts
 *    what was saved and what failed, and "Save the rest" that continues without redoing finished ones.
 *
 * The sender's files are ordinary incoming media: after saving they are ordinary library items and
 * nothing here writes to any store but MediaStore. A `content://media` uri (a row of this device's
 * own library) is held to the library's lock rules by [ReceiveGuard] before it is shown or copied.
 * Every read of a shared file runs off the main thread, in [ReceiveViewModel].
 */
class ReceiveActivity : ComponentActivity() {
    private val model: ReceiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "Fotoz", style = crashRecoveryStyle(this))) return
        enableEdgeToEdge()
        // Exported and reachable by any app: keep only the extras this app reads.
        intent?.withSafeExtras()
        val received = receivedUris(intent)
        if (intent?.action != Intent.ACTION_SEND_MULTIPLE || received.isEmpty()) {
            finish()
            return
        }
        model.prepare(received, intent?.type)

        setContent {
            val galleryPreferences = remember { GalleryPreferences(applicationContext) }
            val preferences by galleryPreferences.observe().collectAsStateWithLifecycle()
            val stage by model.stage.collectAsStateWithLifecycle()
            val saving by model.saving.collectAsStateWithLifecycle()
            val cancelled by model.cancelled.collectAsStateWithLifecycle()
            FotoXplorrTheme(preferences) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val s = stage) {
                        ReceiveStage.Preparing -> Sheet { Text(ReceiveCopy.PREPARING, Modifier.padding(vertical = 16.dp)) }
                        is ReceiveStage.NothingUsable -> Sheet {
                            Text(ReceiveCopy.NOTHING_USABLE, Modifier.padding(vertical = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                            ReceiveButton(ReceiveCopy.DONE, primary = true, onClick = { finish() })
                        }
                        is ReceiveStage.Choice -> ChoiceSheet(s.plan, onView = model::view, onSave = model::save)
                        is ReceiveStage.Saving -> SavingSheet(
                            session = s.session,
                            saving = saving,
                            cancelled = cancelled,
                            onCancel = model::cancel,
                            onResume = model::save,
                            onDone = { finish() },
                            onOpenLibrary = {
                                startActivity(Intent(this@ReceiveActivity, FotoXplorrActivity::class.java))
                                finish()
                            },
                        )
                        is ReceiveStage.Viewing -> ReceiveViewer(
                            plan = s.plan,
                            preferences = preferences,
                            galleryPreferences = galleryPreferences,
                            onClose = { finish() },
                        )
                    }
                }
            }
        }
    }

    /** The uris of a SEND_MULTIPLE, from the stream extra, else from the clip. A hostile extra reads as none. */
    private fun receivedUris(source: Intent?): List<ReceivedUri> {
        if (source == null) return emptyList()
        val fromStream = runCatching {
            IntentCompat.getParcelableArrayListExtra(source, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }.getOrDefault(emptyList())
        val uris = fromStream.ifEmpty {
            val clip = source.clipData
            if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        }
        return uris.map { ReceivedUri(it.toString()) }
    }
}

@Composable
private fun Sheet(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp)) { content() }
        }
    }
}

@Composable
private fun ReceiveButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) Button(onClick, modifier) { Text(label) } else OutlinedButton(onClick, modifier) { Text(label) }
}

@Composable
private fun ChoiceSheet(plan: ReceivePlan, onView: () -> Unit, onSave: () -> Unit) {
    Sheet {
        Text(
            ReceiveCopy.title(plan),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        ReceiveCopy.leftOut(plan)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        Text(ReceiveCopy.CHOICE_PROMPT, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // View is the default: first, filled, and the one that keeps nothing.
            ReceiveButton(ReceiveCopy.VIEW, primary = true, onClick = onView)
            Text(ReceiveCopy.VIEW_HINT, style = MaterialTheme.typography.bodySmall)
            ReceiveButton(ReceiveCopy.SAVE, primary = false, onClick = onSave)
            Text(ReceiveCopy.SAVE_HINT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SavingSheet(
    session: ReceiveSession,
    saving: Boolean,
    cancelled: Boolean,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val progress by session.progress.collectAsStateWithLifecycle()
    Sheet {
        if (saving) {
            val done = progress.saved + progress.failed
            val label = ReceiveCopy.saving(done, progress.total)
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics {
                    contentDescription = ReceiveCopy.SAVING_A11Y
                    progressBarRangeInfo = ProgressBarRangeInfo(done.toFloat(), 0f..progress.total.toFloat())
                },
            )
            ReceiveButton(ReceiveCopy.CANCEL, primary = false, onClick = onCancel)
        } else {
            Text(
                ReceiveCopy.summary(progress, cancelled),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.resumable > 0) ReceiveButton(ReceiveCopy.SAVE_REST, primary = true, onClick = onResume)
                if (progress.saved > 0) ReceiveButton(ReceiveCopy.OPEN_LIBRARY, primary = progress.resumable == 0, onClick = onOpenLibrary)
                ReceiveButton(ReceiveCopy.DONE, primary = false, onClick = onDone)
            }
        }
    }
}

/** The shared items as a read-only swipe run; each is described off the main thread when it is reached. */
@Composable
private fun ReceiveViewer(
    plan: ReceivePlan,
    preferences: GalleryPreferencesState,
    galleryPreferences: GalleryPreferences,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    var asset by remember { mutableStateOf<MediaAsset?>(null) }
    val textLayoutProvider = remember { TextLayoutProvider(context.applicationContext) }
    DisposableEffect(textLayoutProvider) { onDispose { textLayoutProvider.release() } }
    LaunchedEffect(index) {
        val item = plan.items[index]
        asset = null
        asset = runCatching {
            withContext(Dispatchers.IO) { buildExternalAsset(context, Uri.parse(item.uri), item.mimeType) }
        }.getOrElse {
            Toast.makeText(context, ReceiveCopy.VIEW_FAILED, Toast.LENGTH_SHORT).show()
            if (plan.items.size == 1) onClose()
            null
        }
    }
    asset?.let { current ->
        ViewerScreen(
            asset = current,
            position = index + 1,
            total = plan.items.size,
            isFavorite = false,
            isSensitive = false,
            hasPrevious = index > 0,
            hasNext = index < plan.items.size - 1,
            canMoveToTrash = false,
            slideshowActive = false,
            slideshowIntervalSeconds = 5,
            onToggleSlideshow = {},
            onToggleFavorite = {},
            onToggleSensitive = {},
            onShare = {},
            onEdit = {},
            onOpenWith = {},
            onMoveToTrash = {},
            onPrevious = { if (index > 0) index-- },
            onNext = { if (index < plan.items.size - 1) index++ },
            onClose = onClose,
            catalogueActions = false,
            autoplayVideos = preferences.autoplayVideos,
            transparencyBackground = preferences.viewerTransparencyBackground,
            onSetTransparencyBackground = galleryPreferences::setViewerTransparencyBackground,
            textLayoutProvider = textLayoutProvider,
        )
    }
}
