package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.fotoxplorr.app.backup.BackupCopy
import com.fotoxplorr.app.backup.RestoreCopy
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.SettingsTab
import com.fotoxplorr.app.gallery.SettingsTabsRoom
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.app.ui.HelpCopy
import com.fotoxplorr.app.ui.PaneScaffold
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Data with both backup options, on the real theme. The two are told apart on the page
 * itself: the plain file says it is not encrypted, the encrypted one says there is no recovery.
 * The rows come from a self-contained composable, so the page needs nothing new from its host.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class BackupSettingsRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun dataTab() {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    PaneScaffold(title = "Settings", onBack = {}) {
                        SettingsTabsRoom(
                            state = emptyState(),
                            actions = noOpActions(),
                            onOpenLegacyScreen = {},
                            onOpenSupport = {},
                            onOpenMoreApps = {},
                            initialTab = SettingsTab.DATA,
                            showHeader = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the data tab offers the plain file and the encrypted backup as two named options`() {
        dataTab()
        compose.onRoot().captureRoboImage("build/renders/settings-data-backup.png")
        compose.onNodeWithText("BACKUP: TAGS AND COLLECTIONS").assertIsDisplayed()
        compose.onNodeWithText("Save tags and collections to a file").assertIsDisplayed()
        compose.onNodeWithText(HelpCopy.Hint.BACKUP_NOT_ENCRYPTED).assertIsDisplayed()

        compose.onNodeWithText(BackupCopy.SECTION_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BackupCopy.OPTION_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(BackupCopy.CHECK_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(RestoreCopy.ROW_LABEL).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HelpCopy.Hint.BACKUP_RESTORE_ENCRYPTED).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the no-recovery sentence is on the page before anyone taps anything`() {
        dataTab()
        compose.onNodeWithText(HelpCopy.Note.BACKUP_NO_RECOVERY).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HelpCopy.Hint.BACKUP_ENCRYPTED_ADDS).performScrollTo().assertIsDisplayed()
        // Said once on this page, not repeated per row.
        compose.onAllNodesWithText(HelpCopy.Note.BACKUP_NO_RECOVERY).assertCountEquals(1)
    }
}
