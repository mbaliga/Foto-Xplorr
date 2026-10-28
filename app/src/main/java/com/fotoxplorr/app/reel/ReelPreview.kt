package com.fotoxplorr.app.reel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.ui.RoomStyle
import kotlin.coroutines.coroutineContext

/**
 * The preview's clock: where in the reel it is, in microseconds on the plan's clock, and whether it runs.
 * Held by the editor so the buttons beside the picture can start, stop and jump it.
 */
@Stable
internal class PreviewClock(startPlaying: Boolean) {
    var timeUs by mutableLongStateOf(0L)
    var playing by mutableStateOf(startPlaying)
}

private val MAX_PREVIEW_HEIGHT = 220.dp

/**
 * A looping slideshow of the reel's VISUALS, drawn from the same plan the export is made from
 * ([ReelPreviewMath], [ReelLayers]), so a cross-fade starts when the video's will and a slide moves the way
 * the video's will. It is not the video: a clip shows one frame (the library's picture of it), there is no sound,
 * and nothing is encoded. Pictures come only from [byId], which the editor builds from the browsable list
 * (TRAPS #33), so a protected photo is a grey "not in the library" box here as it would stop the export.
 *
 * The clock runs only while the screen is RESUMED and [PreviewClock.playing] is true; it is the editor's job to start
 * it paused under the system's remove-animations setting. Each frame writes one number; what depends on it
 * (opacity, push, the move) is read inside draw-phase lambdas, so the pictures do not recompose per frame, only
 * when one comes on or goes off. How the move looks has not been seen on a device.
 */
@Composable
internal fun ReelPreview(plan: ReelRenderPlan, byId: Map<Long, MediaAsset>, clock: PreviewClock, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(plan, clock.playing, lifecycle) {
        if (!clock.playing || plan.totalUs <= 0L) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                nextFrameNanos { now ->
                    if (last != 0L) clock.timeUs = ReelPreviewMath.loop(plan, clock.timeUs + (now - last) / 1000L)
                    last = now
                }
            }
        }
    }
    // The clock may sit past the end of a shorter plan after an edit.
    LaunchedEffect(plan) { if (clock.timeUs >= plan.totalUs) clock.timeUs = 0L }

    BoxWithConstraints(
        modifier
            .heightIn(max = MAX_PREVIEW_HEIGHT)
            .aspectRatio(plan.frame.ratio)
            .background(Color.Black)
            .clipToBounds()
            .semantics { contentDescription = ReelCopy.PREVIEW },
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Which pictures are on screen changes at a boundary, not every frame, so only then does this recompose.
        val visible by remember(plan) { derivedStateOf { ReelPreviewMath.piecesAt(plan, clock.timeUs).map { it.item.index } } }
        for (index in visible) {
            key(index) { PieceView(plan, index, clock, byId, widthPx, heightPx) }
        }
    }
}

/** One item of the plan on screen. Its opacity and push are read from the clock when drawn. */
@Composable
private fun PieceView(plan: ReelRenderPlan, index: Int, clock: PreviewClock, byId: Map<Long, MediaAsset>, widthPx: Float, heightPx: Float) {
    val item = plan.items.firstOrNull { it.index == index } ?: return
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val piece = ReelPreviewMath.piecesAt(plan, clock.timeUs).firstOrNull { it.item.index == index }
            alpha = piece?.alpha ?: 0f
            // A push of 2 is one frame width (normalised device coordinates run from -1 to 1).
            translationX = (piece?.shiftX ?: 0f) / ReelLayers.FRAME_WIDTH_NDC * size.width
        },
    ) {
        when (item.kind) {
            PlannedKind.CARD -> CardView(item.text, item.colorArgb, heightPx)
            PlannedKind.CLIP -> byId[item.mediaId]?.let {
                MediaImage(it, Modifier.fillMaxSize(), if (plan.fit == ReelFit.CONTAIN) ContentScale.Fit else ContentScale.Crop)
            } ?: Missing()
            PlannedKind.STILL -> byId[item.mediaId]?.let { StillView(plan, item, it, clock, widthPx, heightPx) } ?: Missing()
        }
    }
}

/**
 * A photo with its slow move. The picture is laid out at the size at which the whole of it just covers the frame,
 * then scaled and shifted by the move's window each frame ([ReelGeometry.windowAt], the maths the export uses).
 * The picture's shape is the library's listing until the decoder says what it really is.
 */
@Composable
private fun StillView(plan: ReelRenderPlan, item: PlannedItem, asset: MediaAsset, clock: PreviewClock, widthPx: Float, heightPx: Float) {
    var shape by remember(asset.id) { mutableFloatStateOf(asset.aspectRatio) }
    val onSize: (Size) -> Unit = { size ->
        if (size.width > 0f && size.height > 0f) shape = size.width / size.height
    }
    if (plan.fit == ReelFit.CONTAIN) {
        MediaImage(asset, Modifier.fillMaxSize(), ContentScale.Fit, onImageSize = onSize)
        return
    }
    val frameAspect = if (heightPx > 0f) widthPx / heightPx else plan.frame.ratio
    val a = shape
    val baseW = if (a > frameAspect) heightPx * a else widthPx
    val baseH = if (a > frameAspect) heightPx else widthPx / a
    val (coverW, _) = ReelGeometry.coverSize(a, frameAspect)
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MediaImage(
            asset,
            Modifier
                .requiredSize(with(density) { baseW.toDp() }, with(density) { baseH.toDp() })
                .graphicsLayer {
                    val piece = ReelPreviewMath.piecesAt(plan, clock.timeUs).firstOrNull { it.item.index == item.index }
                    val progress = piece?.progress ?: 0f
                    val window = ReelGeometry.windowAt(item.motion, ReelGeometry.ease(progress), (a * 1000f).toInt().coerceAtLeast(1), 1000, frameAspect)
                    val scale = if (window.width > 0f) coverW / window.width else 1f
                    scaleX = scale
                    scaleY = scale
                    translationX = -(window.centreX - 0.5f) * baseW * scale
                    translationY = -(window.centreY - 0.5f) * baseH * scale
                },
            ContentScale.FillBounds,
            onImageSize = onSize,
        )
    }
}

/** A title card: its words, centred, on its colour, readable on it. */
@Composable
private fun CardView(text: String, argb: Int, heightPx: Float) {
    val density = LocalDensity.current
    val first = with(density) { (heightPx * 0.075f * 1.4f).toSp() }
    val lines = text.split('\n')
    Box(Modifier.fillMaxSize().background(Color(argb or OPAQUE)), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val ink = Color(ReelTitleCard.readableOn(argb))
            lines.forEachIndexed { i, line ->
                Text(
                    line,
                    color = ink,
                    textAlign = TextAlign.Center,
                    fontSize = if (i == 0) first else first * 0.62f,
                    lineHeight = if (i == 0) first * 1.15f else first * 0.75f,
                    maxLines = 3,
                )
            }
        }
    }
}

private const val OPAQUE = 0xFF000000.toInt()

@Composable
private fun Missing() {
    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2A)), contentAlignment = Alignment.Center) {
        Text(ReelCopy.NOT_IN_LIBRARY, color = RoomStyle.InkMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/** The next frame's time, through the context's [InfiniteAnimationPolicy] if it has one: the same as Compose's own infinite animations. */
private suspend fun <R> nextFrameNanos(onFrame: (Long) -> R): R {
    val policy = coroutineContext[InfiniteAnimationPolicy]
    return if (policy == null) withFrameNanos(onFrame) else policy.onInfiniteOperation { withFrameNanos(onFrame) }
}
