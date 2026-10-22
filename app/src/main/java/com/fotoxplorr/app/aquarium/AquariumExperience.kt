package com.fotoxplorr.app.aquarium

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Draw an aquarium": draw a creature with a finger (or start from a ready shape) and watch it
 * swim in a tank with the ones drawn before. Two steps, the tank and the drawing board, and this
 * entry owns both so a drawing started, the tank's creatures and their places survive going back
 * and forth.
 *
 * It lists no library photo and reads none, so it takes no [com.fotoxplorr.app.experimental.ExperimentEnv];
 * the host card takes its title and blurb from [AquariumCopy.TITLE] and [AquariumCopy.BLURB].
 *
 * Back: while the drawing board is open this entry handles Back itself and returns to the tank (the
 * drawing is kept); in the tank Back is the host's. That one `BackHandler` is enabled only while the
 * board is open, so the host's is asked after it, never swallowed.
 *
 * Storage: each creature is a PNG and a JSON under `filesDir/aquarium` ([CreatureStore]), at most
 * [CreatureStore.MAX_CREATURES]; nothing leaves the device and no library photo is involved.
 * Deleting a creature from the list removes its files at once, without a question: it is a small
 * doodle and the list shows exactly which one.
 *
 * Process death: SAVED are the step, paused or not, and the drawing in progress (strokes, colour,
 * brush, head direction; see [DrawDraftCodec], long strokes are thinned). NOT saved: the redo
 * stack, the stroke being drawn, the tank's positions (creatures come back from their files and
 * are placed afresh), the creature list's open state, and any message. Where the animator scale is
 * 0 the tank opens paused with calm motion and a Play button; pressing Play keeps the calm motion.
 *
 * Nothing here has run on a real device; see the KDoc of [MeshCreatureRenderer], [DrawScreen] and
 * [SwimMesh] for what is unverified.
 */
@Composable
fun AquariumExperience(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember { SpriteIo.storeIn(context) }
    val profile = remember { motionProfile(readAnimatorScale(context)) }
    val sim = remember {
        TankSimulation(seed = System.nanoTime()).also { it.applyProfile(profile) }
    }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var paused by rememberSaveable { mutableStateOf(profile.startPaused) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(DrawDraft()) }
    val latestDraft by rememberUpdatedState(draft)

    // null until the files have been read.
    var creatures by remember { mutableStateOf<List<StoredCreature>?>(null) }
    val sprites = remember { mutableStateMapOf<String, Bitmap>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(store) {
        val found = withContext(Dispatchers.IO) {
            store.sweepIncomplete()
            store.list().mapNotNull { stored -> SpriteIo.decode(stored)?.let { stored.record to it } }
        }
        for ((record, bitmap) in found) sprites[record.id] = bitmap
        creatures = found.map { it.first }
    }
    // The tank follows the list; the first read places creatures at random, later additions swim in.
    var placedFirst by remember { mutableStateOf(false) }
    LaunchedEffect(creatures) {
        val list = creatures ?: return@LaunchedEffect
        TankLogic.reconcile(sim, list, enterFromLeft = placedFirst)
        placedFirst = true
    }

    BackHandler(enabled = drawing) { drawing = false }

    val list = creatures.orEmpty()
    val full = list.size >= CreatureStore.MAX_CREATURES
    if (drawing) {
        DrawScreen(
            draft = draft,
            onDraft = { change -> draft = change(latestDraft) },
            tankFull = full,
            busy = busy,
            message = message,
            onSwim = {
                if (!busy) {
                    busy = true
                    message = null
                    val taken = latestDraft
                    scope.launch {
                        val outcome = try {
                            makeCreature(store, taken)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            MadeCreature.Failed
                        } catch (e: OutOfMemoryError) {
                            MadeCreature.Failed
                        }
                        busy = false
                        when (outcome) {
                            is MadeCreature.Done -> {
                                sprites[outcome.record.id] = outcome.sprite
                                creatures = creatures.orEmpty() + outcome.record
                                draft = latestDraft.copy(sketch = Sketch())
                                drawing = false
                            }
                            MadeCreature.Full -> message = AquariumCopy.TANK_FULL
                            MadeCreature.Failed -> message = AquariumCopy.SAVE_FAILED
                        }
                    }
                }
            },
            onBack = { drawing = false },
            modifier = modifier,
        )
    } else {
        AquariumScreen(
            sim = sim,
            sprites = sprites,
            creatures = list,
            loaded = creatures != null,
            paused = paused,
            onPausedChange = { paused = it },
            onDraw = {
                message = null
                drawing = true
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { store.delete(id) }
                    creatures = creatures.orEmpty().filterNot { it.id == id }
                    sprites.remove(id)
                }
            },
            onClose = onClose,
            modifier = modifier,
        )
    }
}

/** How a Swim press ended. */
private sealed interface MadeCreature {
    class Done(val record: StoredCreature, val sprite: Bitmap) : MadeCreature
    data object Full : MadeCreature
    data object Failed : MadeCreature
}

private suspend fun makeCreature(store: CreatureStore, draft: DrawDraft): MadeCreature {
    val sprite = withContext(Dispatchers.Default) { SketchRasterizer.rasterize(draft.sketch.strokes) }
        ?: return MadeCreature.Failed
    return when (val saved = withContext(Dispatchers.IO) { SpriteIo.save(store, sprite, draft.headLeft) }) {
        is SaveResult.Saved -> MadeCreature.Done(saved.creature, sprite)
        SaveResult.Full -> MadeCreature.Full
        SaveResult.Failed -> MadeCreature.Failed
    }
}

private val DraftSaver = Saver<DrawDraft, String>(save = { DrawDraftCodec.encode(it) }, restore = { DrawDraftCodec.decode(it) })
