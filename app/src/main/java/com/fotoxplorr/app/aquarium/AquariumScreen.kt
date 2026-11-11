package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.withTimeoutOrNull
import com.fotoxplorr.app.ui.RoomStyle

/**
 * The system's animator duration scale (Settings.Global), 1 when unset or unreadable. 0 means the
 * person turned animations off; [TankLogic.startsPaused] decides what the tank does about it.
 */
internal fun readAnimatorScale(context: Context): Float = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (e: Exception) {
    1f
}

// The water: fixed colours (semantic), a calm blue to a deep one, so every palette colour reads on it.
private val WATER_TOP = Color(0xFF2B6F9C)
private val WATER_BOTTOM = Color(0xFF0B2A47)
private val SAND = Color(0xFFB9A57A)
private val FOOD = Color(0xFFE8C07A)

/** Tank units of food radius. */
private const val FOOD_RADIUS = 0.009f

/**
 * The tank. A [Canvas] fills the screen; a frame loop steps [sim] and redraws it.
 *
 * Frame loop: `withFrameNanos` inside `repeatOnLifecycle(RESUMED)`, so it runs only while this
 * screen is resumed and stops the moment the app is paused or stopped (a locked app does not keep
 * animating behind the lock) and while [paused]. It starts again from a zero first step, so a
 * long pause is never one big jump; the simulation clamps a long frame as well. Everything that
 * reads or writes [sim] runs on the main thread, in the frame callback or a gesture, because the
 * simulation is single-threaded mutable state read by the draw lambda.
 *
 * Touch: a short press drops food where the finger was; holding, or dragging, scares creatures
 * near the finger for as long as it stays down ([classifyTouch]). The Feed button is the way to
 * feed without a finger on the tank. A touch does nothing while paused. The tank is full-bleed, not
 * inset from the shell's edge zones: a tap is not taken by the shell, and a drag that begins at the
 * very edge opening a room is the shell doing its job.
 *
 * Words for TalkBack: the tank reads "Aquarium, N creatures". The creature list (a 48 dp remove
 * button per creature) is the non-visual way to know and manage what is in it.
 */
@Composable
internal fun AquariumScreen(
    sim: TankSimulation,
    sprites: Map<String, Bitmap>,
    creatures: List<StoredCreature>,
    loaded: Boolean,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    onDraw: () -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    renderer: CreatureRenderer = remember { MeshCreatureRenderer() },
) {
    var frame by remember { mutableLongStateOf(0L) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var feedSlot by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle, paused, sim) {
        if (paused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    sim.step(TankLogic.frameSeconds(last, now))
                    last = now
                    frame++
                }
            }
        }
    }

    val latestPaused by rememberUpdatedState(paused)
    val slop = LocalViewConfiguration.current.touchSlop
    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { sim.setAspect(TankLogic.aspectOf(it.width, it.height)); frame++ }
                .semantics { contentDescription = AquariumCopy.tank(creatures.size) }
                .pointerInput(sim, slop) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (latestPaused) return@awaitEachGesture
                        val unit = size.height.toFloat().coerceAtLeast(1f)
                        val downAt = down.uptimeMillis
                        val startedNs = System.nanoTime()
                        var at = down.position
                        var travel = 0f
                        var disturbing = false
                        try {
                            while (true) {
                                val heldMs = (System.nanoTime() - startedNs) / 1_000_000L
                                val wait = if (disturbing) Long.MAX_VALUE else (TOUCH_HOLD_MS + 1 - heldMs).coerceAtLeast(1L)
                                val event = withTimeoutOrNull(wait) { awaitPointerEvent() }
                                if (event == null) {
                                    // Held long enough: a scare, not a feed.
                                    disturbing = true
                                    sim.disturb(at.x / unit, at.y / unit)
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                at = change.position
                                travel = maxOf(travel, (at - down.position).getDistance())
                                val kind = classifyTouch(change.uptimeMillis - downAt, travel, slop)
                                if (!change.pressed) {
                                    if (kind == TouchKind.TAP) sim.dropFood(at.x / unit, at.y / unit)
                                    break
                                }
                                if (kind == TouchKind.DISTURB) {
                                    disturbing = true
                                    sim.disturb(at.x / unit, at.y / unit)
                                }
                            }
                        } finally {
                            sim.endDisturbance()
                        }
                    }
                },
        ) {
            if (frame < 0 || sim.revision < 0) return@Canvas
            drawRect(Brush.verticalGradient(listOf(WATER_TOP, WATER_BOTTOM)))
            drawRect(SAND.copy(alpha = 0.55f), topLeft = Offset(0f, size.height * 0.985f))
            val unit = size.height
            for (p in sim.food) {
                if (p.active) drawCircle(FOOD, radius = FOOD_RADIUS * unit, center = Offset(p.x * unit, p.y * unit))
            }
            drawIntoCanvas { c ->
                val native = c.nativeCanvas
                for (creature in sim.creatures) {
                    val sprite = sprites[creature.key] ?: continue
                    renderer.draw(native, creature, sprite, unit, sim.waveScale)
                }
            }
        }

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Scrim { AquariumIconButton(Icons.Outlined.Close, AquariumCopy.CLOSE, onClose) }
                Box(Modifier.weight(1f))
                if (paused) {
                    Text(AquariumCopy.PAUSED, color = RoomStyle.Ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                }
                Scrim {
                    AquariumIconButton(
                        if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (paused) AquariumCopy.PLAY else AquariumCopy.PAUSE,
                        { onPausedChange(!paused) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (loaded && creatures.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(AquariumCopy.EMPTY_PROMPT, color = RoomStyle.Ink, style = MaterialTheme.typography.titleMedium)
                        AquariumButton(AquariumCopy.DRAW, onDraw, primary = true)
                    }
                }
            }
            if (showList) {
                CreatureList(creatures, sprites, onDelete, onHide = { showList = false })
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (creatures.isNotEmpty()) {
                    AquariumButton(
                        AquariumCopy.FEED,
                        onClick = {
                            sim.dropFood(TankLogic.feedX(feedSlot, sim.aspect), TankLogic.FEED_Y)
                            feedSlot++
                        },
                        enabled = !paused,
                    )
                    AquariumButton(AquariumCopy.CREATURES, onClick = { showList = !showList })
                    AquariumButton(AquariumCopy.DRAW_ANOTHER, onDraw, primary = true)
                }
            }
        }
    }
}

@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.45f))) { content() }
}

@Composable
private fun CreatureList(
    creatures: List<StoredCreature>,
    sprites: Map<String, Bitmap>,
    onDelete: (String) -> Unit,
    onHide: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RoomStyle.Surface)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(AquariumCopy.CREATURES, color = RoomStyle.Ink, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
            AquariumIconButton(Icons.Outlined.Close, AquariumCopy.HIDE_CREATURES, onHide)
        }
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            creatures.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val sprite = sprites[c.id]
                    if (sprite != null) {
                        Image(sprite.asImageBitmap(), contentDescription = null, modifier = Modifier.size(width = 56.dp, height = 40.dp).padding(horizontal = 4.dp))
                    }
                    Text(AquariumCopy.creature(i + 1), color = RoomStyle.Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    AquariumIconButton(Icons.Outlined.Delete, AquariumCopy.remove(i + 1), { onDelete(c.id) })
                }
            }
        }
    }
}
