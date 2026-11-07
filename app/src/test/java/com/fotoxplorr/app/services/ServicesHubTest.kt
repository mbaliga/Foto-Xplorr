package com.fotoxplorr.app.services

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

class SyncCardModeTest {
    @Test fun seamDecidesTheSyncCard() {
        assertEquals(SyncCardMode.OPENS_SYNC, syncCardMode(hasSyncContent = true, syncIntegrated = false))
        assertEquals(SyncCardMode.OPENS_SYNC, syncCardMode(hasSyncContent = true, syncIntegrated = true))
        assertEquals(SyncCardMode.COMING, syncCardMode(hasSyncContent = false, syncIntegrated = false))
        assertEquals(SyncCardMode.HIDDEN, syncCardMode(hasSyncContent = false, syncIntegrated = true))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class ServicesHubTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hubShowsTheThreeCardsAndSyncComingByDefault() {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { ServicesHub(onBack = {}) } }
        compose.onNodeWithText(ServicesCopy.KEYS_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.MODELS_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.SYNC_COMING).assertIsDisplayed()
    }

    @Test fun syncCardIsAbsentOnceIntegratedWithoutContent() {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { ServicesHub(onBack = {}, syncIntegrated = true) } }
        compose.onAllNodesWithText(ServicesCopy.SYNC_COMING).assertCountEquals(0)
        compose.onAllNodesWithText(ServicesCopy.SYNC_TITLE).assertCountEquals(0)
    }

    @Test fun pluggedInSyncContentOpensFromItsCard() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) { ServicesHub(onBack = {}, syncContent = { androidx.compose.material3.Text("SYNC BODY") }) }
        }
        compose.onNodeWithText(ServicesCopy.SYNC_BLURB).performClick()
        compose.onNodeWithText("SYNC BODY").assertIsDisplayed()
    }

    @Test fun modelsCardOpensTheCatalogueWithoutStartingAnything() {
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { ServicesHub(onBack = {}) } }
        compose.onNodeWithText(ServicesCopy.MODELS_BLURB).performClick()
        compose.onNodeWithText(ModelCatalogue.IMAGE_EMBEDDER.title).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.STATE_NOT_INSTALLED).assertIsDisplayed()
        // Offline unit-test flavor: no Get, an Import file button and the honest note.
        compose.onNodeWithText(ServicesCopy.IMPORT_FILE).assertIsDisplayed()
    }

    @Test fun startAtModelsLeavesTheHubOnBack() {
        var left = false
        compose.setContent { FotoXplorrTheme(GalleryPreferencesState()) { ServicesHub(onBack = { left = true }, startAt = ServicesPane.MODELS) } }
        compose.onNodeWithText(ModelCatalogue.IMAGE_EMBEDDER.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(left)
    }
}
