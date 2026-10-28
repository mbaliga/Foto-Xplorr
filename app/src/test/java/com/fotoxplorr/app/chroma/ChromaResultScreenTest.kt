package com.fotoxplorr.app.chroma

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.palettebuilder.Palette
import com.fotoxplorr.app.palettebuilder.PaletteLibrary
import com.fotoxplorr.app.palettebuilder.SavedPalettes
import com.fotoxplorr.app.palettebuilder.StoreSave
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The result card, the history, the comparison and the palette hand-off as screens, over in-memory stores
 * and with no camera. What these hold: the card is words and numbers, every button is 48 dp, delete-all asks
 * once, a comparison names both swatches and a verdict, a palette choice that cannot be taken says why.
 * The live view and the swatch that covers it are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h1200dp-xhdpi")
class ChromaResultScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rust = ChromaTestKit.argb(176, 48, 32)
    private val teal = ChromaTestKit.argb(32, 150, 160)

    private fun saved(argb: Int, kind: ReadingKind = ReadingKind.APPROXIMATE, at: Long = 1_700_000_000_000L) = SavedReading(argb, kind, at)

    private class MemoryHistory(var readings: List<SavedReading> = emptyList(), var failAdd: Boolean = false, var failClear: Boolean = false) : ChromaHistory {
        override suspend fun load() = HistoryLoad(readings)
        override suspend fun add(reading: SavedReading): Boolean {
            if (failAdd) return false
            readings = listOf(reading) + readings
            return true
        }
        override suspend fun clear(): Boolean {
            if (failClear) return false
            readings = emptyList()
            return true
        }
    }

    private class MemoryPalettes(var palettes: List<Palette> = emptyList()) : PaletteLibrary {
        override suspend fun load() = SavedPalettes(palettes)
        override suspend fun save(palette: Palette): StoreSave {
            val replaced = palettes.any { it.name.equals(palette.name, true) }
            palettes = listOf(palette) + palettes.filterNot { it.name.equals(palette.name, true) }
            return StoreSave.Saved(replaced)
        }
        override suspend fun delete(name: String) = false
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        FotoXplorrTheme(GalleryPreferencesState()) { content() }
    }

    // ---- the result card --------------------------------------------------------------------------------

    @Test
    fun theCardHasTheSwatchTheWordsTheNumbersAndTheThreeNearestNames() {
        val view = ReadingView.of(saved(rust, ReadingKind.APPROXIMATE))
        compose.setContent { Themed { Column { ReadingCard(view, actions = null, notice = null) } } }
        compose.onNodeWithContentDescription(view.swatchDescription).assertIsDisplayed()
        compose.onNodeWithText(view.report.description).assertIsDisplayed()
        compose.onNodeWithText("#B03020").assertIsDisplayed()
        compose.onNodeWithText("RGB 176 48 32").assertIsDisplayed()
        compose.onNodeWithText(view.labLine).assertIsDisplayed()
        compose.onNodeWithText(view.lchLine).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.NEAREST).assertIsDisplayed()
        for (line in view.nearestLines) compose.onNodeWithText(line).assertIsDisplayed()
        compose.onNodeWithText("Approximate: good for comparing swatches, not lab-grade.").assertIsDisplayed()
    }

    @Test
    fun aCardCalibratedReadingSaysSo() {
        val view = ReadingView.of(saved(rust, ReadingKind.CALIBRATED))
        compose.setContent { Themed { Column { ReadingCard(view, null, null) } } }
        compose.onNodeWithText("Calibrated to your card.").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(ChromaCopy.ACCURACY_APPROXIMATE).fetchSemanticsNodes().size)
    }

    @Test
    fun theCardsThreeActionsAreFortyEightDpAndDoWhatTheySay() {
        val log = mutableListOf<String>()
        val view = ReadingView.of(saved(rust))
        val actions = ResultActions({ log += "copy" }, { log += "compare" }, { log += "save" })
        compose.setContent { Themed { Column { ReadingCard(view, actions, notice = ChromaCopy.copied("#B03020")) } } }
        compose.onNodeWithText(ChromaCopy.COPY_HEX).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.COMPARE).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.SAVE_TO_PALETTE).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Copied #B03020.").assertIsDisplayed()
        assertEquals(listOf("copy", "compare", "save"), log)
    }

    // ---- the screen with no camera ----------------------------------------------------------------------

    @Composable
    private fun Screen(history: ChromaHistory, palettes: PaletteLibrary, onClose: () -> Unit = {}) {
        Themed { ChromaIdScreen(choice = null, history = history, palettes = palettes, onClose = onClose) }
    }

    @Test
    fun withNoCameraTheMessageAndTheHistoryButtonAreThere() {
        compose.setContent { Screen(MemoryHistory(), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.NO_CAMERA).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.HISTORY).assertHeightIsAtLeast(48.dp).assertIsDisplayed()
    }

    @Test
    fun anEmptyHistorySaysSoAndTheLineAboutWhatIsKept() {
        compose.setContent { Screen(MemoryHistory(), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText(ChromaCopy.HISTORY_EMPTY).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.HISTORY_KEPT).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(ChromaCopy.DELETE_ALL).fetchSemanticsNodes().size)
    }

    @Test
    fun theHistoryListsReadingsWithTheirHexAndHowTheyWereMade() {
        val h = MemoryHistory(listOf(saved(rust, ReadingKind.CALIBRATED, 2_000), saved(teal, ReadingKind.ROOM, 1_000)))
        compose.setContent { Screen(h, MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText("#B03020  ${ChromaCopy.KIND_CALIBRATED}").assertIsDisplayed()
        compose.onNodeWithText("${ColourSpace.hex(teal)}  ${ChromaCopy.KIND_ROOM}").assertIsDisplayed()
        compose.onNodeWithContentDescription(ChromaCopy.copyHexOf("#B03020")).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun deleteAllAsksOnceAndKeepingThemChangesNothing() {
        val h = MemoryHistory(listOf(saved(rust), saved(teal, at = 5)))
        compose.setContent { Screen(h, MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_ALL).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_SURE).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.DELETE_NO).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, h.readings.size)
        compose.onNodeWithText(ChromaCopy.DELETE_ALL).assertIsDisplayed()
    }

    @Test
    fun confirmingDeleteAllEmptiesTheHistoryAndSaysSo() {
        val h = MemoryHistory(listOf(saved(rust), saved(teal, at = 5)))
        compose.setContent { Screen(h, MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_ALL).performScrollTo().performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_YES).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        assertTrue(h.readings.isEmpty())
        compose.onNodeWithText(ChromaCopy.DELETED).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.HISTORY_EMPTY).assertIsDisplayed()
    }

    @Test
    fun aDeleteThatFailsSaysSoAndKeepsTheList() {
        val h = MemoryHistory(listOf(saved(rust)), failClear = true)
        compose.setContent { Screen(h, MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_ALL).performScrollTo().performClick()
        compose.onNodeWithText(ChromaCopy.DELETE_YES).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ChromaCopy.DELETE_FAILED).assertIsDisplayed()
        assertEquals(1, h.readings.size)
    }

    @Test
    fun backToMeasuringReturnsToTheFirstView() {
        compose.setContent { Screen(MemoryHistory(), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithText(ChromaCopy.MEASURE_NEW).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.NO_CAMERA).assertIsDisplayed()
    }

    @Test
    fun theHeaderBackLeavesAnInnerViewFirstAndTheToolOnlyFromTheFirst() {
        var closed = 0
        compose.setContent { Screen(MemoryHistory(), MemoryPalettes()) { closed++ } }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(0, closed)
        compose.onNodeWithText(ChromaCopy.NO_CAMERA).assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, closed)
    }

    // ---- comparing --------------------------------------------------------------------------------------

    @Test
    fun comparingFromAHistoryRowListsTheOthersThenShowsTwoSwatchesAndAVerdict() {
        val a = saved(rust, ReadingKind.CALIBRATED, 3_000)
        val b = saved(teal, ReadingKind.CALIBRATED, 2_000)
        compose.setContent { Screen(MemoryHistory(listOf(a, b)), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithContentDescription(ChromaCopy.compareHex(a.hex)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.COMPARE_PICK).assertIsDisplayed()
        compose.onNodeWithContentDescription(ChromaCopy.compareWithHex(b.hex)).assertHeightIsAtLeast(48.dp).performClick()
        val view = CompareView.of(a, b)
        compose.onNodeWithText(view.differenceLine).assertIsDisplayed()
        compose.onNodeWithText(view.verdict.level.message).assertIsDisplayed()
        compose.onNodeWithContentDescription("${ChromaCopy.COMPARE_THIS}: ${view.first.swatchDescription}").assertIsDisplayed()
        compose.onNodeWithContentDescription("${ChromaCopy.COMPARE_OTHER}: ${view.second.swatchDescription}").assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.COMPARE_PICK_OTHER).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(ChromaCopy.COMPARE_PICK).assertIsDisplayed()
    }

    @Test
    fun anApproximateComparisonCarriesTheCaution() {
        val a = saved(rust, ReadingKind.APPROXIMATE, 3_000)
        val b = saved(teal, ReadingKind.CALIBRATED, 2_000)
        compose.setContent { Screen(MemoryHistory(listOf(a, b)), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithContentDescription(ChromaCopy.compareHex(a.hex)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(ChromaCopy.compareWithHex(b.hex)).performClick()
        compose.onNodeWithText(ChromaCopy.COMPARE_NOTE).assertIsDisplayed()
    }

    @Test
    fun withNothingElseToCompareWithItSaysSo() {
        val a = saved(rust)
        compose.setContent { Screen(MemoryHistory(listOf(a)), MemoryPalettes()) }
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithContentDescription(ChromaCopy.compareHex(a.hex)).performScrollTo().performClick()
        compose.onNodeWithText(ChromaCopy.COMPARE_NOTHING).assertIsDisplayed()
    }

    // ---- the palette hand-off ---------------------------------------------------------------------------

    private fun openSave(history: MemoryHistory, hex: String) {
        compose.onNodeWithText(ChromaCopy.HISTORY).performClick()
        compose.onNodeWithContentDescription(ChromaCopy.saveHex(hex)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
    }

    @Test
    fun savingToAPaletteAddsTheColourAndSaysWhere() {
        val a = saved(rust, at = 3_000)
        val h = MemoryHistory(listOf(a))
        val lib = MemoryPalettes(listOf(Palette.of("Sea", listOf(teal, ChromaTestKit.argb(0, 0, 80)))!!))
        compose.setContent { Screen(h, lib) }
        openSave(h, a.hex)
        compose.onNodeWithText(ChromaCopy.SAVE_PICK).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.addTo("Sea", 2)).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ChromaCopy.addedTo("Sea")).assertIsDisplayed()
        assertEquals(3, lib.palettes.single().size)
        assertEquals(rust, lib.palettes.single().argbs.last())
    }

    @Test
    fun aPaletteThatAlreadyHasTheColourSaysSoAndIsNotAButton() {
        val a = saved(rust, at = 3_000)
        val h = MemoryHistory(listOf(a))
        val lib = MemoryPalettes(listOf(Palette.of("Warm", listOf(rust, teal))!!))
        compose.setContent { Screen(h, lib) }
        openSave(h, a.hex)
        compose.onNodeWithText(ChromaCopy.paletteHasIt("Warm")).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(ChromaCopy.addTo("Warm", 2)).fetchSemanticsNodes().size)
    }

    @Test
    fun withOnlyOneReadingANewPaletteNeedsTwoColours() {
        val a = saved(rust, at = 3_000)
        val h = MemoryHistory(listOf(a))
        compose.setContent { Screen(h, MemoryPalettes()) }
        openSave(h, a.hex)
        compose.onNodeWithText(ChromaCopy.SAVE_NONE).assertIsDisplayed()
        compose.onNodeWithText(ChromaCopy.NEW_NEEDS_TWO).assertIsDisplayed()
    }

    @Test
    fun aNewPaletteTakesThisColourAndTheLastOtherReading() {
        val a = saved(rust, at = 3_000)
        val b = saved(teal, at = 2_000)
        val h = MemoryHistory(listOf(a, b))
        val lib = MemoryPalettes()
        compose.setContent { Screen(h, lib) }
        openSave(h, a.hex)
        compose.onNodeWithText(ChromaCopy.newPaletteWith("Chroma ID")).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ChromaCopy.createdPalette("Chroma ID")).assertIsDisplayed()
        assertEquals(listOf(rust, teal), lib.palettes.single().argbs)
    }
}
