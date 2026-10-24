package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.fotoxplorr.app.backup.BackupFlow
import com.fotoxplorr.app.backup.BackupStage
import com.fotoxplorr.app.backup.FakePhone
import com.fotoxplorr.app.backup.RestoreChooseDialog
import com.fotoxplorr.app.backup.RestoreCopy
import com.fotoxplorr.app.backup.RestoreFixtures
import com.fotoxplorr.app.backup.RestoreReviewDialog
import com.fotoxplorr.app.backup.RestoreScope
import com.fotoxplorr.app.backup.RestoreSet
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The two restore cards on the real theme: what a person reads and which buttons can be pressed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class RestoreDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir = Files.createTempDirectory("fotoz-restoredialogs-test").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun flow() = BackupFlow(RuntimeEnvironment.getApplication(), scope, jobRunner = { null })

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
    }

    @Test
    fun `the first card describes the backup and offers both choices, everything chosen`() {
        val set = RestoreFixtures.backup(dir, count = 4).set()
        val stage = BackupStage.RestoreChoose(set, "Photos backup")
        show { RestoreChooseDialog(flow(), stage) }

        compose.onNodeWithText("Restore from Photos backup").assertExists()
        compose.onNodeWithText("4 photos", substring = true).assertExists()
        compose.onNodeWithText("Tags and collections:", substring = true).assertExists()
        compose.onNodeWithText(RestoreCopy.OPTION_EVERYTHING).assertIsSelected().assertIsEnabled()
        compose.onNodeWithText(RestoreCopy.OPTION_TAGS).assertIsEnabled()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `the tags choice can be picked`() {
        val stage = BackupStage.RestoreChoose(RestoreFixtures.backup(dir, count = 3).set(), "b")
        show { RestoreChooseDialog(flow(), stage) }
        compose.onNodeWithText(RestoreCopy.OPTION_TAGS).performClick()
        compose.onNodeWithText(RestoreCopy.OPTION_TAGS).assertIsSelected()
        compose.onNodeWithText(RestoreCopy.OPTION_EVERYTHING).assertIsEnabled()
    }

    @Test
    fun `a backup with no tags section cannot be restored as tags only`() {
        val full = RestoreFixtures.backup(dir, count = 3).set()
        val noTags = RestoreSet(full.entries, null)
        show { RestoreChooseDialog(flow(), BackupStage.RestoreChoose(noTags, "b")) }

        compose.onNodeWithText(RestoreCopy.OPTION_TAGS).assertIsNotEnabled()
        compose.onNodeWithText(RestoreCopy.NO_TAGS_IN_BACKUP).assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun `the review says what will happen and its button restores`() {
        val made = RestoreFixtures.backup(dir, count = 4)
        val phone = FakePhone()
        phone.put(700, "IMG_1.jpg", "DCIM/Camera/", made.source.bytes(1))
        val stage = BackupStage.RestoreReview(RestoreFixtures.prepare(made.set(), phone), "b", freeBytes = 50_000_000)
        show { RestoreReviewDialog(flow(), stage, requestLocation = {}) }

        compose.onNodeWithText("Restore to this phone").assertExists()
        compose.onNodeWithText("3 files", substring = true).assertExists()
        compose.onNodeWithText("1 already is on this phone", substring = true).assertExists()
        compose.onNodeWithText(RestoreCopy.NEVER_OVERWRITES).assertExists()
        compose.onNodeWithText("Restore").assertIsEnabled()
        compose.onNodeWithText("Back").assertIsEnabled()
    }

    @Test
    fun `a review with nothing to do has no button to press`() {
        val made = RestoreFixtures.backup(dir, count = 2, sections = { emptyList() })
        val phone = FakePhone()
        made.candidates.forEach { phone.put(700L + it.mediaId, it.displayName, it.relativePath!!, made.source.bytes(it.mediaId)) }
        val prep = RestoreFixtures.prepare(made.set(), phone)
        show { RestoreReviewDialog(flow(), BackupStage.RestoreReview(prep, "b", null), requestLocation = {}) }

        compose.onNodeWithText("No photo needs to be copied back.").assertExists()
        compose.onNodeWithText("Restore").assertIsNotEnabled()
    }

    @Test
    fun `the tags-only review is titled and worded for tags`() {
        val made = RestoreFixtures.backup(dir, count = 3)
        val phone = FakePhone()
        made.candidates.forEachIndexed { i, c -> phone.put(500L + i, c.displayName, c.relativePath!!, made.source.bytes(c.mediaId), taken = c.dateTakenMillis) }
        val prep = RestoreFixtures.prepare(made.set(), phone, RestoreScope.TAGS_ONLY)
        show { RestoreReviewDialog(flow(), BackupStage.RestoreReview(prep, "b", null), requestLocation = {}) }

        compose.onNodeWithText("Add tags and collections").assertExists()
        compose.onNodeWithText("Add").assertIsEnabled()
        compose.onNodeWithText(RestoreCopy.ONLY_WITH_EVERYTHING).assertExists()
    }
}
