package com.fotoxplorr.app.experimental

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import com.fotoxplorr.app.spatial.GeoIndexState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The host on screen: Back closes an open experiment and does not leave the destination, a saved
 * id is reopened only when it may be, an entry that cannot run cannot be opened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class ExperimentalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** A dispatcher the test can press Back on, and ask whether anything is registered to take it. */
    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also {
            it.currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private fun entry(
        id: String,
        restorable: Boolean = true,
        available: Boolean = true,
        content: @Composable (ExperimentEnv, () -> Unit) -> Unit = { _, _ -> Text("inside $id") },
    ) = ExperimentEntry(
        id = id,
        title = "Title $id",
        blurb = "Blurb $id",
        group = ExperimentGroup.EXPLORE,
        icon = Icons.Outlined.Science,
        restorable = restorable,
        available = { available },
        content = content,
    )

    private val owner = BackOwner()

    @Composable
    private fun Host(
        entries: List<ExperimentEntry>,
        assets: List<MediaAsset> = emptyList(),
        sensitiveIds: Set<MediaId> = emptySet(),
        blurSensitive: Boolean = true,
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                ExperimentalScreen(
                    assets = assets,
                    indexInput = emptyList(),
                    geoState = GeoIndexState(),
                    onIndexLocations = {},
                    onOpenAsset = { _, _ -> },
                    entries = entries,
                    sensitiveIds = sensitiveIds,
                    blurSensitive = blurSensitive,
                )
            }
        }
    }

    private fun photo(id: Long) = MediaAsset(
        id = MediaId(id),
        contentUriString = "content://media/external/images/media/$id",
        displayName = "p$id.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = id,
        dateModifiedSeconds = id,
        width = 4,
        height = 4,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    private fun pressBack() = compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `the hub lists a card per entry under its group heading`() {
        compose.setContent { Host(listOf(entry("a"), entry("b"))) }
        compose.onNodeWithText("Title a").assertExists()
        compose.onNodeWithText("Title b").assertExists()
        compose.onNodeWithText(ExperimentalCopy.GROUP_EXPLORE).assertExists()
        compose.onNodeWithText("inside a").assertDoesNotExist()
        // On the hub nothing takes Back, so the destination's own Back order is what runs.
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `Back closes an open experiment and lands on the hub, not out of the section`() {
        compose.setContent { Host(listOf(entry("a"), entry("b"))) }
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").assertExists()
        assertTrue("the host takes Back while an experiment is open", owner.onBackPressedDispatcher.hasEnabledCallbacks())

        pressBack()
        compose.waitForIdle()

        compose.onNodeWithText("inside a").assertDoesNotExist()
        compose.onNodeWithText("Title a").assertExists()
        assertFalse("and gives it back once the hub is showing", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `an experiment that closes itself returns to the hub`() {
        compose.setContent {
            Host(listOf(entry("a") { _, onClose -> Text("inside a", Modifier.clickable(onClick = onClose)) }))
        }
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").performClick()
        compose.onNodeWithText("inside a").assertDoesNotExist()
    }

    @Test
    fun `an experiment with an inner step is asked first and keeps the host's Back for the next press`() {
        var step by mutableStateOf(1)
        compose.setContent {
            Host(
                listOf(
                    entry("a") { _, _ ->
                        BackHandler(enabled = step > 1) { step -= 1 }
                        Text("inside a step $step")
                    },
                ),
            )
        }
        compose.onNodeWithText("Title a").performClick()
        compose.runOnUiThread { step = 2 }
        compose.onNodeWithText("inside a step 2").assertExists()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("inside a step 1").assertExists()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("Title a").assertExists()
        compose.onNodeWithText("inside a step 1").assertDoesNotExist()
    }

    @Test
    fun `an entry this device cannot run says so and cannot be opened`() {
        compose.setContent { Host(listOf(entry("a", available = false), entry("b"))) }
        compose.onNodeWithText("Title a").assertIsNotEnabled()
        compose.onNodeWithText(ExperimentalCopy.UNAVAILABLE).assertExists()
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").assertDoesNotExist()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `a restorable experiment is open again after the activity is recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host(listOf(entry("a"), entry("b"))) }
        compose.onNodeWithText("Title b").performClick()
        compose.onNodeWithText("inside b").assertExists()

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("inside b").assertExists()
    }

    @Test
    fun `an experiment that is not restorable comes back as the hub`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Host(listOf(entry("a"), entry("cam", restorable = false))) }
        compose.onNodeWithText("Title cam").performClick()
        compose.onNodeWithText("inside cam").assertExists()

        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("inside cam").assertDoesNotExist()
        compose.onNodeWithText("Title cam").assertExists()
    }

    @Test
    fun `a saved id this build does not know shows the hub`() {
        val tester = StateRestorationTester(compose)
        var entries = listOf(entry("a"), entry("b"))
        // Read when the content is composed, so the second composition sees the shorter list while
        // the saver (made by the first) still saves "b".
        tester.setContent { Host(entries) }
        compose.onNodeWithText("Title b").performClick()
        compose.onNodeWithText("inside b").assertExists()

        entries = listOf(entry("a"))
        tester.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("inside b").assertDoesNotExist()
        compose.onNodeWithText("Title a").assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // The shell's room (nav or options) is opened with an edge drag over an open experiment and is
    // not a back-stack entry: the gallery's one Back handler closes it. That handler is composed
    // before the experiment, and the dispatcher asks the last one registered first, so without
    // RoomFirstBackHandler the experiment closed underneath a room that was still open.

    @Test
    fun `Back closes a room opened over an experiment before the experiment`() {
        var roomOpen by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                RoomFirstBackHandler(roomOpen = roomOpen, enabled = roomOpen) { roomOpen = false }
                Host(listOf(entry("a")))
            }
        }
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").assertExists()

        compose.runOnUiThread { roomOpen = true }
        compose.waitForIdle()

        pressBack()
        compose.waitForIdle()
        assertFalse("the room is closed first", roomOpen)
        compose.onNodeWithText("inside a").assertExists()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("inside a").assertDoesNotExist()
        compose.onNodeWithText("Title a").assertExists()
    }

    @Test
    fun `a room is closed before an experiment's own inner step too`() {
        var roomOpen by mutableStateOf(false)
        var step by mutableStateOf(2)
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                RoomFirstBackHandler(roomOpen = roomOpen, enabled = roomOpen) { roomOpen = false }
                Host(
                    listOf(
                        entry("a") { _, _ ->
                            BackHandler(enabled = step > 1) { step -= 1 }
                            Text("inside a step $step")
                        },
                    ),
                )
            }
        }
        compose.onNodeWithText("Title a").performClick()
        compose.runOnUiThread { roomOpen = true }
        compose.waitForIdle()

        pressBack()
        compose.waitForIdle()
        assertFalse(roomOpen)
        compose.onNodeWithText("inside a step 2").assertExists()

        pressBack()
        compose.waitForIdle()
        compose.onNodeWithText("inside a step 1").assertExists()
    }

    @Test
    fun `a room opened a second time is closed first again`() {
        var roomOpen by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                RoomFirstBackHandler(roomOpen = roomOpen, enabled = roomOpen) { roomOpen = false }
                Host(listOf(entry("a")))
            }
        }
        compose.onNodeWithText("Title a").performClick()
        repeat(2) {
            compose.runOnUiThread { roomOpen = true }
            compose.waitForIdle()
            pressBack()
            compose.waitForIdle()
            assertFalse(roomOpen)
            compose.onNodeWithText("inside a").assertExists()
        }
    }

    // "Blur sensitive" is on by default and the grid blurs a flagged photo. An experiment draws
    // photos at its own size and has no blur of its own, so the host does not give it a flagged
    // photo at all while the setting is on: not as the hub's cover, a picker thumbnail, a wall
    // picture, a pin or a node.

    @Test
    fun `an experiment is not given a sensitive photo while Blur sensitive is on, and mayExport refuses it`() {
        var seen: ExperimentEnv? = null
        val spy = entry("a") { env, _ ->
            seen = env
            Text("inside a")
        }
        val newestIsSensitive = listOf(photo(3), photo(2), photo(1))
        compose.setContent { Host(listOf(spy), newestIsSensitive, sensitiveIds = setOf(MediaId(3))) }
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").assertExists()

        val env = seen!!
        assertEquals(listOf(2L, 1L), env.assets.map { it.id.value })
        assertFalse(env.mayExport(photo(3)))
        assertTrue(env.mayExport(photo(2)))
    }

    @Test
    fun `an experiment is given every photo once Blur sensitive is off`() {
        var seen: ExperimentEnv? = null
        val spy = entry("a") { env, _ ->
            seen = env
            Text("inside a")
        }
        compose.setContent { Host(listOf(spy), listOf(photo(3), photo(2)), setOf(MediaId(3)), blurSensitive = false) }
        compose.onNodeWithText("Title a").performClick()
        compose.onNodeWithText("inside a").assertExists()
        assertEquals(listOf(3L, 2L), seen!!.assets.map { it.id.value })
    }
}
