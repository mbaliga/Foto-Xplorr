package com.fotoxplorr.app.services

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
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
class ModelRowTest {
    @get:Rule val compose = createComposeRule()
    private val spec = ModelCatalogue.IMAGE_EMBEDDER
    private val calls = mutableListOf<String>()

    private fun show(state: ModelEntryState, canDownload: Boolean) = compose.setContent {
        FotoXplorrTheme(GalleryPreferencesState()) {
            ModelRow(
                entry = ModelEntry(spec, state, canDownload),
                onGet = { calls += "get" }, onCancel = { calls += "cancel" },
                onDelete = { calls += "delete" }, onImport = { calls += "import" },
            )
        }
    }

    @Test fun connectNotInstalledShowsGetImportAndSize() {
        show(ModelEntryState.NotInstalled, canDownload = true)
        compose.onNodeWithText(ServicesCopy.STATE_NOT_INSTALLED).assertIsDisplayed()
        compose.onNodeWithText(spec.enables).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.GET).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ServicesCopy.IMPORT_FILE).performClick()
        assertEquals(listOf("get", "import"), calls)
    }

    @Test fun offlineNotInstalledOffersOnlyImport() {
        show(ModelEntryState.NotInstalled, canDownload = false)
        compose.onNodeWithText(ServicesCopy.IMPORT_FILE).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.OFFLINE_NOTE).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount(ServicesCopy.GET))
    }

    @Test fun downloadingShowsProgressAndCancel() {
        show(ModelEntryState.Downloading(0.42f), canDownload = true)
        compose.onNodeWithText(ServicesCopy.progress(42)).assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.CANCEL).performClick()
        assertEquals(listOf("cancel"), calls)
    }

    @Test fun installedShowsSizeAndDeletes() {
        show(ModelEntryState.Installed(10L * 1024 * 1024, "abcdef0123456789", "user-import"), canDownload = false)
        compose.onNodeWithText("${ServicesCopy.STATE_INSTALLED}, 10.0 MB").assertIsDisplayed()
        compose.onNodeWithText(ServicesCopy.DELETE).performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test fun failedOffersRetry() {
        show(ModelEntryState.Failed("No luck"), canDownload = true)
        compose.onNodeWithText(ServicesCopy.TRY_AGAIN).performClick()
        assertEquals(listOf("get"), calls)
    }

    @Test fun deleteAllAsksFirst() {
        var deleted = 0
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) { StorageFooter("Models use 1.0 MB.", canDeleteAll = true, onDeleteAll = { deleted++ }) }
        }
        compose.onNodeWithText(ServicesCopy.DELETE_ALL).performClick()
        compose.onNodeWithText(ServicesCopy.DELETE_ALL_CONFIRM).assertIsDisplayed()
        assertEquals(0, deleted)
        compose.onNodeWithText(ServicesCopy.CANCEL).performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText(ServicesCopy.DELETE_ALL).performClick()
        compose.onNodeWithText(ServicesCopy.DELETE).performClick()
        assertEquals(1, deleted)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodesWithText(t).fetchSemanticsNodes().size
}
