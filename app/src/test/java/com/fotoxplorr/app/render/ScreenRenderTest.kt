package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SelectionOverlay
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.hyle.ActivityShade
import com.fotoxplorr.app.hyle.BackgroundActivity
import com.fotoxplorr.app.hyle.ShadeState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real composables to PNG, on the JVM, with no device.
 *
 * The owner asked to see the screens before installing a build, and a hand-drawn approximation
 * would have shown what the code is *supposed* to do — which is exactly the thing that has been
 * wrong more than once here (a bar that clipped its last glyph, a swivel facing the wrong way, a
 * pane that vanished). These go through the same Compose runtime the app does, so what comes out
 * is what the code actually produces.
 *
 * `sdk = 34` because Robolectric does not ship a runtime for 36 yet; the app's `compileSdk` is
 * unaffected. NATIVE graphics because the legacy mode draws nothing — every capture would be a
 * blank rectangle, which looks like a rendering bug rather than a configuration one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // BoxScope receiver: the selection chrome is a set of BoxScope children by design, so a
    // plain @Composable lambda cannot call it.
    private fun render(
        name: String,
        content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    ) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Test
    fun `shade collapsed with one job`() {
        render("shade-1-collapsed") {
            ActivityShade(
                activities = listOf(moving()),
                state = ShadeState.COLLAPSED,
                onStateChange = {},
            ) { PhotoGridStandIn() }
        }
    }

    @Test
    fun `shade collapsed with three jobs`() {
        render("shade-2-collapsed-three") {
            ActivityShade(
                activities = listOf(moving(), backingUp(), recognising()),
                state = ShadeState.COLLAPSED,
                onStateChange = {},
            ) { PhotoGridStandIn() }
        }
    }

    @Test
    fun `shade notification state`() {
        render("shade-3-notification") {
            ActivityShade(
                activities = listOf(moving()),
                state = ShadeState.NOTIFICATION,
                onStateChange = {},
            ) { PhotoGridStandIn() }
        }
    }

    @Test
    fun `shade notification with three jobs`() {
        render("shade-4-notification-three") {
            ActivityShade(
                activities = listOf(moving(), backingUp(), recognising()),
                state = ShadeState.NOTIFICATION,
                onStateChange = {},
            ) { PhotoGridStandIn() }
        }
    }

    @Test
    fun `shade expanded`() {
        render("shade-5-expanded") {
            ActivityShade(
                activities = listOf(moving(), backingUp()),
                state = ShadeState.EXPANDED,
                onStateChange = {},
            ) { PhotoGridStandIn() }
        }
    }

    private fun moving() = BackgroundActivity("m", ActivityKind.MOVING, 4_822, 12_366)
    private fun backingUp() = BackgroundActivity("b", ActivityKind.BACKING_UP, 902, 12_366)
    private fun recognising() = BackgroundActivity("r", ActivityKind.RECOGNISING, 155, 13_564)
}

/**
 * A stand-in for the photo grid.
 *
 * Coil cannot decode anything in a JVM test — there is no device, no MediaStore and no files — so
 * the real grid would render as a screen of empty tiles and prove nothing about the shade above it.
 * Flat blocks on the app's own 1dp gutter show the same thing the mockups need to show: that the
 * shade covers the grid rather than pushing it down, and exactly how much of the top row goes.
 */
