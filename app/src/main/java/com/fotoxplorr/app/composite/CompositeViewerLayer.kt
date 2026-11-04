package com.fotoxplorr.app.composite

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.StereoPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tags for tests. */
internal object CompositeTags {
    const val PLAY = "compositePlay"
    const val CLIP = "compositeClip"
    const val EXPORT_STILL = "compositeExportStill"
    const val EXPORT_VIDEO = "compositeExportVideo"
    const val STEREO = "compositeStereo"
}

/**
 * Whether the moving part should be on screen: a tap toggles it, a hold plays while held, a mouse
 * resting on the button plays while it rests. A clip that ends or fails turns the tap off.
 */
internal data class MotionPlayState(val tapped: Boolean = false, val held: Boolean = false, val hovered: Boolean = false) {
    val playing: Boolean get() = tapped || held || hovered
    fun tap() = copy(tapped = !tapped)
    fun ended() = MotionPlayState()
}

/**
 * The viewer's composite media controls for one still, drawn inside the viewer's own box (a hook
 * next to the 360 button in `ViewerScreen`):
 *
 * - **Motion photo or Live Photo**: a 48 dp play button. Tap to play once, hold to play while held,
 *   or rest a mouse on it. The clip plays over the photo WITHOUT SOUND ([SilentClip]); the app has
 *   no audio features. The photo's own long press stays the subject lift, so the hold lives on the
 *   button (the non-gesture path is the tap).
 * - **Motion photo export** (when [mayExport] allows; TRAPS #33): save the still or the clip as a NEW
 *   file ([CompositeExporter]); the original is never written.
 * - **Stereo MPO**: a 48 dp button that opens the 3D view ([onOpenStereo]).
 *
 * The file is probed off the main thread once per photo version; an ordinary photo draws nothing.
 */
@Composable
internal fun BoxScope.CompositeViewerLayer(
    asset: MediaAsset,
    chromeShown: Boolean,
    mayExport: () -> Boolean,
    onOpenStereo: (StereoPair) -> Unit,
    bottomPaddingDp: Int,
) {
    val context = LocalContext.current.applicationContext
    val facts by produceState(CompositeFacts(), asset.id, asset.dateModifiedSeconds) {
        value = withContext(Dispatchers.IO) { CompositeProbe.facts(context, asset) }
    }
    val liveMovie = if (asset.isVideo) null else LivePhotoMovies.movieFor(asset.id)
    val motion = facts.motion
    if (motion == null && liveMovie == null && facts.stereo == null && !facts.spatialOnly) return

    var play by remember(asset.id) { mutableStateOf(MotionPlayState()) }
    var clipUri by remember(asset.id) { mutableStateOf<Uri?>(null) }
    var message by remember(asset.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // The clip file is made only once someone asks to play: paging through motion photos copies nothing.
    LaunchedEffect(play.playing, asset.id) {
        // A copied clip may have been cleared since (the photo was marked sensitive or trashed, or the
        // viewer closed): never hand the player a file that is gone, copy again instead.
        if (!play.playing) return@LaunchedEffect
        val cached = clipUri
        if (cached != null) {
            val gone = cached.scheme == "file" &&
                withContext(Dispatchers.IO) { cached.path?.let { java.io.File(it).isFile } != true }
            if (!gone) return@LaunchedEffect
            clipUri = null
        }
        clipUri = when {
            liveMovie != null -> liveMovie.contentUri
            motion != null -> withContext(Dispatchers.IO) { MotionClipCache.clipFile(context, asset, motion) }?.let(Uri::fromFile)
            else -> null
        }
        if (clipUri == null) {
            play = play.ended()
            message = CompositeCopy.MOTION_FAILED
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            message = null
        }
    }

    val uri = clipUri
    if (play.playing && uri != null) {
        SilentClip(
            uri = uri,
            startMs = 0L,
            onEnded = { play = play.ended() },
            onError = {
                play = play.ended()
                message = CompositeCopy.MOTION_FAILED
            },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = CompositeCopy.STOP_MOTION },
        )
    }

    if (!chromeShown && !play.playing) return
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 12.dp, bottom = bottomPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (facts.spatialOnly) {
            Text(
                CompositeCopy.SPATIAL_ONLY,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (motion != null || liveMovie != null) {
                MotionButton(
                    playing = play.playing,
                    label = if (play.playing) CompositeCopy.STOP_MOTION else if (liveMovie != null) CompositeCopy.PLAY_LIVE else CompositeCopy.PLAY_MOTION,
                    onTap = { play = play.tap() },
                    onHold = { held -> play = play.copy(held = held) },
                    onHover = { hovered -> play = play.copy(hovered = hovered) },
                )
            }
            if (motion != null && chromeShown && mayExport()) {
                RoundAction(CompositeCopy.EXPORT_STILL, CompositeTags.EXPORT_STILL, Icons.Outlined.Photo) {
                    scope.launch {
                        message = CompositeExporter(context).exportStill(asset, motion, mayExport).message(video = false)
                    }
                }
                RoundAction(CompositeCopy.EXPORT_VIDEO, CompositeTags.EXPORT_VIDEO, Icons.Outlined.Movie) {
                    scope.launch {
                        message = CompositeExporter(context).exportVideo(asset, motion, mayExport).message(video = true)
                    }
                }
            }
            val stereo = facts.stereo
            if (stereo != null && chromeShown) {
                RoundAction(CompositeCopy.OPEN_STEREO, CompositeTags.STEREO, Icons.Outlined.ViewInAr) { onOpenStereo(stereo) }
            }
        }
    }
}

