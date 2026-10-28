package com.fotoxplorr.app.vision

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.fotoxplorr.app.experimental.ShellEdges
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.lift.canLiftSubject
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.shell.AppStateViewModel
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which version of which file a computed value belongs to. The file's modified time changes when its bytes do. */
internal data class PhotoKey(val id: MediaId, val modifiedSeconds: Long) {
    constructor(asset: MediaAsset) : this(asset.id, asset.dateModifiedSeconds)
}

/** A picture computed for [key]; the bitmap is never recycled while it may still be on screen (the GC owns it). */
internal class FilteredPicture(val key: FilterKey, val bitmap: Bitmap)

/** The colour that was tapped and what it is called. */
internal class NamedColour(val argb: Int, val label: String, val tapX: Float, val tapY: Float)

/** The pattern layer's state for the shown photo. */
internal sealed interface HatchState {
    /** Not asked for, or the photo is not read yet. */
    data object Idle : HatchState

    /** The layer is being made. */
    data object Working : HatchState

    /** No two colours here are alike for this type, so there is nothing to mark. */
    data object NoneAlike : HatchState

    class Ready(val bitmap: Bitmap) : HatchState
}

/** How much the viewer can do for the shown file. */
internal enum class VisionSupport {
    /** A still raster photo: filter, names, patterns and a saved copy. */
    FULL,

    /** A file the picture cannot be read from (animated, vector, RAW): only the live colour view. */
    VIEW_ONLY,

    /** A video: nothing (the player has no colour filter here). */
    NONE,
}

/** The support for [asset]: what [canLiftSubject] says about reading a still picture, which is the same question. */
internal fun visionSupport(asset: MediaAsset, animated: Boolean): VisionSupport = when {
    asset.isVideo -> VisionSupport.NONE
    canLiftSubject(asset, animated) -> VisionSupport.FULL
    else -> VisionSupport.VIEW_ONLY
}

/**
 * Everything the viewer holds for the colour-vision tools: the person's [choice] (kept across the
 * filmstrip swipe because the holder is not keyed on the photo, and across a rotation by its saver),
 * and, for the photo on screen, what was computed from it. All of that is dropped when the photo
 * changes and none of it is written anywhere: the file, the library and the preferences are not
 * touched.
 *
 * Memory: while anything needs pixels there is one decoded picture of at most [ColourVisionRenderer.VIEW_LIMITS]
 * (about 12 MB), and with a filter on a second of the same size; both are released when the filter,
 * names and patterns are all off or the photo changes.
 */
@Stable
internal class ColourVisionViewer(initial: ColourVisionChoice) {
    var choice by mutableStateOf(initial)
        private set

    /** The decoded photo, for names, patterns and the exact filter. Null until read, and while nothing needs it. */
    var original: Bitmap? by mutableStateOf(null)
        private set

    /**
     * The photo [original], [filtered] and [hatch] were made from. They are cleared a frame after the
     * viewer moves to another photo, so every reader asks [holds] first and ignores them until then.
     */
    var originalKey: PhotoKey? by mutableStateOf(null)
        private set

    fun holds(key: PhotoKey): Boolean = originalKey == key

    var filtered: FilteredPicture? by mutableStateOf(null)
        private set

    var hatch: HatchState by mutableStateOf(HatchState.Idle)
        private set

    var name: NamedColour? by mutableStateOf(null)
        private set

    /** The last sentence about a saved copy, shown for a few seconds. */
    var notice: String? by mutableStateOf(null)
        private set

    /** The app's job list, once a library photo has a filter on (see [rememberColourVisionViewer]). */
    var runner: JobRunner? by mutableStateOf(null)

    /** The index of the shown photo, kept so a change of strength re-plans without quantising again. */
    internal var index: ColourIndexImage? = null

    fun update(change: (ColourVisionChoice) -> ColourVisionChoice) {
        val next = change(choice)
        if (next == choice) return
        choice = next
        if (!next.naming) name = null
    }

    fun putOriginal(key: PhotoKey, value: Bitmap?) {
        original = value
        originalKey = if (value == null) null else key
        index = null
    }

    fun putFiltered(value: FilteredPicture?) {
        filtered = value
    }

    fun putHatch(value: HatchState) {
        hatch = value
    }

    fun putName(value: NamedColour?) {
        name = value
    }

    fun putNotice(value: String?) {
        notice = value
    }

    /** Forgets everything computed from the previous photo. */
    fun resetPhoto() {
        original = null
        originalKey = null
        filtered = null
        hatch = HatchState.Idle
        name = null
        index = null
    }

