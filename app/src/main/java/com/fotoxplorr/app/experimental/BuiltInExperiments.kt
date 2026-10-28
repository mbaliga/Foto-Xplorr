package com.fotoxplorr.app.experimental

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanoramaHorizontal
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wallpaper
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fotoxplorr.app.ai.AiSettingsScreen
import com.fotoxplorr.app.ai.SimilarityExplorerScreen
import com.fotoxplorr.app.aquarium.AquariumCopy
import com.fotoxplorr.app.aquarium.AquariumExperience
import com.fotoxplorr.app.animate.AnimateScreenCopy
import com.fotoxplorr.app.animate.AnimatedImages
import com.fotoxplorr.app.board.BoardCopy
import com.fotoxplorr.app.board.MoodBoard
import com.fotoxplorr.app.chroma.ChromaCopy
import com.fotoxplorr.app.chroma.ChromaId
import com.fotoxplorr.app.experience.GalleryPreviewScreen
import com.fotoxplorr.app.experience.PhotoWallScreen
import com.fotoxplorr.app.palettebuilder.PaletteBuilder
import com.fotoxplorr.app.palettebuilder.PaletteBuilderCopy
import com.fotoxplorr.app.pano.PanoCopy
import com.fotoxplorr.app.pano.PanoramaExperience
import com.fotoxplorr.app.reel.ReelCopy
import com.fotoxplorr.app.reel.ReelMaker
import com.fotoxplorr.app.spatial.SpatialPhotoSceneScreen
import com.fotoxplorr.app.ui.HelpCopy
import com.fotoxplorr.app.vision.ColourVisionCheck
import com.fotoxplorr.app.vision.VisionCopy
import com.fotoxplorr.app.wall.WallCopy
import com.fotoxplorr.app.wall.WallExperience

/**
 * Every experiment the Experimental section offers, in the order the cards appear within a group.
 *
 * **To add one:** append one [ExperimentEntry] here (its screen is a file of its own, in its own
 * package; its words are constants in its own copy object, with the one-sentence [blurb] taken
 * from there). Pick a new, permanent `id`. Nothing else in this package changes. Set
 * `restorable = false` for a camera screen or anything that starts work or asks a permission (the
 * compass does both), and keep the Setup entry last in its group. Give a full-screen interactive area
 * [clearOfShellEdges] (TRAPS #72) and read photos only through [ExperimentEnv].
 *
 * The ids below are saved state: never rename one.
 */
