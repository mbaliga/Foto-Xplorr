package com.fotoxplorr.app.experimental

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.decodeUpright
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.core.model.MediaId

/** The three headings of the Experimental hub, in the order the hub shows them. */
enum class ExperimentGroup(val label: String) {
    MAKE(ExperimentalCopy.GROUP_MAKE),
    EXPLORE(ExperimentalCopy.GROUP_EXPLORE),
    SETUP(ExperimentalCopy.GROUP_SETUP),
}

/**
 * One thing the Experimental section can open. A plain list of these (see [builtInExperiments])
 * is the whole registry: the host draws a card per entry, owns which one is open, and owns Back.
 *
 * @property id stable and unique. It is the one value the host saves across a recreation or a
 *   process death, so it is never renamed once shipped; an id this build does not know opens the
 *   hub, not an error.
 * @property blurb the card's one short sentence.
 * @property restorable whether the host reopens this entry after the process was killed. False
 *   for anything that must not come back by itself: a camera screen, a screen that needs a
 *   permission asked in context, or one that starts work. It does not apply while the process
 *   lives: a rotation, the viewer round trip and leaving the destination keep any open entry.
 * @property available whether this device can run it, asked with an application context. An entry
 *   that says no is drawn dimmed with [ExperimentalCopy.UNAVAILABLE] and is not clickable.
 * @property coverSkip when non-null, the card shows the first still photo after skipping this many
 *   of [ExperimentEnv.assets] as its picture.
 * @property badge a short chip on the card (a count, a mode), or null for none.
 * @property content the full-screen experiment. It must not register a Back handler for plain
 *   "close" (the host's covers it); a screen with an inner step registers its own, and because it
 *   is composed after the host's it is asked first.
 */
class ExperimentEntry(
    val id: String,
    val title: String,
    val blurb: String,
    val group: ExperimentGroup,
    val icon: ImageVector,
    val restorable: Boolean = true,
    val coverSkip: Int? = null,
    val badge: (ExperimentEnv) -> String? = { null },
    val available: (Context) -> Boolean = { true },
    val content: @Composable (env: ExperimentEnv, onClose: () -> Unit) -> Unit,
)

/**
 * What an experiment is given by the host, and the only way it should reach the photo library.
 *
 * The photo list is the gallery's already-filtered one (`browsableAssets`, TRAPS #33): no trashed,
 * archived, locked-folder or hidden-sensitive photo is in [assets], and while "Blur sensitive" is
 * on no flagged one either (see [assetsForExperiments]), because an experiment cannot blur. An experiment that copies
 * pixels out of the library (a cut-out, a print, an export) must additionally ask [mayExport]
 * again at the moment it does so, because the folder can be locked again while the experiment is
 * open, and read the pixels through [decode], which makes the same check.
 *
 * @property assets the photos an experiment may show, newest-first as the gallery orders them.
 * @property indexInput every photo an indexer should cover, trashed included. Only an indexer
 *   reads this: it is never drawn (see `SpatialExperience.indexInput`).
 * @property geoState what the location index knows so far.
 * @property onIndexLocations starts a location read of [indexInput]; call it from an explicit
 *   action or once on open, never in a loop.
 * @property onOpenAsset opens the viewer on a photo, with the list it came from.
 * @property context an application context, for decoding and files.
 */
class ExperimentEnv(
    val assets: List<MediaAsset>,
    val indexInput: List<MediaAsset>,
    val geoState: GeoIndexState,
    val onIndexLocations: () -> Unit,
    val onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    val context: Context,
    private val currentBrowsableIds: () -> Set<MediaId>,
) {
    /**
     * Whether [asset] is still in the browsable list right now. [assets] is a snapshot taken when
     * the host last recomposed; this reads the live one, so a long-running export that began
     * before a folder was locked again is refused at the end.
     */
    fun mayExport(asset: MediaAsset): Boolean = !asset.isVideo && mayReadPixels(asset.id, currentBrowsableIds())

    /**
     * Decodes [asset] upright and bounded to [limits] (EXIF orientation applied, never upscaled,
     * the same call the 3D wall uses), or null when [mayExport] says no or the file cannot be
     * decoded. Suspends on the IO dispatcher.
     */
    suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? =
        if (mayExport(asset)) decodeUpright(context, asset.contentUri, limits) else null
}

/** Whether a photo with [id] may have its pixels read, given the ids the gallery shows now. */
internal fun mayReadPixels(id: MediaId, browsableIds: Set<MediaId>): Boolean = id in browsableIds