    /**
     * A tap on the photo box at [tap], in that box's own coordinates, with the zoom layer's current
     * scale, pan and turn. Names the colour under it and returns true; returns false (so the viewer
     * treats it as an ordinary tap) when naming is off, the photo is not read yet, or the tap is
     * outside the picture. Reads the DECODED bitmap, the pixels of the picture itself and not of the
     * filtered view, so the name is of the colour in the file.
     */
    fun nameAt(
        key: PhotoKey,
        tap: Offset,
        container: IntSize,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        rotation: Float,
    ): Boolean {
        if (!choice.naming || !holds(key)) return false
        val bitmap = original ?: return false
        val point = PhotoPoint.toPixel(
            tap.x, tap.y, container.width.toFloat(), container.height.toFloat(),
            bitmap.width, bitmap.height, scale, offsetX, offsetY, rotation,
        ) ?: return false
        val radius = NAME_RADIUS
        val x0 = maxOf(0, point.x - radius)
        val y0 = maxOf(0, point.y - radius)
        val pw = minOf(bitmap.width, point.x + radius + 1) - x0
        val ph = minOf(bitmap.height, point.y + radius + 1) - y0
        if (pw <= 0 || ph <= 0) return false
        val patch = IntArray(pw * ph)
        bitmap.getPixels(patch, 0, pw, x0, y0, pw, ph)
        val argb = PhotoPoint.sample(patch, pw, ph, point.x - x0, point.y - y0, radius)
        name = NamedColour(argb, ColourNamer.label(argb), tap.x, tap.y)
        return true
    }

    companion object {
        /** Pixels either side of the tapped one that are averaged. */
        const val NAME_RADIUS = 2

        /** How long the colour name stays on the photo. */
        const val NAME_MILLIS = 4_000L

        /** How long a sentence about a saved copy stays. */
        const val NOTICE_MILLIS = 5_000L

        /** A wait before the exact filter is computed, so the stepper held down does not start one per step. */
        const val DEBOUNCE_MILLIS = 120L

        val Saver: Saver<ColourVisionViewer, String> = Saver(
            save = { it.choice.encode() },
            restore = { ColourVisionViewer(ColourVisionChoice.decode(it)) },
        )
    }
}

/**
 * The viewer's colour-vision holder for [asset], with the work that fills it. Call it once at the top
 * of the viewer; it is NOT keyed on the photo, so the choice survives a swipe to the next one.
 *
 * What it does, all off the main thread and cancelled when the photo or the choice changes:
 * - reads the photo ([decodeUpright], TRAPS #34) when a filter, names or patterns need its pixels;
 * - computes the exact filtered picture a moment after the choice settles ([ColourVisionRenderer.filter]);
 * - makes the pattern layer ([ColourVisionRenderer.index], [ColourVisionRenderer.hatch]);
 * - clears the colour name and the saved-copy notice after a few seconds.
 *
 * Process death: the choice is saved with the screen's state (rotation) and nothing else is. Nothing
 * here has run on a device.
 */