@androidx.compose.runtime.Composable
private fun PhotoGridStandIn() {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp),
    ) {
        items(30) { index ->
            Box(
                Modifier
                    .size(146.dp)
                    .background(TILE_COLOURS[index % TILE_COLOURS.size]),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.items(
    count: Int,
    block: @androidx.compose.runtime.Composable (Int) -> Unit,
) = items(count = count, itemContent = { block(it) })

/** Muted blocks that read as photographs without pretending to be any. */
private val TILE_COLOURS = listOf(
    Color(0xFF6B5B4A), Color(0xFF8C3A34), Color(0xFF44525E),
    Color(0xFFCFC4B4), Color(0xFF7A5648), Color(0xFF1E6FB8),
    Color(0xFF3A3B44), Color(0xFF2B2B33), Color(0xFF2E86C8),
)

/**
 * The selection chrome and the rooms, rendered the same way.
 *
 * Separate class so the shade's captures and these can be run independently; same configuration,
 * same reason for it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SurfaceRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // BoxScope receiver: the selection chrome is a set of BoxScope children by design, so a
    // plain @Composable lambda cannot call it.
    private fun render(
        name: String,
        content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    ) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Test
    fun `selection chrome over the grid`() {
        render("selection-1-chrome") {
            PhotoGridStandIn()
            SelectionOverlay(
                selection = com.fotoxplorr.app.gallery.GallerySelection(
                    selectedIds = (1L..9999L).map { com.fotoxplorr.app.media.MediaId(it) }.toSet(),
                    selecting = true,
                ),
                selectedAssets = emptyList(),
                inTrash = false,
                actions = noOpActions(),
                onSelectionChange = {},
            )
        }
    }

    @Test
    fun `gallery actions room`() {
        render("room-1-actions") {
            com.fotoxplorr.app.gallery.GalleryActionsRoom(
                state = emptyState(),
                actions = noOpActions(),
                selection = com.fotoxplorr.app.gallery.GallerySelection(
                    selectedIds = (1L..12L).map { com.fotoxplorr.app.media.MediaId(it) }.toSet(),
                    selecting = true,
                ),
                selectedAssets = emptyList(),
                currentIds = emptySet(),
                inTrash = false,
                inArchive = false,
                tagRoute = null,
                collectionRoute = null,
                onSelectionChange = {},
                onRenameAsset = {},
                onAddToCollection = {},
                onAddTag = {},
                onStartSelection = {},
                onNewCollection = {},
                onCloseRoom = {},
            )
        }
    }

    @Test
    fun `gallery info room`() {
        render("room-2-info") {
            com.fotoxplorr.app.gallery.GalleryInfoRoom(
                title = "Photos",
                assets = emptyList(),
                state = emptyState(),
            )
        }
    }

    @Test
    fun `viewer settings room`() {
        render("room-3-viewer-settings") {
            com.fotoxplorr.app.viewer.ViewerSettingsRoom(
                slideshowIntervalSeconds = 5,
                blurSensitive = true,
                showFilmstrip = true,
                keepScreenOn = false,
                slideshowShuffle = false,
                loopAnimations = true,
                autoplayVideos = false,
                onSetSlideshowInterval = {},
                onSetBlurSensitive = {},
                onSetShowFilmstrip = {},
                onSetKeepScreenOn = {},
                onSetSlideshowShuffle = {},
                onSetLoopAnimations = {},
                onSetAutoplayVideos = {},
            )
        }
    }

    /** The Hyle control gallery on a light card, the way the owner's control-sheet draws it. */
    @Test
    fun `hyle toggle gallery`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color(0xFFF4F4F5))) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.Center)
                            .padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(28.dp),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        com.fotoxplorr.app.hyle.HyleToggle(checked = false, onCheckedChange = {})
                        com.fotoxplorr.app.hyle.HyleToggle(checked = true, onCheckedChange = {})
                        com.fotoxplorr.app.hyle.HyleToggle(
                            checked = false, onCheckedChange = {}, glyphs = '#' to '*',
                        )
                        com.fotoxplorr.app.hyle.HyleToggle(
                            checked = true, onCheckedChange = {}, glyphs = '#' to '*',
                        )
                        com.fotoxplorr.app.hyle.HyleToggle(
                            checked = false, onCheckedChange = {}, enabled = false,
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/hyle-toggle.png")
    }

    /** The Hyle field states, the way the owner's control-sheet lays them out. */
    @Test
    fun `hyle field gallery`() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color(0xFFFFFFFF))) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.Center)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
                    ) {
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(error = true),
                        ) { FieldLabel("Error / Invalid Input (Not Selected)") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(error = true, focused = true, mandatory = true),
                        ) { FieldLabel("Error / Invalid Input & Mandatory (Selected)") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(error = true, focused = true),
                        ) { FieldLabel("Error / Invalid Input (Selected)") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(focused = true, mandatory = true),
                        ) { FieldLabel("Selected & Mandatory") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(focused = true),
                        ) { FieldLabel("Selected") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(),
                        ) { FieldLabel("Not Selected") }
                        com.fotoxplorr.app.hyle.HyleField(
                            style = com.fotoxplorr.app.hyle.HyleFieldStyle(enabled = false),
                        ) { FieldLabel("Disabled", faint = true) }
                        com.fotoxplorr.app.hyle.HyleTextField(
                            value = "sunset at the coast",
                            onValueChange = {},
                            leading = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Color(0xFF3A3A44))
                            },
                        )
                        com.fotoxplorr.app.hyle.HyleTextField(
                            value = "",
                            onValueChange = {},
                            placeholder = "Search names, albums, types and tags",
                            leading = {
                                Icon(Icons.Outlined.Search, contentDescription = null, tint = Color(0xFF3A3A44))
                            },
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/hyle-field.png")
    }
}

