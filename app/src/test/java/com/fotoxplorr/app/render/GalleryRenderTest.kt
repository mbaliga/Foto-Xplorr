package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.DestinationRailPanel
import com.fotoxplorr.app.gallery.GalleryActionsRoom
import com.fotoxplorr.app.gallery.GalleryInfoRoom
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySelection
import com.fotoxplorr.app.gallery.GalleryViewOptionsRoom
import com.fotoxplorr.app.gallery.HyleDestination
import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.hyle.ActivityShade
import com.fotoxplorr.app.hyle.BackgroundActivity
import com.fotoxplorr.app.hyle.FloatingPillControl
import com.fotoxplorr.app.hyle.SEARCH_FAB_MARGIN_BOTTOM
import com.fotoxplorr.app.hyle.SEARCH_FAB_MARGIN_END
import com.fotoxplorr.app.hyle.SearchFab
import com.fotoxplorr.app.hyle.ShadeState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.RoomGeography
import com.fotoxplorr.app.ui.roomSlots
import com.github.takahirom.roborazzi.captureRoboImage
import dev.aarso.cellshell.ParkStyle
import dev.aarso.cellshell.RoomEdge
import dev.aarso.cellshell.SpatialController
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.WheelItem
import dev.aarso.cellshell.rememberSpatialController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gallery's chrome, rendered against the owner's reference screenshots (2026-10) the same
 * way [ScreenRenderTest] renders the shade: real composables, real Compose runtime, no device.
 * Same configuration and the same reason for it — see that class's own KDoc. The qualifier puts
 * one dp on one reference pixel, so every capture can be laid over its screenshot and measured.
 *
 * - `gallery-1-shade-three` / `gallery-2-shade-one` — the collapsed strip, against the three-job
 *   and one-job screenshots. The fractions are chosen to land the fills on the screenshots' own
 *   pixel widths, so a difference in the capture is a difference in the chrome, not in the data.
 * - `gallery-3-shade-notification` — the notification state, which the screenshots do not draw;
 *   kept so the recoloured kinds and the new track are seen in it.
 * - `gallery-4-rail-open` / `gallery-5-rail-mid-reveal` — the left room through the real spatial
 *   shell in its swivel style, parked and half way, against the owner's swivel reference.
 * - `gallery-6-grid-root` — the browsing surface's own chrome over the grid, against the
 *   root-grid screenshot.
 *
 * The grid under all of them is [ReferenceGridStandIn]: Coil cannot decode anything on the JVM,
 * so flat tiles on the screenshots' own geometry (three columns of 144 x 160 on a 4dp gutter)
 * stand in for the photographs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class GalleryRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(name: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Test
    fun `shade collapsed with three jobs`() {
        render("gallery-1-shade-three") {
            ActivityShade(
                activities = listOf(backingUp(), moving(), copying()),
                state = ShadeState.COLLAPSED,
                onStateChange = {},
            ) { ReferenceGridStandIn() }
        }
    }

    @Test
    fun `shade collapsed with one job`() {
        render("gallery-2-shade-one") {
            ActivityShade(
                // 0.39 of the track: the one-job screenshot's 163px of 416.
                activities = listOf(backingUp(done = 4_846)),
                state = ShadeState.COLLAPSED,
                onStateChange = {},
            ) { ReferenceGridStandIn() }
        }
    }

    @Test
    fun `shade notification with three jobs`() {
        render("gallery-3-shade-notification") {
            ActivityShade(
                activities = listOf(backingUp(), moving(), copying()),
                state = ShadeState.NOTIFICATION,
                onStateChange = {},
            ) { ReferenceGridStandIn() }
        }
    }

    @Test
    fun `rail fully revealed`() {
        renderRail("gallery-4-rail-open") { it.open(RoomEdge.LEFT) }
    }

    @Test
    fun `rail mid reveal`() {
        renderRail("gallery-5-rail-mid-reveal") { it.dragH(it.viewport.width * 0.5f, 0f, 1f) }
    }

    @Test
    fun `actions room fully revealed at the bottom`() {
        renderRooms("gallery-7-actions-bottom") { it.open(RoomGeography.Actions) }
    }

    @Test
    fun `actions room mid reveal shows the tilt`() {
        // A short pull, the way a thumb starts one: the grid has only begun to tilt up.
        renderRooms("gallery-8-actions-mid-reveal") { it.dragV(-it.viewport.height * 0.1f, -1f, 0f) }
    }

    @Test
    fun `details room fully revealed at the top`() {
        renderRooms("gallery-9-details-top") { it.open(RoomGeography.Details) }
    }

    @Test
    fun `view options room fully revealed at the right`() {
        renderRooms("gallery-10-view-options-right") { it.open(RoomGeography.ViewOptions) }
    }

    @Test
    fun `grid root chrome`() {
        render("gallery-6-grid-root") {
            ReferenceGridStandIn()
            FloatingPillControl(
                caption = "August 2026",
                onToggleDensity = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            SearchFab(
                onClick = {},
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = SEARCH_FAB_MARGIN_END.dp, bottom = SEARCH_FAB_MARGIN_BOTTOM.dp),
            )
        }
    }

    /**
     * The left room through the real shell, in the swivel style the gallery uses while a room is
     * being peeked. [settle] drives the controller once the shell has measured itself; the test
     * clock then runs whatever it started to completion before the capture.
     */
    private fun renderRail(name: String, settle: (SpatialController) -> Unit) {
        lateinit var controller: SpatialController
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                controller = rememberSpatialController()
                SpatialShell(
                    controller = controller,
                    accentColor = MaterialTheme.colorScheme.primary,
                    scrimColor = Color.Black,
                    cardColor = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    parkStyle = ParkStyle.SWIVEL,
                    left = {
                        DestinationRailPanel(
                            items = railItems,
                            selectedId = HyleDestination.PHOTOS.name,
                            onSelect = {},
                            onOpenSettings = {},
                        )
                    },
                ) { ReferenceGridStandIn() }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { settle(controller) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    /**
     * The three non-navigation rooms through the real shell, on the slots [roomSlots] assigns
     * them, in the swivel style the gallery uses for a peek. Unlike the standalone room renders
     * this shows each room against the shell's own band and parked card, which is what decides
     * whether its content is clipped or hidden behind the card.
     */
    private fun renderRooms(name: String, settle: (SpatialController) -> Unit) {
        lateinit var controller: SpatialController
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                controller = rememberSpatialController()
                val rooms = roomSlots<@Composable () -> Unit>(
                    details = { GalleryInfoRoom(title = "Photos", assets = emptyList(), state = emptyState()) },
                    actions = {
                        GalleryActionsRoom(
                            state = emptyState(),
                            actions = noOpActions(),
                            selection = GallerySelection(),
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
                    },
                    viewOptions = { GalleryViewOptionsRoom(state = emptyState(), actions = noOpActions()) },
                )
                SpatialShell(
                    controller = controller,
                    accentColor = MaterialTheme.colorScheme.primary,
                    scrimColor = Color.Black,
                    cardColor = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    parkStyle = ParkStyle.SWIVEL,
                    left = rooms.left,
                    top = rooms.top,
                    right = rooms.right,
                    bottom = rooms.bottom,
                ) { ReferenceGridStandIn() }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { settle(controller) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    private val railItems = HyleDestination.entries.map { WheelItem(it.name, it.label) }

    // The three-job screenshot's fills, as fractions of its 100px inner tracks: 42, 37 and 36.
    private fun backingUp(done: Int = 5_194) = BackgroundActivity("save", ActivityKind.BACKING_UP, done, 12_366)
    private fun moving() = BackgroundActivity("move", ActivityKind.MOVING, 4_575, 12_366)
    private fun copying() = BackgroundActivity("copy", ActivityKind.COPYING, 4_452, 12_366)
}

/**
 * The screenshots' grid, as flat tiles: three columns of 144 x 160dp on a 4dp gutter, which is
 * what every one of the 2026-10 references draws under its chrome. Muted blocks that read as
 * photographs without pretending to be any, as [PhotoGridStandIn] does on the app's own gutter.
 */
@Composable
internal fun ReferenceGridStandIn() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(30) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(144f / 160f)
                    .background(REFERENCE_TILES[index % REFERENCE_TILES.size]),
            )
        }
    }
}

private val REFERENCE_TILES = listOf(
    Color(0xFF8A6E52), Color(0xFF9C2F2A), Color(0xFF5B6B78),
    Color(0xFFD8CBB8), Color(0xFF3E5570), Color(0xFF2E6FB0),
    Color(0xFF6E5B4E), Color(0xFF9FA3A8), Color(0xFF3A6FA8),
)
