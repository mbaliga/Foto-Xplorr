package com.fotoxplorr.app.experimental

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fotoxplorr.app.ai.AiSettingsScreen
import com.fotoxplorr.app.ai.SimilarityExplorerScreen
import com.fotoxplorr.app.experience.GalleryPreviewScreen
import com.fotoxplorr.app.experience.PhotoWallScreen
import com.fotoxplorr.app.spatial.SpatialPhotoSceneScreen
import com.fotoxplorr.app.ui.HelpCopy

/**
 * Every experiment the Experimental section offers, in the order the cards appear within a group.
 *
 * **To add one:** append one [ExperimentEntry] here (its screen is a file of its own, in its own
 * package; its words are constants in its own copy object, with the one-sentence [blurb] taken
 * from there). Pick a new, permanent `id`. Nothing else in this package changes. Set
 * `restorable = false` for a camera screen or anything that starts work or asks a permission, and
 * keep the Setup entry last in its group. Give a full-screen interactive area
 * [clearOfShellEdges] (TRAPS #72) and read photos only through [ExperimentEnv].
 *
 * The ids below are saved state: never rename one.
 */
internal val builtInExperiments: List<ExperimentEntry> = listOf(
    ExperimentEntry(
        id = "similarity",
        title = "Organise by similarity",
        blurb = HelpCopy.Hint.EXPLORE_SIMILARITY,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.Psychology,
        coverSkip = 0,
        badge = { "On-device" },
    ) { env, onClose ->
        SimilarityExplorerScreen(
            assets = env.assets,
            indexInput = env.indexInput,
            onOpenAsset = env.onOpenAsset,
            onClose = onClose,
        )
    },
    ExperimentEntry(
        id = "wall",
        title = "3D photo wall",
        blurb = HelpCopy.Hint.EXPLORE_WALL,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.AutoAwesomeMosaic,
        coverSkip = 0,
        badge = { "OpenGL ES" },
    ) { env, onClose ->
        PhotoWallScreen(assets = env.assets, onOpenAsset = env.onOpenAsset, onClose = onClose)
    },
    ExperimentEntry(
        id = "preview",
        title = "Gallery preview",
        blurb = HelpCopy.Hint.EXPLORE_PREVIEW,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.PermMedia,
        coverSkip = 8,
        badge = { "Touch + D-pad" },
    ) { env, onClose ->
        GalleryPreviewScreen(assets = env.assets, onOpenAsset = env.onOpenAsset, onClose = onClose)
    },
    ExperimentEntry(
        id = "compass",
        title = "Compass view",
        blurb = HelpCopy.Hint.EXPLORE_COMPASS,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.ViewInAr,
        badge = { env -> if (env.geoState.isIndexing) "Indexing…" else "${env.geoState.locatedCount} located" },
    ) { env, onClose ->
        // The location index starts here, when the compass opens with nothing scanned, and nowhere
        // else in this section. Map mode starts its own (PhotoMapPane).
        IndexLocationsOnOpen(env)
        SpatialPhotoSceneScreen(
            assets = env.assets,
            geoState = env.geoState,
            onIndexLocations = env.onIndexLocations,
            onOpenAsset = env.onOpenAsset,
            onClose = onClose,
        )
    },
    ExperimentEntry(
        id = "ai",
        title = "AI and provider keys",
        blurb = HelpCopy.Hint.EXPLORE_AI,
        group = ExperimentGroup.SETUP,
        icon = Icons.Outlined.Key,
        badge = { "Keystore" },
    ) { _, onClose ->
        AiSettingsScreen(onClose = onClose)
    },
)

/** Asks for a location read once per time the compass opens, if nothing has been scanned yet. */
@Composable
private fun IndexLocationsOnOpen(env: ExperimentEnv) {
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(env.geoState.scannedCount, env.geoState.isIndexing) {
        if (shouldStartLocationIndex(env.geoState, asked)) {
            asked = true
            env.onIndexLocations()
        }
    }
}
