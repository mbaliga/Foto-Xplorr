package com.fotoxplorr.app.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import dev.aarso.cellshell.ScrubberEdge
import dev.aarso.cellshell.ScrubberStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rail laid over a grid, in its default hidden mode: the invisible 44 dp strip must not take
 * the grid's taps, long-presses or ordinary scrolls, but must still scrub on a deliberate drag along
 * it. (Audit item 1: a plain pointer-input sibling on top swallowed all of those.)
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xhdpi")
class RailPointerComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private var longs = 0
    private val scrubbed = mutableListOf<Int>()
    private lateinit var listState: androidx.compose.foundation.lazy.LazyListState

    private fun setUp() {
        compose.setContent {
            listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(List(200) { it }) { _, i ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(if (i % 2 == 0) Color.Gray else Color.DarkGray)
                                .combinedClickable(onClick = { taps++ }, onLongClick = { longs++ }),
                        )
                    }
                }
                TimelineRail(
                    stops = List(20) { ScrubberStop("M$it", it * 10) },
                    itemCount = 200,
                    currentIndex = 0,
                    onScrubTo = { scrubbed += it },
                    inkColor = Color.White,
                    accentColor = Color.Blue,
                    bubbleTextColor = Color.White,
                    edge = ScrubberEdge.RIGHT,
                    visibility = TimelineVisibility.WHILE_SCRUBBING,
                    restAlpha = 0.6f,
                )
            }
        }
    }

    @Test
    fun `control - a tap in the middle of the grid reaches the tile`() {
        setUp()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 150f * density)) }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun `a tap reaches a plain tile under the rail`() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().combinedClickable(onClick = { taps++ }))
                TimelineRail(
                    stops = List(20) { ScrubberStop("M$it", it * 10) },
                    itemCount = 200, currentIndex = 0, onScrubTo = { },
                    inkColor = Color.White, accentColor = Color.Blue, bubbleTextColor = Color.White,
                    edge = ScrubberEdge.RIGHT, visibility = TimelineVisibility.WHILE_SCRUBBING, restAlpha = 0.6f,
                )
            }
        }
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 20f * density, 150f * density)) }
        compose.waitForIdle()
        assertEquals(1, taps)
    }

    @Test
    fun `a tap in the outer 44 dp of the grid reaches the tile`() {
        setUp()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width - 20f * density, 150f * density)) }
        compose.waitForIdle()
        assertEquals(1, taps)
        assertTrue(scrubbed.isEmpty())
    }

    @Test
    fun `a long press in the outer 44 dp reaches the tile`() {
        setUp()
        compose.onRoot().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(width - 20f * density, 150f * density)) }
        compose.waitForIdle()
        assertEquals(1, longs)
        assertTrue(scrubbed.isEmpty())
    }

    @Test
    fun `a drag along the rail scrubs and does not scroll the grid`() {
        setUp()
        compose.onRoot().performTouchInput {
            val x = width - 20f * density
            swipe(
                start = androidx.compose.ui.geometry.Offset(x, 100f * density),
                end = androidx.compose.ui.geometry.Offset(x, 700f * density),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue("the rail scrubbed", scrubbed.size > 1)
        assertTrue("towards the end of the list", scrubbed.last() > scrubbed.first())
        assertEquals("the grid did not scroll by itself", 0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertEquals("a scrub is not a tap", 0, taps)
    }

    @Test
    fun `a drag in the middle of the grid scrolls it and does not scrub`() {
        setUp()
        compose.onRoot().performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(width / 2f, 700f * density),
                end = androidx.compose.ui.geometry.Offset(width / 2f, 100f * density),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        assertTrue(scrubbed.isEmpty())
    }
}
