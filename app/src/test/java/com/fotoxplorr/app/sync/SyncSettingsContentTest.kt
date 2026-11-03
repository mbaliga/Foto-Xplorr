package com.fotoxplorr.app.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SyncSettingsContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val folders = listOf(SyncFolderChoice("dcim", "Camera", 120), SyncFolderChoice("pics", "Pictures", 8))

    private fun show(service: SyncService, picked: String? = null, onPick: () -> Unit = {}) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SyncSettingsContent(service, folders, onPick, pickedFolderUri = picked)
            }
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private val folderConfig = SyncDestinationConfig(
        id = "f", kind = SyncDestinationKind.FOLDER, displayName = "Backup",
        folderTreeUri = "content://x/tree/primary%3ABackup", selection = SyncSelection(allPhotos = true),
    )

    @Test
    fun `starts off with the plain explanation and nothing called`() {
        val service = FakeSyncService()
        show(service)
        compose.onNodeWithText("Sync is off").assertExists()
        compose.onNodeWithText(SyncCopy.OFF_EXPLAINED).assertExists()
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `offline offers only the folder and connect offers all`() {
        show(FakeSyncService(supportedKinds = setOf(SyncDestinationKind.FOLDER)))
        compose.onNodeWithTag("sync-kind-folder").assertExists()
        compose.onAllNodesWithTagCount("sync-kind-webdav", 0)
        compose.onAllNodesWithTagCount("sync-kind-s3", 0)
        compose.onNodeWithText(SyncCopy.NEEDS_CONNECT_BUILD).assertExists()
    }

    @Test
    fun `connect shows all three and the not yet built ones disabled`() {
        show(FakeSyncService())
        compose.onNodeWithTag("sync-kind-folder").assertExists()
        compose.onNodeWithTag("sync-kind-webdav").assertExists()
        compose.onNodeWithTag("sync-kind-s3").assertExists()
        compose.onNodeWithTag("sync-soon-gdrive").assertIsNotEnabled()
        compose.onNodeWithTag("sync-soon-dropbox").assertIsNotEnabled()
        compose.onNodeWithText(SyncCopy.KIND_DRIVE).assertExists()
    }

    @Test
    fun `webdav form shows errors after a try and the address preview`() {
        show(FakeSyncService())
        tap("sync-kind-webdav")
        type("sync-webdav-url", "cloud.example.org/dav")
        compose.onNodeWithText("Fotoz will use https://cloud.example.org/dav/").assertExists()
        tap("sync-test")
        compose.onNodeWithText(SyncCopy.WEBDAV_NEED_USER).assertExists()
        compose.onNodeWithText(SyncCopy.NEED_PASSWORD).assertExists()
        compose.onNodeWithText(SyncCopy.FIX_DETAILS_FIRST, substring = true).assertExists()
    }

    @Test
    fun `plain http warning appears only when allowed`() {
        show(FakeSyncService())
        tap("sync-kind-webdav")
        compose.onAllNodesWithTagCount("sync-webdav-http", 1)
        compose.onNodeWithText(SyncCopy.PLAIN_HTTP_WARNING).assertDoesNotExist()
        tap("sync-webdav-http")
        compose.onNodeWithText(SyncCopy.PLAIN_HTTP_WARNING).assertExists()
    }

    @Test
    fun `test connection shows the providers sentence and sends the typed secret once`() {
        val service = FakeSyncService()
        service.nextTestResult = Result.success("Connected to cloud.example.org.")
        show(service)
        tap("sync-kind-webdav")
        type("sync-webdav-url", "cloud.example.org/dav")
        type("sync-webdav-user", "ann")
        type("sync-secret", "app-pass")
        tap("sync-test")
        compose.waitForIdle()
        compose.onNodeWithText("Connected to cloud.example.org.").assertExists()
        assertEquals(1, service.calls.count { it.startsWith("test:") })
    }

    @Test
    fun `a failed test shows the failure sentence`() {
        val service = FakeSyncService()
        service.nextTestResult = Result.failure(SyncException(SyncFailure.AUTH, SyncCopy.failureSentence(SyncFailure.AUTH)))
        show(service)
        tap("sync-kind-webdav")
        type("sync-webdav-url", "cloud.example.org")
        type("sync-webdav-user", "ann")
        type("sync-secret", "wrong")
        tap("sync-test")
        compose.waitForIdle()
        compose.onNodeWithText(SyncCopy.failureSentence(SyncFailure.AUTH)).assertExists()
    }

    @Test
    fun `folder kind shows the chosen folder and the choose button calls the picker`() {
        var picks = 0
        show(FakeSyncService(), picked = "content://x/tree/primary%3ADCIM%2FBackup", onPick = { picks++ })
        tap("sync-kind-folder")
        compose.waitForIdle()
        compose.onNodeWithText("Backup").assertExists()
        tap("sync-choose-folder")
        assertEquals(1, picks)
    }

    @Test
    fun `send is disabled with the exact reason until something is chosen`() {
        val service = FakeSyncService()
        show(service, picked = "content://x/tree/primary%3ABackup")
        tap("sync-kind-folder")
        compose.waitForIdle()
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(SyncStartRefusal.NOTHING_CHOSEN.sentence).assertExists()
        tap("sync-all")
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `encrypting needs matching passphrases`() {
        show(FakeSyncService(), picked = "content://x/tree/primary%3ABackup")
        tap("sync-kind-folder")
        tap("sync-all")
        tap("sync-encrypt")
        compose.onNodeWithText(SyncCopy.ENCRYPT_NAMES_VISIBLE).assertExists()
        compose.onNodeWithText(SyncCopy.ENCRYPT_NO_RECOVERY).assertExists()
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(SyncCopy.ENCRYPT_PASSPHRASE_NEEDED).assertExists()
        type("sync-passphrase", "long enough pass")
        type("sync-passphrase-confirm", "long enough pasx")
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(SyncCopy.PASSPHRASE_MISMATCH, substring = true).assertExists()
        compose.onNodeWithTag("sync-passphrase-confirm").performScrollTo().performTextInput("") // no-op focus
    }

    @Test
    fun `matching passphrases enable send and start gets them`() {
        val service = FakeSyncService()
        show(service, picked = "content://x/tree/primary%3ABackup")
        tap("sync-kind-folder")
        tap("sync-all")
        tap("sync-encrypt")
        type("sync-passphrase", "long enough pass")
        type("sync-passphrase-confirm", "long enough pass")
        compose.onNodeWithTag("sync-send").performScrollTo().assertIsEnabled()
        tap("sync-send")
        compose.waitForIdle()
        assertTrue(service.calls.any { it.startsWith("save:") })
        assertTrue(service.calls.contains("start:mobile=false"))
    }

    @Test
    fun `preview shows files bytes and already there`() {
        val service = FakeSyncService(initial = folderConfig)
        service.nextPreview = Result.success(SyncPreview(12, 1_500_000_000L, 7, 0, 0, 0))
        show(service)
        tap("sync-preview")
        compose.waitForIdle()
        compose.onNodeWithText("12 files, 1.5 GB to send, 7 already there").assertExists()
    }

    @Test
    fun `waiting for wifi offers one run on mobile data`() {
        val service = FakeSyncService(initial = folderConfig)
        service.nextStart = SyncStartResult.Refused(SyncStartRefusal.WAITING_FOR_WIFI)
        show(service)
        tap("sync-send")
        compose.waitForIdle()
        compose.onNodeWithText(SyncCopy.ALLOW_MOBILE_ONCE).assertExists()
        service.nextStart = SyncStartResult.Started
        compose.onNodeWithText(SyncCopy.ALLOW_MOBILE_ONCE).performClick()
        compose.waitForIdle()
        assertTrue(service.calls.contains("start:mobile=true"))
    }

    @Test
    fun `another refusal is said in words`() {
        val service = FakeSyncService(initial = folderConfig)
        service.nextStart = SyncStartResult.Refused(SyncStartRefusal.NO_NETWORK)
        show(service)
        tap("sync-send")
        compose.waitForIdle()
        compose.onNodeWithText(SyncCopy.NO_NETWORK).assertExists()
    }

    @Test
    fun `running shows progress, the notification note and pause`() {
        val service = FakeSyncService(initial = folderConfig)
        show(service)
        service.setState(SyncRunState.Running(12, 340, 1_200_000_000L, 0, 4_500_000_000L, SyncTally(uploaded = 12)))
        compose.waitForIdle()
        compose.onNodeWithText("12 of 340 files, 1.2 GB of 4.5 GB").assertExists()
        compose.onNodeWithText(SyncCopy.RUNNING_NOTE).assertExists()
        tap("sync-pause")
        assertTrue(service.calls.contains("pause"))
        compose.waitForIdle()
        compose.onNodeWithTag("sync-outcome").assertExists()
    }

    @Test
    fun `finished and failed show the tally and the failure sentence`() {
        val service = FakeSyncService(initial = folderConfig)
        show(service)
        service.setState(SyncRunState.Finished(SyncTally(uploaded = 3, upToDate = 2)))
        compose.waitForIdle()
        compose.onNodeWithText("Sent: 3 (0 B)").assertExists()
        service.setState(SyncRunState.Failed(SyncFailure.NO_SPACE, "", SyncTally(uploaded = 1)))
        compose.waitForIdle()
        compose.onNodeWithText(SyncCopy.failureSentence(SyncFailure.NO_SPACE), substring = true).assertExists()
    }

    @Test
    fun `forget asks first, says it deletes nothing, then forgets`() {
        val service = FakeSyncService(initial = folderConfig)
        show(service)
        tap("sync-forget")
        compose.onNodeWithText(SyncCopy.FORGET_DELETES_NOTHING).assertExists()
        compose.onNodeWithText(SyncCopy.CANCEL).performClick()
        assertTrue(service.calls.none { it == "forget" })
        tap("sync-forget")
        compose.onNodeWithText(SyncCopy.FORGET_CONFIRM).performClick()
        compose.waitForIdle()
        assertTrue(service.calls.contains("forget"))
        compose.onNodeWithText("Sync is off").assertExists()
    }

    @Test
    fun `a saved destination is shown as the chosen card`() {
        show(FakeSyncService(initial = folderConfig))
        compose.onNodeWithText("Ready to send").assertExists()
        compose.onNodeWithTag("sync-kind-folder").assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, expected: Int) {
    assertEquals(expected, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
