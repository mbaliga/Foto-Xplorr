package com.fotoxplorr.app.gallery

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.metadata.DefaultSidecarService
import com.fotoxplorr.app.metadata.FileSidecarDirectory
import com.fotoxplorr.app.metadata.SidecarAsset
import com.fotoxplorr.app.metadata.SidecarParent
import com.fotoxplorr.core.formats.MediaFormat
import com.fotoxplorr.core.metadata.SidecarAnnotations
import com.fotoxplorr.core.metadata.SidecarFlag
import com.fotoxplorr.core.metadata.SidecarPolicy
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The Compose views of the look-without-opening family (hover, Quick Look, the preview pane) and
 * of compare/survey and culling. Every one is handed its photos by GalleryScreen from the list the
 * grid draws -- the browsable list (TRAPS #33) -- and blurs a sensitive photo exactly as the grid
 * does. None of them has run on a device.
 */

/** A photo drawn whole, blurred when the grid would blur it. */
@Composable
internal fun LookImage(asset: MediaAsset, blurred: Boolean, modifier: Modifier = Modifier) {
    MediaImage(
        asset = asset,
        modifier = modifier.then(if (blurred) Modifier.blur(22.dp) else Modifier),
        contentScale = ContentScale.Fit,
    )
}

/**
 * The hover preview: the photo large in a corner window. It has no pointer input of its own, so
 * the mouse keeps hovering the tile underneath and moving off it takes the preview away.
 */
@Composable
internal fun HoverPreviewOverlay(asset: MediaAsset, blurred: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(16.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth(0.45f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(8.dp),
    ) {
        LookImage(asset, blurred, Modifier.fillMaxWidth().aspectRatioOf(asset))
        Text(
            asset.displayName,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun Modifier.aspectRatioOf(asset: MediaAsset): Modifier =
    aspectRatio(JustifiedLayout.normalizedAspect(asset.aspectRatio))

/** Swallows every pointer event so nothing behind a full-screen view can be tapped through it. */
private fun Modifier.swallowPointer(): Modifier =
    pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

@Composable
private fun LookButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) { content() }
}

/**
 * Space-bar Quick Look over the grid. Keys come through GalleryScreen's key handler (the grid
 * keeps focus); these buttons are the path without a keyboard.
 */
@Composable
internal fun QuickLookOverlay(
    asset: MediaAsset,
    position: Int,
    count: Int,
    blurred: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .zIndex(20f)
            .swallowPointer()
            .background(Color.Black.copy(alpha = 0.94f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            LookImage(asset, blurred, Modifier.fillMaxSize())
        }
        Text(
            "${asset.displayName}  ·  ${position + 1} of $count",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LookButton(QuickPreviewCopy.PREVIOUS, onPrevious) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = Color.White)
            }
            LookButton(QuickPreviewCopy.OPEN, onOpen) { Icon(Icons.Outlined.OpenInNew, null, tint = Color.White) }
            LookButton(QuickPreviewCopy.QUICK_LOOK_CLOSE, onClose) { Icon(Icons.Outlined.Close, null, tint = Color.White) }
            LookButton(QuickPreviewCopy.NEXT, onNext) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color.White)
            }
        }
    }
}

