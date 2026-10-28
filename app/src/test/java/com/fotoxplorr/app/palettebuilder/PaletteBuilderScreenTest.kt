package com.fotoxplorr.app.palettebuilder

import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.DecodeLimits
import com.fotoxplorr.app.media.DecodedBitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.BLUE
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.FakeShare
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.GREEN
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.MemoryLibrary
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RED
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.RecordingSink
import com.fotoxplorr.app.palettebuilder.PaletteTestKit.YELLOW
import com.fotoxplorr.app.ui.FotoXplorrTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The palette builder on screen, over fakes for everything outside it: the pages, the buttons that edit without a
 * drag (add, lock, reorder, remove, the steppers), harmony and undo, save and the saved list, share, the gate for
 * photos that were protected meanwhile, the recolour page, Back, and what survives a recreation or a killed process.
 * Clicks are injected; how it looks and feels is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class PaletteBuilderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private val a = PaletteTestKit.asset(1, "a.jpg")
    private val b = PaletteTestKit.asset(2, "b.jpg")
    private val c = PaletteTestKit.asset(3, "c.jpg")
    private val photos = listOf(c, b, a)

    /** Photos are two-colour pictures; [allow] false is a folder locked again while the builder is open. */
    private class SwitchHost : PaletteHost {
        @Volatile var allow = true
        @Volatile var blocked: Set<Long> = emptySet()
        val decoded = ArrayList<Long>()

        override fun mayExport(asset: MediaAsset) = allow && asset.id.value !in blocked

        override suspend fun decode(asset: MediaAsset, limits: DecodeLimits): DecodedBitmap? {
            if (!mayExport(asset)) return null
            decoded += asset.id.value
            val pair = when (asset.id.value) {
                1L -> RED to BLUE
                2L -> GREEN to YELLOW
                else -> BLUE to GREEN
            }
            val bitmap = PaletteTestKit.bitmap(20, 10) { x, _ -> if (x < 10) pair.first else pair.second }
            return DecodedBitmap(bitmap, 20, 10, downscaled = false)
        }
    }

    private val host = SwitchHost()
    private val share = FakeShare()
    private var library = MemoryLibrary()
    private val sink = RecordingSink()
    private val runner = JobRunner(scope)
    private var closed = 0
    private val opened = ArrayList<MediaAsset>()

    @Composable
    private fun Screen(assets: List<MediaAsset> = photos, runner: JobRunner? = this.runner) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                PaletteBuilderScreen(
                    assets = assets,
                    newHost = { host },
                    newSink = { sink },
                    library = library,
                    share = share,
                    runner = runner,
                    onOpenAsset = { asset, _ -> opened += asset },
                    onClose = { closed++ },
                )
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun waitFor(timeoutMillis: Long = 60_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun waitForText(text: String) = waitFor { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun exists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun click(text: String) = compose.onNodeWithText(text).performClick()

    private fun clickDescription(description: String) = compose.onNodeWithContentDescription(description).performClick()

    private fun pressBack() {
        compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun swatchMatcher(position: Int, count: Int) =
        SemanticsMatcher("swatch $position of $count") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Colour $position of $count,") } == true
        }

    private fun swatch(position: Int, count: Int): SemanticsNodeInteraction = compose.onNode(swatchMatcher(position, count))

    private fun swatchExists(position: Int, count: Int) = compose.onAllNodes(swatchMatcher(position, count)).fetchSemanticsNodes().isNotEmpty()

    private fun describe(position: Int, count: Int): String =
        swatch(position, count).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()

    private fun hexes(count: Int): List<String> = (1..count).map { Regex("#[0-9A-F]{6}").find(describe(it, count))!!.value }

    private fun textContaining(part: String): String =
        compose.onAllNodes(hasText(part, substring = true)).fetchSemanticsNodes().first().config[SemanticsProperties.Text].first().text

    /** Start -> from a colour -> Start: the edit page of a five-colour palette. */
    private fun startFromColour() {
        click(PaletteBuilderCopy.FROM_COLOUR)
        click(PaletteBuilderCopy.COLOUR_START)
    }

    /** Start -> from photos -> tick the named photos -> Make palette -> wait for the edit page. */
    private fun startFromPhotos(vararg names: String) {
        click(PaletteBuilderCopy.FROM_PHOTOS)
        names.forEach { clickDescription(it) }
        click(PaletteBuilderCopy.MAKE_PALETTE)
        waitFor { compose.onAllNodesWithText(PaletteBuilderCopy.SAVE).fetchSemanticsNodes().isNotEmpty() || exists(PaletteBuilderCopy.PHOTO_PROTECTED) }
    }

    // ---- start ------------------------------------------------------------------------------------

    @Test
    fun `the start page offers photos and a colour, and nothing to keep editing yet`() {
        compose.setContent { Screen() }
        compose.onNodeWithText(PaletteBuilderCopy.START_HINT).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.FROM_PHOTOS).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.FROM_COLOUR).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.KEEP_EDITING).assertDoesNotExist()
        waitForText(PaletteBuilderCopy.NO_SAVED)
    }

    @Test
    fun `a colour that is not one is said so and Start waits`() {
        compose.setContent { Screen() }
        click(PaletteBuilderCopy.FROM_COLOUR)
        compose.onNodeWithText(PaletteBuilderCopy.COLOUR_START).assertIsEnabled()
        compose.onNodeWithTag(COLOUR_TAG).performTextReplacement("#12")
        compose.onNodeWithText(PaletteBuilderCopy.COLOUR_BAD).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.COLOUR_START).assertIsNotEnabled()
        compose.onNodeWithTag(COLOUR_TAG).performTextReplacement("00ff00")
        compose.onNodeWithText(PaletteBuilderCopy.COLOUR_BAD).assertDoesNotExist()
        compose.onNodeWithText(PaletteBuilderCopy.COLOUR_START).assertIsEnabled()
    }

    @Test
    fun `starting from a colour opens a palette of five with that colour first`() {
        compose.setContent { Screen() }
        click(PaletteBuilderCopy.FROM_COLOUR)
        compose.onNodeWithTag(COLOUR_TAG).performTextReplacement("#00FF00")
        click(PaletteBuilderCopy.COLOUR_START)
        assertEquals("#00FF00", hexes(5).first())
        assertTrue("the first swatch is picked", describe(1, 5).endsWith("picked"))
        assertFalse(swatchExists(6, 6))
    }

    // ---- editing without a drag -------------------------------------------------------------------

    @Test
    fun `tapping a swatch picks it, and only that one says picked`() {
        compose.setContent { Screen() }
        startFromColour()
        swatch(3, 5).performClick()
        assertTrue(describe(3, 5).endsWith("picked"))
        assertFalse(describe(1, 5).contains("picked"))
    }

    @Test
    fun `Lock and Unlock mark the picked swatch, in words as well as a mark`() {
        compose.setContent { Screen() }
        startFromColour()
        swatch(2, 5).performClick()
        click(PaletteBuilderCopy.LOCK)
        assertTrue(describe(2, 5).contains("locked"))
        assertFalse(describe(1, 5).contains("locked"))
        compose.onNodeWithText(PaletteBuilderCopy.LOCK).assertDoesNotExist()
        click(PaletteBuilderCopy.UNLOCK)
        assertFalse(describe(2, 5).contains("locked"))
        compose.onNodeWithText(PaletteBuilderCopy.LOCK).assertExists()
    }

    @Test
    fun `Move right and Move left reorder the swatches with no drag, the pick follows, and the ends stop`() {
        compose.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        compose.onNodeWithText(PaletteBuilderCopy.MOVE_LEFT).assertIsNotEnabled()

        click(PaletteBuilderCopy.MOVE_RIGHT)
        assertEquals(listOf(before[1], before[0]) + before.drop(2), hexes(5))
        assertTrue(describe(2, 5).endsWith("picked"))

        click(PaletteBuilderCopy.MOVE_LEFT)
        assertEquals(before, hexes(5))

        swatch(5, 5).performClick()
        compose.onNodeWithText(PaletteBuilderCopy.MOVE_RIGHT).assertIsNotEnabled()
    }

    @Test
    fun `Add makes a sixth swatch and picks it, up to twelve, and then says so`() {
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.ADD)
        assertTrue(swatchExists(6, 6))
        assertTrue(describe(6, 6).endsWith("picked"))
        repeat(6) { click(PaletteBuilderCopy.ADD) }
        assertTrue(swatchExists(12, 12))
        compose.onNodeWithText(PaletteBuilderCopy.ADD).assertIsNotEnabled()
        compose.onNodeWithText(PaletteBuilderCopy.AT_MAX).assertExists()
    }

    @Test
    fun `Remove takes the picked swatch out, down to two, and then says so`() {
        compose.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        swatch(2, 5).performClick()
        click(PaletteBuilderCopy.REMOVE)
        assertEquals(before.filterIndexed { i, _ -> i != 1 }, hexes(4))
        repeat(2) { click(PaletteBuilderCopy.REMOVE) }
        assertTrue(swatchExists(2, 2))
        compose.onNodeWithText(PaletteBuilderCopy.REMOVE).assertIsNotEnabled()
        compose.onNodeWithText(PaletteBuilderCopy.AT_MIN).assertExists()
    }

    @Test
    fun `the steppers change the picked colour and its readout`() {
        compose.setContent { Screen() }
        startFromColour()
        swatch(2, 5).performClick()
        val hex = hexes(5)[1]
        val hue = textContaining("degrees")

        clickDescription(PaletteBuilderCopy.raise(PaletteBuilderCopy.HUE, 15))
        assertNotEquals(hex, hexes(5)[1])
        assertNotEquals(hue, textContaining("degrees"))
        clickDescription(PaletteBuilderCopy.lower(PaletteBuilderCopy.HUE, 15))

        val afterHue = hexes(5)[1]
        clickDescription(PaletteBuilderCopy.lower(PaletteBuilderCopy.LIGHTNESS, 10))
        assertNotEquals(afterHue, hexes(5)[1])
        clickDescription(PaletteBuilderCopy.lower(PaletteBuilderCopy.SATURATION, 2))
        assertEquals("other swatches are untouched", 5, hexes(5).size)
    }

    @Test
    fun `a stepper at its end is disabled`() {
        compose.setContent { Screen() }
        click(PaletteBuilderCopy.FROM_COLOUR)
        compose.onNodeWithTag(COLOUR_TAG).performTextReplacement("#FF0000")
        click(PaletteBuilderCopy.COLOUR_START)
        // Pure red is as strong as colour goes.
        compose.onNodeWithContentDescription(PaletteBuilderCopy.raise(PaletteBuilderCopy.SATURATION, 10)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(PaletteBuilderCopy.lower(PaletteBuilderCopy.SATURATION, 10)).assertIsEnabled()
    }

    @Test
    fun `a harmony rebuilds the palette and Undo gives it back`() {
        compose.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        compose.onNodeWithText(PaletteBuilderCopy.UNDO).assertIsNotEnabled()

        click(HarmonyKind.TRIAD.label)
        val after = hexes(5)
        assertEquals("the picked colour is the base and stays", before[0], after[0])
        assertNotEquals(before, after)
        compose.onNodeWithText(PaletteBuilderCopy.UNDO).assertIsEnabled()

        click(PaletteBuilderCopy.UNDO)
        assertEquals(before, hexes(5))
        compose.onNodeWithText(PaletteBuilderCopy.UNDO).assertIsNotEnabled()
    }

    @Test
    fun `a locked swatch is left alone by a harmony`() {
        compose.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        swatch(3, 5).performClick()
        click(PaletteBuilderCopy.LOCK)
        swatch(1, 5).performClick()
        click(HarmonyKind.COMPLEMENTARY.label)
        assertEquals(before[2], hexes(5)[2])
        assertTrue(describe(3, 5).contains("locked"))
    }

    // ---- the checks -------------------------------------------------------------------------------

    @Test
    fun `the contrast grid and the colour-blind verdict are shown, with words`() {
        compose.setContent { Screen() }
        startFromColour()
        compose.onNodeWithTag(CONTRAST_TAG).assertExists()
        assertTrue(textContaining("pairs read well").matches(Regex("""\d+ of 10 pairs read well\.""")))
        compose.onAllNodes(hasContentDescription("Colour 1 on colour 2,", substring = true)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.onNodeWithTag(VISION_TAG).assertExists()
        assertTrue(exists(PaletteBuilderCopy.VISION_ALL_CLEAR) || exists(PaletteBuilderCopy.VISION_BLUR))
    }

    @Test
    fun `two near colours are flagged as blurring together for some eyes`() {
        compose.setContent { Screen() }
        click(PaletteBuilderCopy.FROM_COLOUR)
        compose.onNodeWithTag(COLOUR_TAG).performTextReplacement("#808080")
        click(PaletteBuilderCopy.COLOUR_START)
        // A grey has no hue: the neighbours the start makes are all the same grey.
        compose.onNodeWithText(PaletteBuilderCopy.VISION_BLUR).assertExists()
    }

    // ---- save and the saved list ------------------------------------------------------------------

    @Test
    fun `Save keeps the palette under the name typed, says so, and a second Save says it replaced one`() {
        compose.setContent { Screen() }
        startFromColour()
        compose.onNodeWithTag(NAME_TAG).performTextReplacement("Dawn")
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVED)
        assertEquals(listOf("Dawn"), library.palettes.map { it.name })
        assertEquals(5, library.palettes.single().size)

        click(PaletteBuilderCopy.ADD)
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVED_REPLACED)
        assertEquals(1, library.palettes.size)
        assertEquals(6, library.palettes.single().size)
    }

    @Test
    fun `a full store and a failing store are said in words`() {
        library = MemoryLibrary(full = true)
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVE_FULL)
    }

    @Test
    fun `a store that cannot write is said in words`() {
        library = MemoryLibrary(failing = true)
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVE_FAILED)
    }

    @Test
    fun `saved palettes are listed on the start page and Open brings one back to edit`() {
        library = MemoryLibrary(listOf(Palette.of("Sea", listOf(0xFF0066AA.toInt(), 0xFFEEEEEE.toInt(), 0xFF003355.toInt()))!!))
        compose.setContent { Screen() }
        waitForText(PaletteBuilderCopy.savedRow("Sea", 3))
        clickDescription(PaletteBuilderCopy.openSaved("Sea"))
        assertTrue(swatchExists(3, 3))
        assertEquals(listOf("#0066AA", "#EEEEEE", "#003355"), hexes(3))
    }

    @Test
    fun `Delete asks first, Keep it leaves the palette, Yes removes it`() {
        library = MemoryLibrary(listOf(Palette.of("Sea", listOf(0xFF0066AA.toInt(), 0xFFEEEEEE.toInt()))!!))
        compose.setContent { Screen() }
        waitForText(PaletteBuilderCopy.savedRow("Sea", 2))

        clickDescription(PaletteBuilderCopy.deleteSaved("Sea"))
        compose.onNodeWithText(PaletteBuilderCopy.deleteAsk("Sea")).assertExists()
        click(PaletteBuilderCopy.DELETE_KEEP)
        assertEquals(1, library.palettes.size)
        compose.onNodeWithText(PaletteBuilderCopy.deleteAsk("Sea")).assertDoesNotExist()

        clickDescription(PaletteBuilderCopy.deleteSaved("Sea"))
        click(PaletteBuilderCopy.DELETE_YES)
        waitForText(PaletteBuilderCopy.NO_SAVED)
        assertTrue(library.palettes.isEmpty())
    }

    @Test
    fun `a damaged saved file is said so and the rest still show`() {
        val damaged = object : PaletteLibrary {
            override suspend fun load() = SavedPalettes(listOf(Palette.of("Sea", listOf(0xFF0066AA.toInt(), 0xFFEEEEEE.toInt()))!!), damaged = true)
            override suspend fun save(palette: Palette) = StoreSave.Failed
            override suspend fun delete(name: String) = false
        }
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaletteBuilderScreen(photos, { host }, { sink }, damaged, share, runner, { _, _ -> }, {})
            }
        }
        waitForText(PaletteBuilderCopy.SAVED_DAMAGED)
        compose.onNodeWithText(PaletteBuilderCopy.savedRow("Sea", 2)).assertExists()
    }

    // ---- share ------------------------------------------------------------------------------------

    @Test
    fun `each text format is handed to the share sheet with the palette as named`() {
        compose.setContent { Screen() }
        startFromColour()
        compose.onNodeWithTag(NAME_TAG).performTextReplacement("Dawn")
        click(PaletteBuilderCopy.shareAs(ExportFormat.GPL.label))
        click(PaletteBuilderCopy.shareAs(ExportFormat.CSS.label))
        click(PaletteBuilderCopy.shareAs(ExportFormat.JSON.label))
        assertEquals(listOf(ExportFormat.GPL, ExportFormat.CSS, ExportFormat.JSON), share.texts.map { it.second })
        assertTrue(share.texts.all { it.first.name == "Dawn" })
    }

    @Test
    fun `the card is shared as a picture`() {
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.shareAs(ExportFormat.CARD.label))
        waitFor { share.cards.isNotEmpty() }
        assertEquals(1, share.cards.size)
    }

    @Test
    fun `a share that could not start says so`() {
        val broken = FakeShare(works = false)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaletteBuilderScreen(photos, { host }, { sink }, library, broken, runner, { _, _ -> }, {})
            }
        }
        startFromColour()
        click(PaletteBuilderCopy.shareAs(ExportFormat.GPL.label))
        compose.onNodeWithText(PaletteBuilderCopy.SHARE_FAILED).assertExists()
    }

    // ---- photos and the gate ----------------------------------------------------------------------

    @Test
    fun `the photo page lists the stills, counts the ticks, and Make palette waits for one`() {
        compose.setContent { Screen(assets = photos + PaletteTestKit.asset(9, "clip.mp4", mime = "video/mp4")) }
        click(PaletteBuilderCopy.FROM_PHOTOS)
        photos.forEach { compose.onNodeWithContentDescription(it.displayName).assertExists() }
        compose.onNodeWithContentDescription("clip.mp4").assertDoesNotExist()
        compose.onNodeWithText(PaletteBuilderCopy.chosen(0)).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.MAKE_PALETTE).assertIsNotEnabled()
        clickDescription("a.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.chosen(1)).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.MAKE_PALETTE).assertIsEnabled()
        clickDescription("a.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.MAKE_PALETTE).assertIsNotEnabled()
    }

    @Test
    fun `an empty library says so`() {
        compose.setContent { Screen(assets = emptyList()) }
        click(PaletteBuilderCopy.FROM_PHOTOS)
        compose.onNodeWithText(PaletteBuilderCopy.NO_PHOTOS).assertExists()
    }

    @Test
    fun `one photo gives a palette of its colours on the edit page`() {
        compose.setContent { Screen() }
        startFromPhotos("a.jpg")
        assertEquals(setOf("#DC2828", "#1E3CDC"), hexes(2).toSet())
        assertEquals(listOf(1L), host.decoded)
    }

    @Test
    fun `two photos are pooled into one palette`() {
        compose.setContent { Screen() }
        startFromPhotos("a.jpg", "b.jpg")
        assertTrue(swatchExists(4, 4))
    }

    @Test
    fun `a protected photo cannot be used and the page stays where it was`() {
        host.blocked = setOf(1L)
        compose.setContent { Screen() }
        startFromPhotos("a.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.PHOTO_PROTECTED).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.MAKE_PALETTE).assertExists()
        assertTrue(host.decoded.isEmpty())
    }

    @Test
    fun `photos locked after the palette was made stop the share, and allowing them again lets it through`() {
        compose.setContent { Screen() }
        startFromPhotos("a.jpg")
        host.allow = false
        click(PaletteBuilderCopy.shareAs(ExportFormat.GPL.label))
        compose.onNodeWithText(PaletteBuilderCopy.SOURCE_PROTECTED).assertExists()
        click(PaletteBuilderCopy.shareAs(ExportFormat.CARD.label))
        assertTrue(share.texts.isEmpty() && share.cards.isEmpty())

        host.allow = true
        click(PaletteBuilderCopy.shareAs(ExportFormat.GPL.label))
        assertEquals(1, share.texts.size)
        compose.onNodeWithText(PaletteBuilderCopy.SOURCE_PROTECTED).assertDoesNotExist()
    }

    @Test
    fun `a card whose photos were locked while it was being drawn is not offered`() {
        val locking = object : PaletteShare {
            override fun text(palette: Palette, format: ExportFormat) = true
            override suspend fun card(palette: Palette, stillAllowed: () -> Boolean): Boolean {
                host.allow = false
                return stillAllowed()
            }
        }
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaletteBuilderScreen(photos, { host }, { sink }, library, locking, runner, { _, _ -> }, {})
            }
        }
        startFromPhotos("a.jpg")
        click(PaletteBuilderCopy.shareAs(ExportFormat.CARD.label))
        waitForText(PaletteBuilderCopy.SOURCE_PROTECTED)
    }

    @Test
    fun `a palette typed in has no photo behind it, so the gate does not stop it`() {
        compose.setContent { Screen() }
        startFromColour()
        host.allow = false
        click(PaletteBuilderCopy.shareAs(ExportFormat.JSON.label))
        assertEquals(1, share.texts.size)
    }

    // ---- recolour ---------------------------------------------------------------------------------

    private fun openRecolour() {
        click(PaletteBuilderCopy.RECOLOUR_OPEN)
    }

    @Test
    fun `the recolour page waits for a photo, then Preview shows a picture and a change throws it away`() {
        compose.setContent { Screen() }
        startFromColour()
        openRecolour()
        compose.onNodeWithText(PaletteBuilderCopy.NEED_PHOTO).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.PREVIEW).assertIsNotEnabled()
        compose.onNodeWithText(PaletteBuilderCopy.SAVE_COPY).assertIsNotEnabled()

        clickDescription("a.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.NEED_PHOTO).assertDoesNotExist()
        click(PaletteBuilderCopy.PREVIEW)
        waitFor { compose.onAllNodes(hasTestTag(PREVIEW_TAG)).fetchSemanticsNodes().isNotEmpty() }

        click(PaletteBuilderCopy.WEAKER)
        compose.onNodeWithText(PaletteBuilderCopy.strengthValue(75)).assertExists()
        compose.onNodeWithTag(PREVIEW_TAG).assertDoesNotExist()
    }

    @Test
    fun `Weaker and Stronger stop at a quarter and at full`() {
        compose.setContent { Screen() }
        startFromColour()
        openRecolour()
        compose.onNodeWithText(PaletteBuilderCopy.STRONGER).assertIsNotEnabled()
        repeat(3) { click(PaletteBuilderCopy.WEAKER) }
        compose.onNodeWithText(PaletteBuilderCopy.strengthValue(25)).assertExists()
        compose.onNodeWithText(PaletteBuilderCopy.WEAKER).assertIsNotEnabled()
        click(PaletteBuilderCopy.STRONGER)
        compose.onNodeWithText(PaletteBuilderCopy.strengthValue(50)).assertExists()
    }

    @Test
    fun `Save a recoloured copy runs the job, writes one new file from the chosen photo, and says so`() {
        compose.setContent { Screen(assets = photos + PaletteTestKit.asset(900, "a-edited.jpg")) }
        startFromColour()
        openRecolour()
        clickDescription("b.jpg")
        click(PaletteBuilderCopy.SAVE_COPY)
        waitForText(PaletteBuilderCopy.COPIED)
        assertEquals(1, sink.saved.size)
        assertEquals(b, sink.saved.single().source)
        assertTrue("the library already lists the new file, so it can be opened", exists(PaletteBuilderCopy.COPY_OPEN))
        click(PaletteBuilderCopy.COPY_OPEN)
        assertEquals(900L, opened.single().id.value)
    }

    @Test
    fun `a copy of a photo that is protected says so and writes nothing`() {
        compose.setContent { Screen() }
        startFromColour()
        openRecolour()
        clickDescription("b.jpg")
        host.blocked = setOf(2L)
        click(PaletteBuilderCopy.SAVE_COPY)
        waitForText(PaletteBuilderCopy.COPY_PROTECTED)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `a copy is refused when the photos the palette came from are protected now`() {
        compose.setContent { Screen() }
        startFromPhotos("a.jpg")
        openRecolour()
        clickDescription("b.jpg")
        host.blocked = setOf(1L)
        click(PaletteBuilderCopy.SAVE_COPY)
        waitForText(PaletteBuilderCopy.SOURCE_PROTECTED)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `without a job list there is no copy button, and Preview is still offered`() {
        compose.setContent { Screen(runner = null) }
        startFromColour()
        openRecolour()
        compose.onNodeWithText(PaletteBuilderCopy.SAVE_COPY).assertDoesNotExist()
        clickDescription("a.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.PREVIEW).assertIsEnabled()
    }

    // The real writer finishes its write even when the job is cancelled (it runs uncancellable), then the job
    // discards the file; the fake does the same, holding the write open on a gate the test lets go of.

    @Test
    fun `leaving the screen cancels a copy in progress and the file it was making is discarded`() {
        val gate = CompletableDeferred<Unit>()
        val slow = RecordingSink(uncancellable = true, onSave = { gate.await() })
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) {
                FotoXplorrTheme(GalleryPreferencesState()) {
                    CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                        PaletteBuilderScreen(photos, { host }, { slow }, library, share, runner, { _, _ -> }, {})
                    }
                }
            }
        }
        startFromColour()
        openRecolour()
        clickDescription("a.jpg")
        click(PaletteBuilderCopy.SAVE_COPY)
        waitFor { slow.saved.isNotEmpty() && runner.jobs.value.isNotEmpty() }

        compose.runOnUiThread { show = false }
        // The write is held open until the screen's leaving has cancelled the job, as a real write would be mid-way.
        waitFor { slow.callerJob?.isCancelled == true }
        gate.complete(Unit)
        waitFor { runner.jobs.value.isEmpty() }
        waitFor { slow.discarded.isNotEmpty() }
    }

    @Test
    fun `Cancel stops a copy in progress and says so`() {
        val gate = CompletableDeferred<Unit>()
        val slow = RecordingSink(uncancellable = true, onSave = { gate.await() })
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PaletteBuilderScreen(photos, { host }, { slow }, library, share, runner, { _, _ -> }, {})
            }
        }
        startFromColour()
        openRecolour()
        clickDescription("a.jpg")
        click(PaletteBuilderCopy.SAVE_COPY)
        waitFor { slow.saved.isNotEmpty() }
        compose.onNodeWithText(PaletteBuilderCopy.COPYING).assertExists()
        click(PaletteBuilderCopy.CANCEL)
        waitFor { slow.callerJob?.isCancelled == true }
        gate.complete(Unit)
        waitForText(PaletteBuilderCopy.CANCELLED)
        waitFor { slow.discarded.isNotEmpty() }
    }

    @Test
    fun `while a copy runs the first Back that would leave is held and the next leaves`() {
        val gate = CompletableDeferred<Unit>()
        val slow = RecordingSink(uncancellable = true, onSave = { gate.await() })
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                    PaletteBuilderScreen(photos, { host }, { slow }, library, share, runner, { _, _ -> }, { closed++ })
                }
            }
        }
        startFromColour()
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVED)
        openRecolour()
        clickDescription("a.jpg")
        click(PaletteBuilderCopy.SAVE_COPY)
        waitFor { slow.saved.isNotEmpty() }
        // Recolour to edit to start, and then the one that would leave is held.
        repeat(3) { pressBack() }
        compose.onNodeWithText(PaletteBuilderCopy.START_HINT).assertExists()
        assertEquals(0, closed)
        pressBack()
        assertEquals(1, closed)
        gate.complete(Unit)
        waitFor { runner.jobs.value.isEmpty() }
    }

    // ---- Back -------------------------------------------------------------------------------------

    @Test
    fun `Back steps from recolour to edit to start, and Keep editing returns to the palette`() {
        compose.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        openRecolour()
        pressBack()
        assertTrue(swatchExists(1, 5))
        pressBack()
        compose.onNodeWithText(PaletteBuilderCopy.START_HINT).assertExists()
        click(PaletteBuilderCopy.KEEP_EDITING)
        assertEquals(before, hexes(5))
        assertEquals(0, closed)
    }

    @Test
    fun `an unsaved palette holds the first Back that would leave, and the next one leaves`() {
        compose.setContent { Screen() }
        startFromColour()
        pressBack()
        compose.onNodeWithText(PaletteBuilderCopy.START_HINT).assertExists()
        pressBack()
        compose.onNodeWithText(PaletteBuilderCopy.UNSAVED_LEAVE).assertExists()
        assertEquals("the first Back that would leave was held", 0, closed)
        pressBack()
        assertEquals(1, closed)
    }

    @Test
    fun `a saved palette lets Back leave at once`() {
        library = MemoryLibrary()
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVED)
        pressBack()
        // At the start page nothing is unsaved, so no Back callback is left for the host's to be asked first.
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `an edit after a save makes the palette unsaved again`() {
        compose.setContent { Screen() }
        startFromColour()
        click(PaletteBuilderCopy.SAVE)
        waitForText(PaletteBuilderCopy.SAVED)
        click(PaletteBuilderCopy.ADD)
        pressBack()
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `with nothing started Back is left to the host`() {
        compose.setContent { Screen() }
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        click(PaletteBuilderCopy.FROM_COLOUR)
        compose.waitForIdle()
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        pressBack()
        compose.onNodeWithText(PaletteBuilderCopy.START_HINT).assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // ---- what survives ----------------------------------------------------------------------------

    @Test
    fun `the palette, its locks, order and pick survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        startFromColour()
        swatch(2, 5).performClick()
        click(PaletteBuilderCopy.LOCK)
        click(PaletteBuilderCopy.MOVE_RIGHT)
        val before = hexes(5)

        tester.emulateSavedInstanceStateRestore()

        assertEquals(before, hexes(5))
        assertTrue(describe(3, 5).contains("locked"))
        assertTrue(describe(3, 5).endsWith("picked"))
        assertTrue("and Back still has its page", owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun `the undo list survives too`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        startFromColour()
        val before = hexes(5)
        click(HarmonyKind.TETRAD.label)
        tester.emulateSavedInstanceStateRestore()
        click(PaletteBuilderCopy.UNDO)
        assertEquals(before, hexes(5))
    }

    @Test
    fun `the name typed is kept per palette through a recreation`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        startFromColour()
        compose.onNodeWithTag(NAME_TAG).performTextReplacement("Dawn")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag(NAME_TAG).assertExists()
        assertTrue(compose.onAllNodes(hasText("Dawn")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a ticked photo that left the library drops out, and an empty list is not taken as everything gone`() {
        var assets by mutableStateOf(photos)
        compose.setContent { Screen(assets = assets) }
        click(PaletteBuilderCopy.FROM_PHOTOS)
        clickDescription("a.jpg")
        clickDescription("b.jpg")
        compose.onNodeWithText(PaletteBuilderCopy.chosen(2)).assertExists()

        compose.runOnUiThread { assets = photos.filter { it.id != b.id } }
        compose.waitForIdle()
        compose.onNodeWithText(PaletteBuilderCopy.chosen(1)).assertExists()

        compose.runOnUiThread { assets = emptyList() }
        compose.waitForIdle()
        compose.onNodeWithText(PaletteBuilderCopy.chosen(1)).assertExists()
    }

    // ---- accessibility ----------------------------------------------------------------------------

    @Test
    fun `every control is at least 48 dp and has a name`() {
        compose.setContent { Screen() }
        compose.onNodeWithContentDescription(PaletteBuilderCopy.BACK).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        listOf(PaletteBuilderCopy.FROM_PHOTOS, PaletteBuilderCopy.FROM_COLOUR).forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
        startFromColour()
        listOf(
            PaletteBuilderCopy.LOCK, PaletteBuilderCopy.MOVE_LEFT, PaletteBuilderCopy.MOVE_RIGHT, PaletteBuilderCopy.ADD, PaletteBuilderCopy.REMOVE,
            PaletteBuilderCopy.UNDO, PaletteBuilderCopy.SAVE, PaletteBuilderCopy.RECOLOUR_OPEN,
            HarmonyKind.TRIAD.label, HarmonyKind.SHADES.label,
            PaletteBuilderCopy.shareAs(ExportFormat.GPL.label), PaletteBuilderCopy.shareAs(ExportFormat.CARD.label),
        ).forEach { compose.onNodeWithText(it).assertHeightIsAtLeast(48.dp) }
        listOf(PaletteBuilderCopy.HUE, PaletteBuilderCopy.SATURATION, PaletteBuilderCopy.LIGHTNESS).forEach { label ->
            listOf(PaletteBuilderCopy.lower(label, if (label == PaletteBuilderCopy.HUE) 15 else 10), PaletteBuilderCopy.raise(label, if (label == PaletteBuilderCopy.HUE) 3 else 2))
                .forEach { compose.onNodeWithContentDescription(it).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        }
        (1..5).forEach { swatch(it, 5).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
    }

    @Test
    fun `every swatch is announced with its place, its hex and its state`() {
        compose.setContent { Screen() }
        startFromColour()
        swatch(2, 5).performClick()
        click(PaletteBuilderCopy.LOCK)
        val d = describe(2, 5)
        assertTrue(d.matches(Regex("""Colour 2 of 5, #[0-9A-F]{6}, locked, picked""")))
        assertTrue(describe(1, 5).matches(Regex("""Colour 1 of 5, #[0-9A-F]{6}""")))
    }
}
