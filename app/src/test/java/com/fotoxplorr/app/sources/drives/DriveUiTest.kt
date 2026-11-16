package com.fotoxplorr.app.sources.drives

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.sources.ui.DriveOfferActions
import com.fotoxplorr.app.sources.ui.DriveOfferCard
import com.fotoxplorr.app.sources.ui.StorageCopy
import com.fotoxplorr.app.sources.ui.StoragePanel
import com.fotoxplorr.app.sources.ui.StoragePanelState
import com.fotoxplorr.app.sources.ui.StorageRow
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class DriveUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = ArrayList<String>()
    private val volume = DriveVolume("1234-ABCD", "Holiday SD")
    private val actions = DriveOfferActions({ calls += "add" }, { calls += "notNow" }, { calls += "never" })

    private fun row(name: String = "Camera", online: Boolean = true, previews: String? = null, protectedLabel: String? = null, problems: List<String> = emptyList()) =
        StorageRow(1, name, online, if (online) "Online" else "Offline", "1200 files", "3.4 GB", previews, "Last read today.", problems, protectedLabel)

    private fun panel(vararg rows: StorageRow, placeholder: String? = null, ignored: List<IgnoredDrive> = emptyList()) =
        StoragePanelState("${rows.size} folders, 1 online, 1200 files.", "3.4 GB", rows.toList(), placeholder, ignored)

    @Test
    fun `the card says what happened and offers three labelled 48 dp answers`() {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { DriveOfferCard(volume, actions) } }
        compose.onNodeWithText(DriveCopy.OFFER_TITLE).assertIsDisplayed()
        compose.onNodeWithText(DriveCopy.OFFER_BODY).assertIsDisplayed()
        compose.onNodeWithText("Holiday SD").assertIsDisplayed()
        compose.onNodeWithContentDescription(DriveCopy.offerA11y("Holiday SD")).assertExists()
        listOf(DriveCopy.addA11y("Holiday SD"), DriveCopy.notNowA11y("Holiday SD"), DriveCopy.neverA11y("Holiday SD")).forEach {
            compose.onNodeWithContentDescription(it).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun `each button reports its own answer and nothing else`() {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { DriveOfferCard(volume, actions) } }
        compose.onNodeWithText(DriveCopy.ADD).performClick()
        compose.onNodeWithText(DriveCopy.NOT_NOW).performClick()
        compose.onNodeWithText(DriveCopy.NEVER).performClick()
        assertEquals(listOf("add", "notNow", "never"), calls)
    }

    @Test
    fun `the panel shows an online and an offline source with words for state and the placeholder for protection`() {
        val state = panel(row("Camera"), row("Backup stick", online = false), placeholder = StorageCopy.PROTECTION_PENDING)
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { StoragePanel(state, onAskAgain = { calls += it }) } }
        compose.onNodeWithText(StorageCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithText("2 folders, 1 online, 1200 files. 3.4 GB.").assertIsDisplayed()
        compose.onNodeWithText("Online").assertIsDisplayed()
        compose.onNodeWithText("Offline").assertIsDisplayed()
        compose.onAllNodesWithText("1200 files, 3.4 GB").assertCountEquals(2)
        compose.onAllNodesWithText(StorageCopy.PREVIEWS_NONE).assertCountEquals(2)
        compose.onNodeWithText(StorageCopy.PROTECTION_PENDING).assertIsDisplayed()
    }

    @Test
    fun `the panel shows preview cost, protection and errors when it has them`() {
        val state = panel(row(previews = "Offline previews use 12 MB.", protectedLabel = "Protected on 2 places.", problems = listOf("3 files could not be read.")))
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { StoragePanel(state, onAskAgain = {}) } }
        compose.onNodeWithText("Offline previews use 12 MB.").assertIsDisplayed()
        compose.onNodeWithText("Protected on 2 places.").assertIsDisplayed()
        compose.onNodeWithText("3 files could not be read.").assertIsDisplayed()
        compose.onAllNodesWithText(StorageCopy.PROTECTION_PENDING).assertCountEquals(0)
    }

    @Test
    fun `an empty panel says so and an ignored drive can be asked about again`() {
        val state = panel(ignored = listOf(IgnoredDrive("1234-ABCD", "Holiday SD")))
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { StoragePanel(state, onAskAgain = { calls += it }) } }
        compose.onNodeWithText(StorageCopy.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(StorageCopy.IGNORED_TITLE).assertIsDisplayed()
        compose.onNodeWithContentDescription(StorageCopy.askAgainA11y("Holiday SD")).assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("1234-ABCD"), calls)
    }
}