/** The always-open preview pane beside the grid on a wide window: the photo, its facts, and Open. */
@Composable
internal fun PreviewPane(asset: MediaAsset?, blurred: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .background(Color(0xFF101014))
            .padding(16.dp),
    ) {
        if (asset == null) {
            Text(QuickPreviewCopy.PANE_EMPTY, color = Color.White.copy(alpha = 0.6f))
            return@Column
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            LookImage(asset, blurred, Modifier.fillMaxSize())
        }
        Text(asset.displayName, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        Text(
            previewFacts(asset),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(onClick = onOpen, modifier = Modifier.padding(top = 8.dp)) { Text(QuickPreviewCopy.OPEN) }
    }
}

/** "4032 × 3024 · 3.1 MB" -- what the pane says under the name. */
internal fun previewFacts(asset: MediaAsset): String {
    val dims = if (asset.width > 0 && asset.height > 0) "${asset.width} × ${asset.height}" else null
    val mb = if (asset.sizeBytes > 0) String.format(java.util.Locale.ROOT, "%.1f MB", asset.sizeBytes / 1_048_576.0) else null
    return listOfNotNull(dims, mb).joinToString("  ·  ")
}

/** Compare / survey over the chosen photos, full screen; Escape or the close button leaves. */
@Composable
internal fun SurveyScreen(
    assets: List<MediaAsset>,
    blurredIds: Set<MediaId>,
    onClose: () -> Unit,
) {
    val byId = remember(assets) { assets.associateBy { it.id.value } }
    var set by remember(assets) { mutableStateOf(surveyFromSelection(assets.map { it.id.value })) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(22f)
            .swallowPointer()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onClose(); true } else false
            }
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SurveyView(
            set = set,
            onDrop = { id -> set = set.remove(id) },
            tile = { id, mod -> byId[id]?.let { LookImage(it, it.id in blurredIds, mod) } },
        )
        LookButton(SurveyCopy.CLOSE, onClose) { Icon(Icons.Outlined.Close, null, tint = Color.White) }
    }
}

/** A durable side that keeps nothing: culling with sidecars off is memory only, and says so. */
private object NoDurableStore : AnnotationStore {
    override fun get(id: Long): SidecarAnnotations? = null
    override fun put(id: Long, annotations: SidecarAnnotations): Boolean = false
}

/**
 * The store a cull writes to: memory always, plus the XMP sidecar beside the photo only when the
 * user turned that on in Settings. A sidecar is placed in the photo's own folder on the primary
 * volume; if the app may not write there (scoped storage, no grant) the write fails honestly and
 * the value stays in memory.
 */
internal fun cullingStore(assets: List<MediaAsset>, sidecars: Boolean): WriteThroughAnnotationStore {
    if (!sidecars) return WriteThroughAnnotationStore(NoDurableStore)
    val byId = assets.associateBy { it.id.value }
    val service = DefaultSidecarService(policy = { SidecarPolicy(writeEnabled = true) })
    return WriteThroughAnnotationStore(
        SidecarAnnotationStore(service) { id ->
            val asset = byId[id] ?: return@SidecarAnnotationStore null
            val parent = asset.relativePath?.let { rel ->
                runCatching { SidecarParent.Directory(FileSidecarDirectory(File(Environment.getExternalStorageDirectory(), rel))) }
                    .getOrNull()
            } ?: SidecarParent.NoGrant
            SidecarAsset(
                fileName = asset.displayName,
                source = SidecarPolicy.Source(isRaw = asset.format is MediaFormat.Raw, embedWritable = false),
                parent = parent,
            )
        },
    )
}

/** "★★★☆☆" -- stars as text so the rating is read, not only coloured. */
internal fun starsText(rating: Int?): String {
    val r = (rating ?: 0).coerceIn(0, 5)
    return "★".repeat(r) + "☆".repeat(5 - r)
}

/** One line naming the flag, rating and label of the photo, for the culling HUD. */
internal fun cullStatusText(a: SidecarAnnotations?): String {
    val flag = when (a?.flag) {
        SidecarFlag.PICK -> "Picked"
        SidecarFlag.REJECT -> "Rejected"
        else -> "Unflagged"
    }
    val label = a?.label?.takeIf { it.isNotEmpty() }
    return listOfNotNull(flag, starsText(a?.rating), label).joinToString("  ·  ")
}

/**
 * The culling view: one photo at a time, single keys (P X U, 0-5, 6-9, [ ] and arrows, Z undo,
 * Escape leaves) and the same actions as 48 dp buttons for touch. Store writes run off the main
 * thread, one at a time, so a fast run of keys lands in order.
 */
