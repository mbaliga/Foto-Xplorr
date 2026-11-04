package com.fotoxplorr.app.composite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.formats.composite.AppleIdentifiers
import com.fotoxplorr.core.formats.composite.BurstCandidate
import com.fotoxplorr.core.formats.composite.BurstGrouping
import com.fotoxplorr.core.formats.composite.BurstStack
import com.fotoxplorr.core.formats.composite.BurstStacks
import com.fotoxplorr.core.formats.composite.LivePhotoPairing
import com.fotoxplorr.core.formats.composite.PairCandidate
import com.fotoxplorr.core.formats.composite.StackBadge
import com.fotoxplorr.core.model.MediaId

/** Library-wide composite groups: computed from the whole library, applied per view. */
internal data class CompositeGroups(
    /** Still id -> movie id of each Live Photo pair. */
    val livePairs: Map<MediaId, MediaId> = emptyMap(),
    val bursts: List<BurstStack<MediaId>> = emptyList(),
) {
    val isEmpty: Boolean get() = livePairs.isEmpty() && bursts.isEmpty()
}

/**
 * What one grid draws once composite media is applied: [visible] (bursts collapsed to their cover,
 * paired Live Photo movies hidden behind their still), the badge of each stack's representative,
 * and the movie of each still in view (what the viewer plays for a Live Photo).
 */
internal data class CompositeView(
    val visible: List<MediaAsset>,
    val badges: Map<MediaId, StackBadge<MediaId>> = emptyMap(),
    val liveMovies: Map<MediaId, MediaAsset> = emptyMap(),
)

/**
 * Composite media in the grid: a PRESENTATION over the browsable list, never a change to the
 * library. Nothing here writes anything: the groups are recomputed from the list in memory, the
 * Apple identifiers read from files are cached in memory only ([CompositeProbe]), and the two
 * Settings > Media switches turn each half off. Only items of the list handed in can be shown or
 * counted, so a hidden or locked frame of a burst never reaches a badge or the viewer (TRAPS #33).
 */
internal object CompositeGrouping {

    /** A folder key that is equal only for files in the same folder (bucket ids are per path). */
    fun folderOf(asset: MediaAsset): String = asset.bucketId?.toString() ?: asset.relativePath.orEmpty()

    /** Pairs and bursts for [library], using whatever Apple identifiers have been read so far. */
    fun groups(library: List<MediaAsset>, apple: Map<MediaId, AppleIdentifiers>, pairLive: Boolean, groupBursts: Boolean): CompositeGroups {
        val pairs = if (!pairLive) {
            emptyMap()
        } else {
            LivePhotoPairing.pair(
                library.mapNotNull { a ->
                    val movie = a.isVideo
                    if (movie && !CompositeProbe.isLiveMovieCandidate(a)) return@mapNotNull null
                    if (!movie && !stillMayPair(a)) return@mapNotNull null
                    PairCandidate(a.id, folderOf(a), LivePhotoPairing.baseName(a.displayName), movie, a.dateTakenMillis, apple[a.id]?.contentIdentifier)
                },
            )
        }
        val paired = pairs.values.toHashSet()
        val bursts = if (!groupBursts) {
            emptyList()
        } else {
            BurstGrouping.group(
                library.filter { !it.isVideo && it.id !in paired }
                    .map { BurstCandidate(it.id, folderOf(it), it.displayName, it.dateTakenMillis, apple[it.id]?.burstUuid, takenIsCaptureTime(it)) },
            )
        }
        return CompositeGroups(pairs, bursts)
    }

    /** Applies [groups] to one view's [items], with the stacks in [expanded] open. */
    fun present(items: List<MediaAsset>, groups: CompositeGroups, expanded: Set<MediaId>): CompositeView {
        if (groups.isEmpty || items.isEmpty()) return CompositeView(items)
        val byId = HashMap<MediaId, MediaAsset>(items.size * 2)
        for (a in items) byId[a.id] = a
        val liveMovies = HashMap<MediaId, MediaAsset>()
        val hiddenMovies = HashSet<MediaId>()
        for ((still, movie) in groups.livePairs) {
            if (still in byId) {
                byId[movie]?.let {
                    liveMovies[still] = it
                    hiddenMovies += movie
                }
            }
        }
        val unpaired = if (hiddenMovies.isEmpty()) items else items.filter { it.id !in hiddenMovies }
        val stacked = BurstStacks.present(unpaired, { it.id }, groups.bursts, expanded)
        return CompositeView(stacked.visible, stacked.badges, liveMovies)
    }

    /**
     * Which files are worth opening for Apple identifiers: HEIF stills (iPhone's own format), and
     * JPEG stills and QuickTime movies in folders that hold a QuickTime movie. Newest first, at most
     * [cap], skipping what is already known.
     */
    fun scanCandidates(library: List<MediaAsset>, known: Set<MediaId>, cap: Int = SCAN_CAP): List<MediaAsset> {
        val movieFolders = library.filter { CompositeProbe.isLiveMovieCandidate(it) }.mapTo(HashSet()) { folderOf(it) }
        return library.asSequence()
            .filter { it.id !in known }
            .filter { a ->
                val heif = a.mimeType.equals("image/heic", true) || a.mimeType.equals("image/heif", true) ||
                    a.displayName.endsWith(".heic", true)
                heif || (folderOf(a) in movieFolders && (CompositeProbe.isLiveMovieCandidate(a) || stillMayPair(a)))
            }
            .sortedByDescending { it.dateTakenMillis }
            .take(cap)
            .toList()
    }