@Composable
internal fun rememberColourVisionViewer(asset: MediaAsset, support: VisionSupport, canSave: Boolean): ColourVisionViewer {
    val viewer = rememberSaveable(saver = ColourVisionViewer.Saver) { ColourVisionViewer(ColourVisionChoice()) }
    val context = LocalContext.current
    val choice = viewer.choice
    val reads = support == VisionSupport.FULL && choice.needsPixels

    // The job list, looked up only for a library photo once a filter is on: the viewer opened from
    // another app has no use for it, and nobody who never turns this on pays for the look-up.
    val owner = LocalViewModelStoreOwner.current
    val wantsRunner = canSave && choice.filtering
    val runner = remember(owner, wantsRunner) {
        if (!wantsRunner) null else runCatching { owner?.let { ViewModelProvider(it)[AppStateViewModel::class.java].jobRunner } }.getOrNull()
    }
    SideEffect { viewer.runner = runner }

    // Another photo, or nothing needs its pixels any more: drop what was computed, then read it again if needed.
    LaunchedEffect(asset.id, asset.dateModifiedSeconds, reads) {
        viewer.resetPhoto()
        if (!reads) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            orNull { decodeUpright(context.applicationContext, asset.contentUri, ColourVisionRenderer.VIEW_LIMITS) }
        }
        viewer.putOriginal(PhotoKey(asset), decoded?.bitmap)
    }

    val filterKey = choice.filterKey
    LaunchedEffect(viewer.original, filterKey) {
        val source = viewer.original
        if (filterKey == null || source == null) {
            viewer.putFiltered(null)
            return@LaunchedEffect
        }
        if (viewer.filtered?.key == filterKey) return@LaunchedEffect
        delay(ColourVisionViewer.DEBOUNCE_MILLIS)
        val out = withContext(Dispatchers.Default) { orNull { ColourVisionRenderer.filter(source, filterKey) } }
        if (out != null) viewer.putFiltered(FilteredPicture(filterKey, out))
    }

    LaunchedEffect(viewer.original, choice.hatching, choice.deficiency, choice.severityStep) {
        val source = viewer.original
        val deficiency = choice.deficiency
        if (!choice.hatching || source == null || deficiency == null) {
            viewer.putHatch(HatchState.Idle)
            return@LaunchedEffect
        }
        viewer.putHatch(HatchState.Working)
        val state = withContext(Dispatchers.Default) {
            orNull {
                val image = viewer.index ?: ColourVisionRenderer.index(source).also { viewer.index = it }
                val layer = ColourVisionRenderer.hatch(image, FilterKey(deficiency, choice.kind, choice.severityStep))
                if (layer.width == 0) {
                    HatchState.NoneAlike
                } else {
                    HatchState.Ready(Bitmap.createBitmap(layer.argb, layer.width, layer.height, Bitmap.Config.ARGB_8888))
                }
            }
        }
        viewer.putHatch(state ?: HatchState.Idle)
    }

    // How a corrected copy ended, for as long as this screen is up, whichever room started it.
    LaunchedEffect(runner) {
        runner?.outcomes?.collect { outcome ->
            if (outcome.title == VisionCopy.JOB_TITLE) viewer.putNotice(copyNotice(outcome.result))
        }
    }
    LaunchedEffect(viewer.name) {
        if (viewer.name != null) {
            delay(ColourVisionViewer.NAME_MILLIS)
            viewer.putName(null)
        }
    }
    LaunchedEffect(viewer.notice) {
        if (viewer.notice != null) {
            delay(ColourVisionViewer.NOTICE_MILLIS)
            viewer.putNotice(null)
        }
    }
    return viewer
}

/**
 * Starts the corrected-copy job for the photo on screen. Does nothing when there is no filter, no job
 * list, or a copy is already being made. How it ends is told by the viewer's own collector of the
 * job list's outcomes (see [rememberColourVisionViewer]), not by the room that started it: the room
 * may be closed again by then.
 */
internal fun startCorrectedCopy(
    viewer: ColourVisionViewer,
    asset: MediaAsset,
    context: Context,
    mayCopy: (MediaAsset) -> Boolean,
) {
    val runner = viewer.runner ?: return
    val key = viewer.choice.filterKey ?: return
    if (runner.jobs.value.any { it.title == VisionCopy.JOB_TITLE }) return
    CorrectedCopyJob.launch(
        runner,
        CorrectedCopyRequest(asset, key),
        ViewerCopyHost(context, mayCopy),
        EditorCopySink(context),
    )
}

/** The sentence for how a corrected-copy job ended: its own for a failure, "Cancelled." for a cancel. */
internal fun copyNotice(result: Result<*>): String = result.fold(
    onSuccess = { (it as? CorrectedCopyOutcome.Done)?.message ?: VisionCopy.COPY_FAILED },
    onFailure = { error ->
        when (error) {
            is kotlinx.coroutines.CancellationException -> VisionCopy.COPY_CANCELLED
            is CorrectedCopyFailure -> error.message ?: VisionCopy.COPY_FAILED
            else -> VisionCopy.COPY_FAILED
        }
    },
)

/**
 * The photo as the viewer draws it, in place of a bare [MediaImage]: the original; the live image
 * under the colour-matrix preview while the exact picture is not ready (or cannot be made); or the
 * exact picture. The pattern layer, when there is one, sits over it in the same box, so the viewer's
 * zoom layer moves both together. Both are `ContentScale.Fit`, so they cover the same rectangle.
 */