internal val builtInExperiments: List<ExperimentEntry> = listOf(
    // Make. Neither of these starts work or asks a permission on open, so both are restorable: the
    // aquarium reloads its creatures from files and the wall checks its draft against the photos
    // the gallery shows now. Each keeps its draft through rotation, process death, the viewer round
    // trip and leaving the Experimental destination (KeepExperimentalState); closing the
    // experiment ends it.
    ExperimentEntry(
        id = "aquarium",
        title = AquariumCopy.TITLE,
        blurb = AquariumCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Brush,
    ) { _, onClose ->
        // Reads no library photo, so it takes nothing from the environment.
        AquariumExperience(onClose)
    },
    ExperimentEntry(
        // Not "wall": that id is the 3D photo wall below, and a saved id is never reused.
        id = "wall-mockup",
        title = WallCopy.TITLE,
        blurb = WallCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Wallpaper,
    ) { env, onClose ->
        WallExperience(env, onClose)
    },
    // Restorable: opening it starts no work and asks no permission. After process death it comes
    // back on the step it was on with the same photos in the same order (PanoramaDraft); the preview
    // and a save in progress do not.
    ExperimentEntry(
        id = "panorama",
        title = PanoCopy.TITLE,
        blurb = PanoCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.PanoramaHorizontal,
    ) { env, onClose ->
        PanoramaExperience(env, onClose)
    },
    // Restorable, all four: opening one starts no work and asks no permission. Each keeps its own draft
    // (reel, mood board and palette autosave to private files; the GIF/video maker keeps its choices
    // in saved state), so after a kill the person is put back where they were. A render or a save
    // that was running is gone with no file. None of them reads a photo except through ExperimentEnv.
    ExperimentEntry(
        id = "reel",
        title = ReelCopy.TITLE,
        blurb = ReelCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Movie,
    ) { env, onClose ->
        ReelMaker(env, onClose)
    },
    ExperimentEntry(
        id = "animate",
        title = AnimateScreenCopy.TITLE,
        blurb = AnimateScreenCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Gif,
    ) { env, onClose ->
        AnimatedImages(env, onClose)
    },
    ExperimentEntry(
        id = "board",
        title = BoardCopy.TITLE,
        blurb = BoardCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Dashboard,
    ) { env, onClose ->
        MoodBoard(env, onClose)
    },
    ExperimentEntry(
        id = "palette",
        title = PaletteBuilderCopy.TITLE,
        blurb = PaletteBuilderCopy.BLURB,
        group = ExperimentGroup.MAKE,
        icon = Icons.Outlined.Palette,
    ) { env, onClose ->
        PaletteBuilder(env, onClose)
    },
    // Explore.
    ExperimentEntry(
        id = "similarity",
        title = ExperimentalCopy.SIMILARITY_TITLE,
        blurb = HelpCopy.Hint.EXPLORE_SIMILARITY,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.Psychology,
        coverSkip = 0,
        badge = { ExperimentalCopy.BADGE_ON_DEVICE },
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
        title = ExperimentalCopy.WALL_TITLE,
        blurb = HelpCopy.Hint.EXPLORE_WALL,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.AutoAwesomeMosaic,
        coverSkip = 0,
        badge = { ExperimentalCopy.BADGE_OPENGL },
    ) { env, onClose ->
        PhotoWallScreen(assets = env.assets, onOpenAsset = env.onOpenAsset, onClose = onClose)
    },
    ExperimentEntry(
        id = "preview",
        title = ExperimentalCopy.PREVIEW_TITLE,
        blurb = HelpCopy.Hint.EXPLORE_PREVIEW,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.PermMedia,
        coverSkip = 8,
        badge = { ExperimentalCopy.BADGE_TOUCH_DPAD },
    ) { env, onClose ->
        GalleryPreviewScreen(assets = env.assets, onOpenAsset = env.onOpenAsset, onClose = onClose)
    },
    ExperimentEntry(
        id = "compass",
        title = ExperimentalCopy.COMPASS_TITLE,
        blurb = HelpCopy.Hint.EXPLORE_COMPASS,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.ViewInAr,
        // Not restorable: it starts a location read when it opens and asks for the location
        // permission in context, so after the process was killed a restored id must not put the
        // person back in it. In a live process it stays open, so Back from a photo returns here.
        restorable = false,
        badge = { env ->
            if (env.geoState.isIndexing) ExperimentalCopy.BADGE_INDEXING else ExperimentalCopy.located(env.geoState.locatedCount)
        },
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
        id = "chroma",
        title = ChromaCopy.TITLE,
        blurb = ChromaCopy.BLURB,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.Colorize,
        // Not restorable: it opens the camera (and asks for the camera permission in context), so a
        // restored id must not put the person back in front of the camera after a kill. Dimmed on a
        // phone with no camera at all; the screen also says so if the camera cannot be opened.
        restorable = false,
        available = ::deviceHasCamera,
    ) { env, onClose ->
        ChromaId(env, onClose)
    },
    // Restorable: opening it starts nothing and asks nothing; the step and the answers come back.
    ExperimentEntry(
        id = "colourcheck",
        title = VisionCopy.CHECK_TITLE,
        blurb = VisionCopy.CHECK_BLURB,
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.Visibility,
    ) { env, onClose ->
        ColourVisionCheck(env, onClose)
    },
    // Setup, always last.
    ExperimentEntry(
        id = "ai",
        title = ExperimentalCopy.AI_TITLE,
        blurb = HelpCopy.Hint.EXPLORE_AI,
        group = ExperimentGroup.SETUP,
        icon = Icons.Outlined.Key,
        badge = { ExperimentalCopy.BADGE_KEYSTORE },
    ) { _, onClose ->
        AiSettingsScreen(onClose = onClose)
    },
)

/**
 * Whether this phone has any camera (front, back or external), asked of the package manager: the same
 * feature the manifest declares as optional, so a phone without one still installs Fotoz and Chroma ID
 * is dimmed. It opens nothing and needs no permission.
 */
internal fun deviceHasCamera(context: Context): Boolean = cameraFeaturePresent { context.packageManager.hasSystemFeature(it) }

/** [deviceHasCamera] with the feature lookup handed in, so a test needs no phone. */
internal fun cameraFeaturePresent(has: (String) -> Boolean): Boolean = has(PackageManager.FEATURE_CAMERA_ANY)

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