@Composable
internal fun CullingScreen(
    assets: List<MediaAsset>,
    store: WriteThroughAnnotationStore,
    sidecars: Boolean,
    blurredIds: Set<MediaId>,
    onClose: () -> Unit,
) {
    val byId = remember(assets) { assets.associateBy { it.id.value } }
    var cull by remember(assets) { mutableStateOf(CullState(assets.map { it.id.value })) }
    var current by remember { mutableStateOf<SidecarAnnotations?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var lastSaved by remember { mutableStateOf(true) }
    var summary by remember { mutableStateOf(CullSummary(0, 0, assets.size)) }
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(cull.currentId, version) {
        val id = cull.currentId ?: return@LaunchedEffect
        current = withContext(Dispatchers.IO) { store.get(id) }
        summary = withContext(Dispatchers.IO) { cullSummary(cull.ids, store) }
    }

    fun act(action: CullAction) {
        scope.launch {
            mutex.withLock {
                val before = cull
                val next = withContext(Dispatchers.IO) { before.apply(action, store) }
                cull = next
                if (action !is CullAction.Next && action !is CullAction.Previous) lastSaved = store.lastPutDurable
                version++
            }
        }
    }

    val asset = cull.currentId?.let { byId[it] }
    Column(
        Modifier
            .fillMaxSize()
            .zIndex(22f)
            .swallowPointer()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val action = when (e.key) {
                    Key.Escape -> { onClose(); return@onKeyEvent true }
                    Key.DirectionRight -> CullAction.Next
                    Key.DirectionLeft -> CullAction.Previous
                    else -> e.utf16CodePoint.takeIf { it > 0 }?.let { cullActionFor(it.toChar()) }
                } ?: return@onKeyEvent false
                act(action)
                true
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LookButton(CullingCopy.CLOSE, onClose) { Icon(Icons.Outlined.Close, null, tint = Color.White) }
            Text(
                "${(cull.index + 1).coerceAtMost(cull.ids.size)} of ${cull.ids.size}  ·  " +
                    "${summary.picks} picked, ${summary.rejects} rejected",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            when {
                !sidecars -> CullingCopy.MEMORY_ONLY
                !lastSaved -> CullingCopy.NOT_SAVED
                else -> CullingCopy.HINT
            },
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (asset != null) LookImage(asset, asset.id in blurredIds, Modifier.fillMaxSize())
        }
        Text(cullStatusText(current), color = Color.White, style = MaterialTheme.typography.titleSmall)
        val flag = current?.flag
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            LookButton(CullingCopy.PREVIOUS, { act(CullAction.Previous) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, tint = Color.White)
            }
            CullChip("P", CullingCopy.PICK, flag == SidecarFlag.PICK) { act(CullAction.Flag(SidecarFlag.PICK)) }
            CullChip("X", CullingCopy.REJECT, flag == SidecarFlag.REJECT) { act(CullAction.Flag(SidecarFlag.REJECT)) }
            CullChip("U", CullingCopy.UNFLAG, flag == null || flag == SidecarFlag.NONE) { act(CullAction.Flag(SidecarFlag.NONE)) }
            CullChip("Z", CullingCopy.UNDO, false) { act(CullAction.Undo) }
            LookButton(CullingCopy.NEXT, { act(CullAction.Next) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Color.White)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (stars in 0..5) {
                CullChip("$stars", "${CullingCopy.RATE} $stars", (current?.rating ?: 0) == stars) { act(CullAction.Rate(stars)) }
            }
            CULL_LABELS.forEachIndexed { i, name ->
                CullChip(name.take(1), "${CullingCopy.LABEL} $name, key ${i + 6}", current?.label == name) {
                    act(CullAction.Label(name))
                }
            }
            CullChip(
                if (cull.autoAdvance) "Auto" else "Stay",
                CullingCopy.AUTO_ADVANCE,
                cull.autoAdvance,
            ) { cull = cull.copy(autoAdvance = !cull.autoAdvance) }
        }
    }
}

/** A 48 dp key-cap: its letter, its spoken name, and a frame plus bold when on (not colour only). */
@Composable
private fun CullChip(text: String, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(2.dp)
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Text(
                text,
                color = Color.White,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (on) TextDecoration.Underline else null,
            )
        }
    }
}
