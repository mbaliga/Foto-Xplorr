package com.fotoxplorr.app.wall

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.experimental.ExperimentEnv
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The wall photo is decoded to about this long edge (and at most this many pixels) for the preview. */
internal val WALL_PREVIEW_LIMITS = DecodeLimits(maxLongEdge = 1600, maxPixels = 2_560_000L)

/**
 * The pictures hung on it are decoded smaller: at most six are alive at once and each is drawn at
 * a fraction of the screen, so 1024 px is plenty for the picture and keeps six of them modest.
 * The dpi figures use the decode's `sourceWidth`/`sourceHeight` (the real size), not these.
 */
internal val PICTURE_PREVIEW_LIMITS = DecodeLimits(maxLongEdge = 1024, maxPixels = 1_050_000L)

/**
 * "See it on your wall": a mock-up of pictures hung on a photo of the person's own wall, at their
 * real size, with an honest word on how well each would print. The wall photo comes from the
 * browsable list (their camera app already put it there), so this needs no camera, no permission and
 * no library. It saves, shares and sends nothing until the person asks for a print file or
 * the system print dialog (see [PrintExporter] and [PhotoPrintAdapter]).
 *
 * Photos: only `env.assets` is listed; a draft restored after the process died is checked against
 * it, and a photo that has since been locked or trashed drops out of the draft and out of memory.
 * Pixels come only through `env.decode`, which asks the live browsable set again; the export asks
 * `env.mayExport` once more at the moment it writes. An export in flight is cancelled when this
 * screen is left, and is not restored after process death.
 *
 * Process death: the [WallDraft] is kept by one `rememberSaveable` (see there for what that holds).
 * Decoded pictures, the lens read, and a reference being marked are not, and come back by decoding
 * again. Back inside a step goes to the step before it; Back on the first step is the host's.
 *
 * Nothing has run on a real device: dragging, the feel of pinch and twist, and the fit of the wall
 * photo with the edge insets are unverified.
 */
@Composable
fun WallExperience(env: ExperimentEnv, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val stills = remember(env.assets) { env.assets.stills() }
    val byId = remember(stills) { stills.associateBy { it.id.value } }
    var saved by rememberSaveable(stateSaver = Saver<WallDraft, String>(save = { it.encode() }, restore = { WallDraft.decode(it) })) {
        mutableStateOf(WallDraft())
    }
    // An empty list is a library still loading as much as an empty library, so it is never taken as
    // "everything was locked" (that would wipe a restored draft); nothing is drawn from it either way.
    val draft = remember(saved, byId) { if (byId.isEmpty()) saved else saved.reconciled(byId.keys) }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnv by rememberUpdatedState(env)

    val wallAsset = draft.wallId?.let(byId::get)
    val wall by produceState<WallPhoto?>(null, wallAsset?.id) {
        value = null
        val asset = wallAsset ?: return@produceState
        val decoded = latestEnv.decodeSafely(asset, WALL_PREVIEW_LIMITS) ?: return@produceState
        val focal = withContext(Dispatchers.IO) {
            if (latestEnv.mayExport(asset)) WallExif.focal35mm(latestEnv.context, asset.contentUri) else null
        }
        value = WallPhoto(decoded, focal)
    }

    val pictures = remember { mutableStateMapOf<Long, HungPicture?>() }
    val hungIds = draft.frames.map { it.assetId }
    LaunchedEffect(hungIds, byId) {
        pictures.keys.retainAll(hungIds.toSet())
        for (id in hungIds) {
            if (pictures.containsKey(id)) continue
            val asset = byId[id] ?: continue
            pictures[id] = latestEnv.decodeSafely(asset, PICTURE_PREVIEW_LIMITS)?.let(::HungPicture)
        }
    }

    val export = rememberPrintActions(env, byId)

    WallPreviewScreen(
        draft = draft,
        stills = stills,
        wall = wall,
        pictures = pictures,
        onEdit = { change -> saved = change(latestDraft) },
        onClose = onClose,
        modifier = modifier,
        export = export,
    )
}

/**
 * [ExperimentEnv.decode] that answers null for a file or a memory shortfall instead of throwing
 * (TRAPS #49: the decode can throw before its own guard), and lets a cancellation through.
 */
private suspend fun ExperimentEnv.decodeSafely(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? =
    try {
        decode(asset, limits)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