@androidx.compose.runtime.Composable
private fun FieldLabel(text: String, faint: Boolean = false) {
    androidx.compose.material3.Text(
        text = text,
        color = if (faint) Color(0xFFB9B9BE) else Color(0xFF2A2A30),
        style = androidx.compose.ui.text.TextStyle(
            fontFamily = com.fotoxplorr.app.ui.HyleGrotesk,
            fontSize = 17.sp,
        ),
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** Every action, wired to nothing. Renders need the shape, not the behaviour. */
private fun noOpActions() = com.fotoxplorr.app.gallery.GalleryActions(
        onRequestPermission = { Unit },
        onRefresh = { Unit },
        onSetSort = { _ -> Unit },
        onSetGridColumns = { _ -> Unit },
        onSetBlurSensitive = { _ -> Unit },
        onSetHideSensitive = { _ -> Unit },
        onSetShowVideos = { _ -> Unit },
        onSetTimelineGrouping = { _ -> Unit },
        onSetThemeMode = { _ -> Unit },
        onSetAccentPalette = { _ -> Unit },
        onSetSlideshowInterval = { _ -> Unit },
        onSetDefaultDestination = { _ -> Unit },
        onSetKeepScreenOn = { _ -> Unit },
        onSetSlideshowShuffle = { _ -> Unit },
        onSetAutoplayVideos = { _ -> Unit },
        onSetFitToTile = { _ -> Unit },
        onSetLoopAnimations = { _ -> Unit },
        onSetLongPressPreview = { _ -> Unit },
        onIndexRecognition = { Unit },
        onProtectFolder = { _, _ -> Result.success(Unit) },
        onUnlockFolder = { _, _ -> false },
        onLockFolder = { _ -> Unit },
        onRemoveFolderProtection = { _, _ -> false },
        onSetFavorite = { _, _ -> Unit },
        onSetSensitive = { _, _ -> Unit },
        onSetArchived = { _, _ -> Unit },
        onShare = { _ -> Unit },
        onShareClean = { _ -> Unit },
        onCopyToFolder = { _ -> Unit },
        onMoveToFolder = { _ -> Unit },
        onRenameAsset = { _, _ -> Unit },
        onMoveToTrash = { _ -> Unit },
        onRestore = { _ -> Unit },
        onDeletePermanently = { _ -> Unit },
        onCreateCollection = { _ -> null },
        onRenameCollection = { _, _ -> Unit },
        onDeleteCollection = { _ -> Unit },
        onAddToCollection = { _, _ -> Unit },
        onRemoveFromCollection = { _, _ -> Unit },
        onAddTag = { _, _ -> Unit },
        onRemoveTag = { _, _ -> Unit },
        onExportZip = { _ -> Unit },
        onExportMetadata = { Unit },
        onImportMetadata = { Unit },
        onOpenAsset = { _, _ -> Unit },
        onStartSlideshow = { _ -> Unit },
        onPendingSearchConsumed = {},
        onRejectArchiveSuggestions = { _ -> Unit },
)

/** A library with nothing in it: these renders are about chrome, not content. */
private fun emptyState() = com.fotoxplorr.app.gallery.GalleryUiState(
    assets = emptyList(),
    favoriteIds = emptySet(),
    sensitiveIds = emptySet(),
    lockedFolders = emptySet(),
    unlockedFolders = emptySet(),
    library = com.fotoxplorr.app.organize.LibraryState(),
    permissionGranted = true,
    scanState = com.fotoxplorr.app.ScanState.Complete(total = 22_310),
    preferences = GalleryPreferencesState(),
)
