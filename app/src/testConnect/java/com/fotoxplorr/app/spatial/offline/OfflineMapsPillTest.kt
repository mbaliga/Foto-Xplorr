package com.fotoxplorr.app.spatial.offline

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map's "Save this area offline" pill: present for an area the caps allow, absent otherwise, and a tap only opens the sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class OfflineMapsPillTest {

    @get:Rule
    val compose = createComposeRule()

    private val paris = GeoBox.clamped(2.30, 48.83, 2.38, 48.89)!!
    private val switzerland = GeoBox.clamped(5.96, 45.82, 10.49, 47.81)!!

    private fun mount(view: GeoBox?, points: () -> List<GeoPoint> = { emptyList() }) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) { OfflineMapsPill(view, points) }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a view the caps allow shows the pill, spoken by its label`() {
        mount(paris)
        compose.onNodeWithText(OfflineMapsCopy.PILL).assertIsDisplayed()
        compose.onNodeWithContentDescription(OfflineMapsCopy.PILL).assertIsDisplayed()
    }

    @Test
    fun `a view too big for the caps shows no pill`() {
        mount(switzerland)
        compose.onAllNodesWithText(OfflineMapsCopy.PILL).assertCountEquals(0)
    }

    @Test
    fun `no view yet shows no pill`() {
        mount(null)
        compose.onAllNodesWithText(OfflineMapsCopy.PILL).assertCountEquals(0)
    }

    @Test
    fun `a tap opens the sheet and reads the places only then`() {
        var asked = 0
        mount(paris) { asked++; emptyList() }
        assertEquals(0, asked)

        compose.onNodeWithText(OfflineMapsCopy.PILL).performClick()
        compose.waitForIdle()

        assertTrue(asked >= 1)
        compose.onNodeWithText(OfflineMapsCopy.ROW_LABEL).assertIsDisplayed()
    }
}
