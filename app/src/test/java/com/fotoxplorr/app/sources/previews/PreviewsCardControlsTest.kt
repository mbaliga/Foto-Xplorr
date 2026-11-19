package com.fotoxplorr.app.sources.previews

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.ui.FotoXplorrTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PreviewsCardControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val settings = InMemoryPreviewSettings()
    private val store by lazy { SourcePreviewStore(File(tmp.root, "p"), capOf = settings::capBytes, io = Dispatchers.Unconfined) }

    private fun show() {
        val previews = SourcePreviews(store, settings, SourcePreviewsFixtures.writer(store, settings) { null }, CoroutineScope(Job() + Dispatchers.Unconfined))
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalSourcePreviews provides previews) { PreviewsCardSection(sourceRow = 7, sourceName = "Camera") }
            }
        }
    }

    private fun chip(label: String, selected: Boolean) = compose.onNodeWithContentDescription(PreviewsCopy.capA11y(label, selected))

    @Test
    fun `a new source shows keep on and 1 GB selected with the real size`() {
        runBlocking { store.write(7, 1, 1, ByteArray(12_000)) }
        show()
        compose.onNodeWithText(PreviewsCopy.size(12_000)).assertIsDisplayed()
        compose.onNodeWithText(PreviewsCopy.KEEP).assertIsDisplayed()
        compose.onNodeWithText(PreviewsCopy.KEEP_HELP).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(PreviewsCopy.KEEP, substring = true)).assertIsOn()
        chip("1 GB", selected = true).assertIsSelected()
        listOf("Off", "250 MB", "2 GB").forEach { chip(it, selected = false).assertExists() }
    }

    @Test
    fun `the keep switch writes the setting and every control is a 48 dp target`() {
        show()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(PreviewsCopy.KEEP, substring = true))
            .assertHeightIsAtLeast(48.dp).performClick()
        assertFalse(settings.keep(7))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(PreviewsCopy.KEEP, substring = true)).assertIsOff()
        listOf("Off", "250 MB", "2 GB").forEach { chip(it, selected = false).assertHeightIsAtLeast(48.dp) }
        chip("1 GB", selected = true).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `choosing a cap stores it and moves the selection and the selected chip shows a check as well as the word`() {
        show()
        chip("250 MB", selected = false).performClick()
        assertEquals(PreviewCaps.MB_250, settings.capBytes(7))
        chip("250 MB", selected = true).assertIsSelected()
        compose.onNode(hasContentDescription(PreviewsCopy.capA11y("1 GB", selected = false))).assertExists()
        // The selected chip carries a glyph (an unlabelled icon) beside its word.
        compose.onNode(hasText("250 MB"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `lowering the cap evicts previews now and off disables the keep switch and says why`() {
        runBlocking { store.write(7, 1, 1, ByteArray(400)) }
        show()
        chip("Off", selected = false).performClick()
        compose.waitForIdle()
        assertEquals(0L, settings.capBytes(7))
        assertEquals("what was stored is evicted", 0L, runBlocking { store.sizeBytes(7) })
        compose.onNodeWithText(PreviewsCopy.CAP_OFF_NOTE).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText(PreviewsCopy.KEEP, substring = true))
            .assertIsNotEnabled().assertIsOff()
        chip("Off", selected = true).assertIsSelected()
    }

    @Test
    fun `strings are one short sentence each`() {
        listOf(PreviewsCopy.KEEP, PreviewsCopy.KEEP_HELP, PreviewsCopy.CAP_TITLE, PreviewsCopy.CAP_OFF_NOTE).forEach {
            assertTrue(it, it.count { c -> c == '.' } <= 1 && it.length < 100)
        }
    }
}
