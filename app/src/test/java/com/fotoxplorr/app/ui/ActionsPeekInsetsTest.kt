package com.fotoxplorr.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.fotoxplorr.app.gallery.GalleryActionsRoom
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.GallerySelection
import com.fotoxplorr.app.render.emptyState
import com.fotoxplorr.app.render.noOpActions
import dev.aarso.cellshell.RoomStage
import dev.aarso.cellshell.SpatialController
import dev.aarso.cellshell.SpatialShell
import dev.aarso.cellshell.rememberSpatialController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The actions room's peek with a navigation bar present. The shell adds the bar's height to the
 * peek, so at the peek that much more of the room than the strip is on screen; the frame fills it
 * with a black slice under the strip, so the first rows of the scrolling list do not show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ActionsPeekInsetsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: SpatialController

    private val navBarDp = 48

    private fun showGalleryRoomWithNavigationBar() {
        // Edge to edge, as the app is: otherwise the decor view takes the bar's insets as padding
        // and the composition below it never sees them.
        compose.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false) }
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                controller = rememberSpatialController()
                SpatialShell(
                    controller = controller,
                    accentColor = Color.White,
                    scrimColor = Color.Black,
                    cardColor = Color.Black,
                    modifier = Modifier.fillMaxSize(),
                    bottom = {
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
                    bottomPeek = RoomGeography.peekFor(RoomKind.ACTIONS),
                ) { Box(Modifier.fillMaxSize().background(Color.DarkGray)) }
            }
        }
        compose.waitForIdle()
        val px = (navBarDp * compose.density.density).toInt()
        compose.runOnUiThread {
            // Straight to the composition's own view: Robolectric's window never lays out a real
            // insets pass, so nothing would otherwise carry the bar down from the decor.
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            fun deliver(view: View) {
                if (view.javaClass.simpleName == "AndroidComposeView") ViewCompat.dispatchApplyWindowInsets(view, insets)
                if (view is ViewGroup) for (i in 0 until view.childCount) deliver(view.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        compose.waitForIdle()
    }

    @Test
    fun `with a navigation bar the slice under the strip is black and the list stays below the screen`() {
        showGalleryRoomWithNavigationBar()
        compose.runOnIdle { controller.peekBottom() }
        compose.waitForIdle()
        assertEquals(RoomStage.PEEK, controller.bottomStage)

        val navPx = navBarDp * compose.density.density
        val screen = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        val strip = compose.onNodeWithTag(QUICK_ACTION_STRIP_TAG).fetchSemanticsNode().boundsInRoot
        val spacer = compose.onNodeWithTag(ACTIONS_PEEK_NAV_SPACER_TAG).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText("ORGANISE").fetchSemanticsNode().boundsInRoot

        assertTrue("the navigation bar was not applied: spacer is ${spacer.height}px", spacer.height >= navPx - 1f)
        assertTrue("the strip is cut off: bottom ${strip.bottom} of $screen", strip.bottom <= screen - navPx + 1f)
        assertTrue("the spacer does not start at the strip's foot", kotlin.math.abs(spacer.top - strip.bottom) <= 1f)
        assertTrue("the list shows under the strip (top ${row.top} of $screen)", row.top >= screen - 1f)
    }
}