    /**
     * False when the taken time is the scanner's stand-in, the modification second (a file with no
     * taken date). The catalogue stores only the resulting number, so this is read back from it: a
     * camera file with a whole-second taken time written in that same second also reads as a stand-in,
     * which only means the time rule leaves it alone (the name and Apple rules still apply).
     */
    fun takenIsCaptureTime(a: MediaAsset): Boolean = a.dateTakenMillis != a.dateModifiedSeconds * 1_000L

    private fun stillMayPair(a: MediaAsset): Boolean {
        val mime = a.mimeType.lowercase()
        val name = a.displayName.lowercase()
        return mime == "image/jpeg" || mime == "image/heic" || mime == "image/heif" ||
            name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".heic")
    }

    /**
     * Files opened per pass at most: a whole large iPhone library in one background pass (headers
     * only), so later passes find it all in [AppleIdentifierMemory] (whose capacity is twice this).
     */
    const val SCAN_CAP = 50_000
}

/**
 * The Live Photo movies of the stills in the grid on screen, for the viewer. In memory only, and
 * only ever pairs whose two halves are both in the browsable list the grid drew.
 */
internal object LivePhotoMovies {
    var current: Map<MediaId, MediaAsset> by mutableStateOf(emptyMap())
        internal set

    fun movieFor(still: MediaId): MediaAsset? = current[still]
}

/**
 * The last groups the grid computed and the switches they were computed with, in memory only. The
 * gallery leaves composition while the viewer is open; on its return the grid draws these at once
 * instead of the ungrouped list, so the restored scroll position (counted in the stacked list) lands
 * on the same photo. Groups name ids only: [CompositeGrouping.present] shows nothing that is not in
 * the list handed in, so a photo locked or hidden meanwhile cannot come back through them (TRAPS #33).
 */
internal object CompositeGroupsMemory {
    @Volatile
    var last: Triple<Boolean, Boolean, CompositeGroups>? = null

    fun groupsFor(pairLive: Boolean, groupBursts: Boolean): CompositeGroups =
        last?.takeIf { it.first == pairLive && it.second == groupBursts }?.third ?: CompositeGroups()
}

/**
 * The grid's composite view of [items] (one view of [library]): groups are computed off the main
 * thread whenever the library or a switch changes. Until then the last groups stay on screen
 * ([CompositeGroupsMemory]), so a search keystroke or a refresh does not unstack and restack the
 * grid. Apple identifiers already read ([CompositeProbe.appleMemory]) are used from the first
 * pass; only files never read in this process are opened, in the background, at most
 * [CompositeGrouping.SCAN_CAP] per library change and only while [pairLive] or [groupBursts] is on
 * ([AppleIdentifierPass]). A search or a time range narrows [library] to files already read, so it
 * regroups without opening anything.
 */
@Composable
internal fun rememberCompositeView(
    items: List<MediaAsset>,
    library: List<MediaAsset>,
    pairLive: Boolean,
    groupBursts: Boolean,
    expanded: Set<MediaId>,
): CompositeView {
    val context = LocalContext.current.applicationContext
    var groups by remember { mutableStateOf(CompositeGroupsMemory.groupsFor(pairLive, groupBursts)) }
    LaunchedEffect(library, pairLive, groupBursts) {
        if (!pairLive && !groupBursts) {
            groups = CompositeGroups()
            CompositeGroupsMemory.last = null
            return@LaunchedEffect
        }
        AppleIdentifierPass(CompositeProbe.appleMemory) { CompositeProbe.readAppleIdentifiers(context, it) }
            .run(library, pairLive, groupBursts) { next ->
                groups = next
                CompositeGroupsMemory.last = Triple(pairLive, groupBursts, next)
            }
    }
    val view = remember(items, groups, expanded) { CompositeGrouping.present(items, groups, expanded) }
    LaunchedEffect(view.liveMovies) { LivePhotoMovies.current = view.liveMovies }
    return view
}

/** Saves the set of open stacks across the viewer round trip (ids as a LongArray, Bundle-safe). */
internal val MediaIdSetSaver: Saver<Set<MediaId>, LongArray> = Saver(
    save = { ids -> LongArray(ids.size).also { out -> ids.forEachIndexed { i, id -> out[i] = id.value } } },
    restore = { saved -> saved.mapTo(LinkedHashSet()) { MediaId(it) } },
)

/** The open stacks after the badge of [id] is pressed: open becomes closed and closed open. */
internal fun toggledStack(expanded: Set<MediaId>, id: MediaId): Set<MediaId> = if (id in expanded) expanded - id else expanded + id