@Composable
internal fun ColourVisionPhoto(
    viewer: ColourVisionViewer,
    asset: MediaAsset,
    support: VisionSupport,
    animate: Boolean,
    onImageSize: ((androidx.compose.ui.geometry.Size) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val choice = viewer.choice
    val current = viewer.holds(PhotoKey(asset))
    val ready = viewer.filtered.takeIf { current }
    val plan = remember(choice.deficiency, choice.kind, choice.severityStep, ready?.key, support) {
        if (support == VisionSupport.NONE) {
            DisplayPlan.Original
        } else {
            displayPlan(choice, ready?.key, exactAllowed = support == VisionSupport.FULL)
        }
    }
    when (plan) {
        is DisplayPlan.Exact -> {
            val picture = ready?.takeIf { it.key == plan.key }
            if (picture != null) {
                val image = remember(picture.bitmap) { picture.bitmap.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = asset.displayName,
                    modifier = modifier,
                    contentScale = ContentScale.Fit,
                )
            }
        }
        is DisplayPlan.Preview -> MediaImage(
            asset = asset,
            modifier = modifier.colourMatrixLayer(plan.matrix),
            contentScale = ContentScale.Fit,
            onImageSize = onImageSize,
            animate = animate,
        )
        DisplayPlan.Original -> MediaImage(
            asset = asset,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            onImageSize = onImageSize,
            animate = animate,
        )
    }
    val layer = viewer.hatch
    if (current && layer is HatchState.Ready) {
        val image = remember(layer.bitmap) { layer.bitmap.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            // Nearest, so a one-pixel stroke is not smeared into a grey band by the zoom layer.
            filterQuality = FilterQuality.None,
        )
    }
}

/**
 * Draws this content through a 4x5 colour matrix (the layout of `android.graphics.ColorMatrix`).
 * An offscreen layer carries the colour filter, so it works on any content, an image included.
 */
@Composable
internal fun Modifier.colourMatrixLayer(matrix: FloatArray): Modifier {
    val paint = remember(matrix) { Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix)) } }
    return this.drawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * What is drawn over the viewer for the colour tools, outside the photo's zoom layer: the chip that
 * says a filter is on (a tap turns it off), the name of a tapped colour, and the notice about a
 * copy. [bottomReserve] lifts the chip and the notice clear of the filmstrip. Nothing here takes a drag; the name and the chip are taps. The label is kept
 * [ShellEdges.INSET_DP] from every side so it is clear of the shell's edge zones (TRAPS #72).
 */
@Composable
internal fun ColourVisionOverlay(
    viewer: ColourVisionViewer,
    support: VisionSupport,
    container: IntSize,
    bottomReserve: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        val choice = viewer.choice
        val deficiency = choice.deficiency
        if (deficiency != null && support != VisionSupport.NONE) {
            val text = VisionCopy.badge(deficiency, choice.kind)
            val off = VisionCopy.BADGE_TURN_OFF
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = ShellEdges.INSET_DP.dp, bottom = 12.dp + bottomReserve)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(RoomStyle.Surface.copy(alpha = 0.92f))
                    .clickable(onClickLabel = off) { viewer.update { it.withDeficiency(null) } }
                    .semantics { contentDescription = "$text. $off." }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = text, color = RoomStyle.Ink, style = RoomStyle.Caption)
            }
        }
        val notice = viewer.notice
        if (notice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = ShellEdges.INSET_DP.dp, end = ShellEdges.INSET_DP.dp, bottom = 72.dp + bottomReserve)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(RoomStyle.Surface.copy(alpha = 0.92f))
                    .clickable { viewer.putNotice(null) }
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = notice, color = RoomStyle.Ink, style = RoomStyle.Caption)
            }
        }
        val named = viewer.name
        if (named != null) NamePill(named, container, onDismiss = { viewer.putName(null) })
    }
}

/** The tapped colour: a swatch of it and its name, placed near the finger. */
@Composable
private fun NamePill(named: NamedColour, container: IntSize, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { ShellEdges.INSET_DP.dp.toPx() }
    val gap = with(density) { 24.dp.toPx() }
    val description = VisionCopy.nameDescription(named.label)
    Layout(
        content = {
            Row(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(RoomStyle.Surface.copy(alpha = 0.94f))
                    .clickable(onClickLabel = VisionCopy.CLOSE, onClick = onDismiss)
                    .semantics {
                        role = Role.Button
                        contentDescription = description
                        liveRegion = LiveRegionMode.Polite
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(named.argb))
                        .border(1.dp, RoomStyle.InkMuted, CircleShape),
                )
                Text(
                    text = named.label,
                    color = RoomStyle.Ink,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val at = LabelPlacement.place(
            named.tapX, named.tapY, placeable.width.toFloat(), placeable.height.toFloat(),
            container.width.toFloat(), container.height.toFloat(), margin, gap,
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(at.left.toInt(), at.top.toInt())
        }
    }
}

/** [block]'s result, or null when it fails or runs out of memory; a cancellation still propagates. */
private inline fun <T> orNull(block: () -> T): T? = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}
