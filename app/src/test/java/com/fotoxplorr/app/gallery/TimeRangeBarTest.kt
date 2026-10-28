package com.fotoxplorr.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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
 * The range chip and its editor: what they say, what each control calls back with, and the
 * 48dp target on the clear button. The slider is checked as pure arithmetic ([handlesFor]) -- a
 * drag on it under Robolectric would only test Material's slider.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class TimeRangeBarTest {

    @get:Rule
    val compose = createComposeRule()

    // The labels read in the default zone, so the range is built in it too.
    private fun range(from: YearMonth, to: YearMonth) = TimeRange.ofMonths(from, to)

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    content()
                }
            }
        }
    }

    // ---- the chip ----

    @Test
    fun `the chip says the range and how many are shown`() {
        val r = range(YearMonth(2023, 2), YearMonth(2024, 8))
        host { TimeRangeChip(range = r, shownCount = 412, onEdit = {}, onClear = {}) }
        compose.onNodeWithText("${r.label()} · 412").assertExists()
    }

    @Test
    fun `a range that matches nothing says so with a zero`() {
        val r = range(YearMonth(2023, 2), YearMonth(2023, 2))
        host { TimeRangeChip(range = r, shownCount = 0, onEdit = {}, onClear = {}) }
        compose.onNodeWithText("${r.label()} · 0").assertExists()
    }

    @Test
    fun `the chip's clear button is 48dp square and clears`() {
        var cleared = 0
        var edited = 0
        host {
            TimeRangeChip(
                range = range(YearMonth(2023, 2), YearMonth(2023, 2)),
                shownCount = 3,
                onEdit = { edited++ },
                onClear = { cleared++ },
            )
        }
        compose.onNodeWithContentDescription("Clear time range")
            .assertHasClickAction()
            .assertTouchWidthIsEqualTo(48.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()
        assertEquals(1, cleared)
        assertEquals("clearing is not editing", 0, edited)
    }

    @Test
    fun `tapping the chip's body opens the editor`() {
        var edited = 0
        val r = range(YearMonth(2023, 2), YearMonth(2023, 2))
        host { TimeRangeChip(range = r, shownCount = 3, onEdit = { edited++ }, onClear = {}) }
        compose.onNodeWithText("${r.label()} · 3").performClick()
        assertEquals(1, edited)
    }

    // ---- the editor ----

    private val domain = YearMonth(2021, 10) to YearMonth(2024, 4)

    @Test
    fun `the editor offers every year in the domain`() {
        host {
            TimeRangeEditor(
                domain = domain, range = null,
                onApply = {}, onClear = {}, onDismiss = {},
            )
        }
        listOf(2021, 2022, 2023, 2024).forEach { compose.onNodeWithText(it.toString()).assertExists() }
        compose.onNodeWithText("2020").assertDoesNotExist()
        compose.onNodeWithText("2025").assertDoesNotExist()
    }

    @Test
    fun `a year chip applies that whole year`() {
        var applied: TimeRange? = null
        host {
            TimeRangeEditor(
                domain = domain, range = null,
                onApply = { applied = it }, onClear = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("2023").performClick()
        assertEquals(TimeRange.ofMonths(YearMonth(2023, 0), YearMonth(2023, 11)), applied)
    }

    @Test
    fun `all time is offered only with a range and clears it`() {
        var cleared = 0
        val r = range(YearMonth(2023, 0), YearMonth(2023, 11))
        host {
            TimeRangeEditor(
                domain = domain, range = r,
                onApply = {}, onClear = { cleared++ }, onDismiss = {},
            )
        }
        compose.onNodeWithText("All time").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `without a range there is no all time to choose`() {
        host {
            TimeRangeEditor(domain = domain, range = null, onApply = {}, onClear = {}, onDismiss = {})
        }
        compose.onNodeWithText("All time").assertDoesNotExist()
    }

    @Test
    fun `the editor's close button dismisses without applying`() {
        var dismissed = 0
        var applied = 0
        host {
            TimeRangeEditor(
                domain = domain, range = null,
                onApply = { applied++ }, onClear = {}, onDismiss = { dismissed++ },
            )
        }
        compose.onNodeWithContentDescription("Close time filter")
            .assertTouchWidthIsEqualTo(48.dp)
            .performClick()
        assertEquals(1, dismissed)
        assertEquals(0, applied)
    }

    @Test
    fun `a view with nothing dated says so instead of showing a slider`() {
        host {
            TimeRangeEditor(domain = null, range = null, onApply = {}, onClear = {}, onDismiss = {})
        }
        compose.onNodeWithText("Nothing in this view has a date to filter on.").assertExists()
    }

    // ---- the handles ----

    @Test
    fun `with no range the handles span the whole domain`() {
        val handles = handlesFor(null, 100, 140)
        assertEquals(100f, handles.start, 0f)
        assertEquals(140f, handles.endInclusive, 0f)
    }

    @Test
    fun `a range positions the handles on its first and last month`() {
        val r = TimeRange.ofMonths(YearMonth(2023, 2), YearMonth(2023, 6))
        val handles = handlesFor(r, YearMonth(2021, 10).index, YearMonth(2024, 4).index)
        assertEquals(YearMonth(2023, 2).index.toFloat(), handles.start, 0f)
        assertEquals(YearMonth(2023, 6).index.toFloat(), handles.endInclusive, 0f)
    }

    @Test
    fun `a range outside the domain is clamped into it rather than put off the slider`() {
        val first = YearMonth(2023, 0).index
        val last = YearMonth(2023, 5).index
        val early = handlesFor(TimeRange.ofMonths(YearMonth(2019, 0), YearMonth(2019, 3)), first, last)
        assertTrue(early.start >= first && early.endInclusive >= early.start && early.endInclusive <= last)
        val late = handlesFor(TimeRange.ofMonths(YearMonth(2026, 0), YearMonth(2026, 3)), first, last)
        assertTrue(late.start <= last && late.endInclusive == last.toFloat())
    }
}
