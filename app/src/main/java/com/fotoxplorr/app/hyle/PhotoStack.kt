package com.fotoxplorr.app.hyle

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoxplorr.app.aquarium.readAnimatorScale
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.core.model.MediaId

/** The words of the photo stack; one short sentence each, in the feature's own object. */
internal object PhotoStackCopy {
    const val PREVIOUS = "Previous photo"
    const val NEXT = "Next photo"
    const val STACK = "Photos being worked on"
    const val EMPTY = "No photos to show yet"
    const val SENSITIVE = "Sensitive photo, hidden"
}

/**
 * What the stack needs from the host and cannot be handed through a job: which photos are
 * sensitive, whether to blur them, and how to open one.
 *
 * The photos given to the stack are already the browsable list (archived, locked and trashed
 * photos are never in it), so this only decides how a SENSITIVE one that the list still holds
 * is drawn: with [blurSensitive] (the app's own setting) it is not loaded at all and a plate says
 * "Sensitive", because a blur is a no-op below API 31.
 */
class ActivityStackEnv(
    val sensitiveIds: Set<MediaId> = emptySet(),
    val blurSensitive: Boolean = true,
    val onOpen: ((MediaAsset) -> Unit)? = null,
)

/** Provided once by the gallery; the default shows no photo as anything but blurred and opens nothing. */
val LocalActivityStackEnv = compositionLocalOf { ActivityStackEnv() }

/**
 * The pull of the top room as a 0..1 lift, read lazily so only the readers recompose. 1 where
 * nothing provides it (a room is as open as it gets), which is the safe side for insets.
 */
val LocalSheetLift = compositionLocalOf<() -> Float> { { 1f } }

/** Whether the system has animations turned off; the stack then lies square and flips at once. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { reduceMotionFrom(context) }
}

private fun reduceMotionFrom(context: Context): Boolean {
    val scale = readAnimatorScale(context)
    return scale.isFinite() && scale <= 0f
}

private val CardWidth = 84.dp
private val CardHeight = 104.dp

/**
 * A deck of the photos a job is working on, lying like cards on a table: each tilted and nudged a
 * little (seeded, so it never reshuffles), the top one changing as the job advances.
 *
 * You can ruffle through it. A drag across the stack, horizontal or vertical, flips the top card to
 * the back (or, dragged the other way, brings the back one forward), one card per [FLIP_STEP_DP] of
 * travel and a light tick for each. The previous and next buttons do the same without a drag, and
 * the counter says where you are. A tap opens the top photo.
 *
 * With nothing to show it is a neutral silhouette of a stack, never a coloured placeholder.
 *
 * @param photos newest first; the caller has already filtered them to what may be shown (see
 *   [ActivityStackEnv]). At most [PhotoDeck.MAX_CARDS] are used.
 */
