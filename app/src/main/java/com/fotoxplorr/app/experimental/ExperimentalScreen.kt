package com.fotoxplorr.app.experimental

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.BuildConfig
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.HelpCopy
import com.fotoxplorr.app.ui.RoomStyle
import com.fotoxplorr.core.model.MediaId

/**
 * The Experimental section: the page behind the rail's "Experimental" word (and Discover's card).
 *
 * A hub of cards grouped Make, Explore, Setup, and the host of whichever one is open. The host owns
 * three things so no experiment has to:
 *
 *  - **Which one is open**, as one `rememberSaveable` id. Saved: that id, for entries that are
 *    [ExperimentEntry.restorable]. Not saved: anything inside an experiment (its own state is its
 *    own to save), and the id of a non-restorable one -- those reopen as the hub.
 *  - **Back**, one handler enabled while something is open. It exists because two of the screens
 *    here (similarity, AI keys) register none of their own. A screen with an inner step registers
 *    its own handler, which is composed after this one and so is asked first. A shell room opened
 *    over the experiment is closed before any of them: the gallery's handler is re-registered when
 *    a room opens ([RoomFirstBackHandler]).
 *  - **The ink** (TRAPS #62): on the root-destination path nothing above provides a content
 *    colour, and the theme's would be dark on the pane's black ground.
 *
 * Adding an experiment is one [ExperimentEntry] in [builtInExperiments]; nothing here changes.
 *
 * @param assets the gallery's browsable-filtered photos -- the only list anything is drawn from.
 * @param sensitiveIds the photos flagged sensitive; with [blurSensitive] on they are not given to
 *   any experiment (see [assetsForExperiments]).
 * @param indexInput what an indexer covers, trashed included; see [ExperimentEnv.indexInput].
 * @param entries the list to host; a test passes its own.
 */
@Composable
fun ExperimentalScreen(
    assets: List<MediaAsset>,
    indexInput: List<MediaAsset>,
    geoState: GeoIndexState,
    onIndexLocations: () -> Unit,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    modifier: Modifier = Modifier,
    entries: List<ExperimentEntry> = builtInExperiments,
    sensitiveIds: Set<MediaId> = emptySet(),
    blurSensitive: Boolean = true,
) {
    require(duplicateIds(entries).isEmpty()) { "Experiment ids must be unique: ${duplicateIds(entries)}" }
    val context = LocalContext.current.applicationContext

    var openId by rememberSaveable(
        stateSaver = Saver<String?, String>(save = { id -> idToSave(entries, id) }, restore = { it }),
    ) { mutableStateOf<String?>(null) }
    val current = entryToShow(entries, openId) { it.available(context) }

    BackHandler(enabled = current != null) { openId = null }

    // What an experiment may draw: the browsable list less flagged photos while they are blurred
    // in the grid (see assetsForExperiments).
    val shown = remember(assets, sensitiveIds, blurSensitive) {
        assetsForExperiments(assets, sensitiveIds, blurSensitive)
    }
    // Read live by ExperimentEnv.mayExport, so an export that outlives a re-lock is refused.
    val browsableIds = remember(shown) { shown.mapTo(HashSet()) { it.id } }
    val latestBrowsableIds by rememberUpdatedState(browsableIds)
    val env = ExperimentEnv(
        assets = shown,
        indexInput = indexInput,
        geoState = geoState,
        onIndexLocations = onIndexLocations,
        onOpenAsset = onOpenAsset,
        context = context,
        currentBrowsableIds = { latestBrowsableIds },
    )

    // The pane's ink for the hub and every experiment it opens. A sub-screen that names no colour
    // of its own is right on every path because of this.
    CompositionLocalProvider(LocalContentColor provides RoomStyle.Ink) {
        if (current != null) {
            current.content(env) { openId = null }
        } else {
            ExperimentalHub(entries = entries, env = env, onOpen = { openId = it.id }, modifier = modifier)
        }
    }
}

@Composable
private fun ExperimentalHub(
    entries: List<ExperimentEntry>,
    env: ExperimentEnv,
    onOpen: (ExperimentEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(entries) { groupedEntries(entries) }
    val stills = remember(env.assets) { env.assets.filter { !it.isVideo && !it.isTrashed } }

    Column(modifier = modifier.fillMaxSize()) {
        // The pane's own colours, not the theme's: this sits on the pane's black ground, whose
        // content colour is white whatever the theme. In light theme `colorScheme.surface` is
        // near-white, which put white titles on it and made the header unreadable.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(RoomStyle.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                Text(
                    ExperimentalCopy.TITLE,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    ExperimentalCopy.HEADER,
                    style = MaterialTheme.typography.bodySmall,
                    color = RoomStyle.InkFaint,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(164.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            groups.forEach { (group, inGroup) ->
                item(key = "group-${group.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        group.label,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(inGroup, key = { it.id }) { entry ->
                    ExperimentCard(
                        entry = entry,
                        cover = entry.coverSkip?.let { skip -> stills.getOrNull(skip) },
                        badge = entry.badge(env),
                        available = entry.available(env.context),
                        onClick = { onOpen(entry) },
                    )
                }
            }
            item(key = "privacy", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        ExperimentalCopy.PRIVACY_TITLE,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        HelpCopy.experimentalPrivacy(BuildConfig.NETWORK_FEATURES),
                        style = MaterialTheme.typography.bodySmall,
                        color = RoomStyle.InkFaint,
                    )
                    Text(
                        HelpCopy.Note.EXPERIMENTAL_LOCATION,
                        style = MaterialTheme.typography.bodySmall,
                        color = RoomStyle.InkFaint,
                    )
                    Spacer(Modifier.size(72.dp))
                }
            }
        }
    }
}

@Composable
private fun ExperimentCard(
    entry: ExperimentEntry,
    cover: MediaAsset?,
    badge: String?,
    available: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .alpha(if (available) 1f else 0.5f)
            // The card is one button; a disabled one is announced as such. No onLongClick: a copy of
            // onClick would only give TalkBack a second, identical action to read out.
            .clickable(enabled = available, role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.35f)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                MediaImage(cover, Modifier.fillMaxSize(), ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            }
            // Decorative: the title under it names the card.
            Icon(
                entry.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = if (cover == null) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
            )
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.72f), MaterialTheme.shapes.small)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (available) entry.blurb else ExperimentalCopy.UNAVAILABLE,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