private fun CompositeExportResult.message(video: Boolean): String = when (this) {
    is CompositeExportResult.Saved -> if (video) CompositeCopy.EXPORTED_VIDEO else CompositeCopy.EXPORTED_STILL
    CompositeExportResult.Refused -> CompositeCopy.EXPORT_REFUSED
    CompositeExportResult.Failed -> CompositeCopy.EXPORT_FAILED
}

/** A 48 dp round button with a white icon over a dark disc. */
@Composable
private fun RoundAction(description: String, tag: String, icon: ImageVector, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .semantics { contentDescription = description }
            .testTag(tag),
    ) { Icon(icon, contentDescription = null, tint = Color.White) }
}

/**
 * The play button: tap toggles, a hold plays while held, a mouse resting on it plays. It takes
 * keyboard focus like the buttons beside it, and Enter, Space or the D-pad centre toggle it.
 */
@Composable
internal fun MotionButton(
    playing: Boolean,
    label: String,
    onTap: () -> Unit,
    onHold: (Boolean) -> Unit,
    onHover: (Boolean) -> Unit,
) {
    // The pointer blocks start once; they call through these so a swipe to the next photo is not
    // answered with the previous photo's state (TRAPS #50).
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onHold)
    val hover by rememberUpdatedState(onHover)
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(if (playing) Color.White.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.55f), CircleShape)
            .then(if (focused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .onKeyEvent { event ->
                val activates = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                if (activates && event.type == KeyEventType.KeyUp) tap()
                activates
            }
            .focusable(interactionSource = focus)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                stateDescription = if (playing) "Playing" else "Still"
                onClick { tap(); true }
            }
            .testTag(CompositeTags.PLAY)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        hold(true)
                        tryAwaitRelease()
                        hold(false)
                    },
                    // A hold is not a tap: without this a long hold would also toggle on release.
                    onLongPress = { },
                    onTap = { tap() },
                )
            }
            // Mouse only: resting on the button plays; touch has the hold above (TRAPS #144).
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val mouse = event.changes.any { it.type == PointerType.Mouse }
                        when (event.type) {
                            PointerEventType.Enter -> if (mouse) hover(true)
                            PointerEventType.Exit -> if (mouse) hover(false)
                        }
                    }
                }
            },
    ) {
        Icon(Icons.Outlined.MotionPhotosOn, contentDescription = null, tint = if (playing) Color.Black else Color.White)
    }
}

private const val MESSAGE_MS = 3_000L