@Composable
fun PhotoStack(
    photos: List<MediaAsset>,
    seed: Long,
    modifier: Modifier = Modifier,
    env: ActivityStackEnv = LocalActivityStackEnv.current,
) {
    val fresh = remember(photos) { photos.take(PhotoDeck.MAX_CARDS) }
    // Once the person has thumbed the deck it is pinned: the cards they were looking at stay put
    // and new photos go behind them, so the photo they flipped to never changes under their finger.
    var frozen by remember { mutableStateOf<List<MediaAsset>?>(null) }
    val cards = remember(fresh, frozen) { frozen?.let { mergeBehind(it, fresh) } ?: fresh }
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalHapticFeedback.current
    var deck by remember { mutableStateOf(PhotoDeck(cards.size)) }
    // Whether the person has thumbed the deck. Until they have, the top card follows the job (the
    // newest photo); after, it stays where they left it.
    var touched by remember { mutableStateOf(false) }
    if (deck.size != cards.size) deck = deck.resizedTo(cards.size)
    val shown = if (touched) deck else deck.copy(top = 0)

    fun flip(steps: Int) {
        if (steps == 0 || !shown.canFlip) return
        if (!touched) frozen = cards
        touched = true
        deck = shown.flip(steps)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    // The drag's pointerInput outlives recompositions, so it must call the CURRENT flip, not the
    // one from the composition that started it.
    val flipNow = rememberUpdatedState<(Int) -> Unit> { flip(it) }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            FlipButton(Icons.Outlined.ChevronLeft, PhotoStackCopy.PREVIOUS, enabled = shown.canFlip) { flip(-1) }
            val step = with(androidx.compose.ui.platform.LocalDensity.current) { FLIP_STEP_DP.dp.toPx() }
            val slop = LocalViewConfiguration.current.touchSlop
            Box(
                Modifier
                    .size(width = CardWidth + 36.dp, height = CardHeight + 24.dp)
                    .semantics {
                        contentDescription = if (cards.isEmpty()) {
                            PhotoStackCopy.EMPTY
                        } else {
                            "${PhotoStackCopy.STACK}, ${shown.counter()}"
                        }
                    }
                    .pointerInput(cards, shown.top) {
                        detectTapGestures {
                            cards.getOrNull(shown.topIndex)?.let { env.onOpen?.invoke(it) }
                        }
                    }
                    .pointerInput(cards.size) {
                        val ruffle = RuffleAccumulator(step.coerceAtLeast(slop))
                        detectDragGestures(
                            onDragStart = { ruffle.reset() },
                            onDragEnd = { ruffle.reset() },
                            onDragCancel = { ruffle.reset() },
                        ) { change, drag ->
                            change.consume()
                            flipNow.value(ruffle.add(ruffleDelta(drag.x, drag.y)))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (cards.isEmpty()) {
                    Silhouette()
                } else {
                    // Back to front, so the top card is drawn last.
                    val order = shown.visible()
                    order.asReversed().forEach { index ->
                        val asset = cards[index]
                        androidx.compose.runtime.key(asset.id.value) {
                            Card(asset, seed, isTop = index == shown.topIndex, reduceMotion, env)
                        }
                    }
                }
            }
            FlipButton(Icons.Outlined.ChevronRight, PhotoStackCopy.NEXT, enabled = shown.canFlip) { flip(1) }
        }
        Text(
            text = shown.counter(),
            color = Color.White.copy(alpha = 0.70f),
            style = TextStyle(fontSize = 12.sp),
        )
    }
}

/** Pixels of drag per card; the touch slop is the floor so a tremor never flips one. */
internal const val FLIP_STEP_DP = 36

@Composable
private fun Card(asset: MediaAsset, seed: Long, isTop: Boolean, reduceMotion: Boolean, env: ActivityStackEnv) {
    val target = fanTransform(asset.id.value, seed, isTop, instant = reduceMotion)
    val spec = if (reduceMotion) snap<Float>() else tween(durationMillis = 160)
    val rotation by animateFloatAsState(target.rotationDeg, spec, label = "card-rotation")
    val dx by animateFloatAsState(target.offsetXDp, spec, label = "card-dx")
    val dy by animateFloatAsState(target.offsetYDp, spec, label = "card-dy")
    val hidden = asset.id in env.sensitiveIds && env.blurSensitive
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(width = CardWidth, height = CardHeight)
            .graphicsLayer {
                rotationZ = rotation
                translationX = dx * density
                translationY = dy * density
            }
            .clip(shape)
            .background(Color(0xFF1C1C22))
            .border(1.dp, Color.White.copy(alpha = 0.30f), shape),
    ) {
        // A hidden photo is not loaded at all, on any API: Modifier.blur does nothing below API 31
        // (minSdk is 26), so "blurred" there would have been the sharp photo under a chip.
        if (!hidden) {
            MediaImage(
                asset = asset,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = "Sensitive",
                color = Color.White,
                style = TextStyle(fontSize = 11.sp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .semantics { contentDescription = PhotoStackCopy.SENSITIVE },
            )
        }
    }
}

/** No photo yet: three neutral plates, outlined, the same silhouette a deck makes. */
@Composable
private fun Silhouette() {
    listOf(10f, -7f, 0f).forEach { angle ->
        val shape = RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(width = CardWidth, height = CardHeight)
                .graphicsLayer { rotationZ = angle }
                .clip(shape)
                .background(Color(0xFF1C1C22))
                .border(1.dp, Color.White.copy(alpha = 0.30f), shape),
        )
    }
}

@Composable
private fun FlipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = if (enabled) 0.85f else 0.30f),
        )
    }
}

/** Provides the stack's environment to the shade below [content]. */
@Composable
fun ProvideActivityStack(env: ActivityStackEnv, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActivityStackEnv provides env, content = content)
}
