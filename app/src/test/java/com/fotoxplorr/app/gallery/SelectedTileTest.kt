package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's multi-select reference (2026-11-18) draws every selected tile plain, so a selected
 * [MediaTile] draws exactly what a tile outside selection mode does: no wash, no ✓. The tiles
 * left OUT of an active selection are dimmed, so a stray tap is visible. The state also reaches
 * TalkBack as the selected flag and a "Selected" / "Not selected" state description.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SelectedTileTest {

    @get:Rule
    val compose = createComposeRule()

    private fun asset(id: Long) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/$id", displayName = "$id.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 0L,
        width = 10, height = 10, sizeBytes = 1L, relativePath = null, isFavorite = false,
        isTrashed = false,
    )

    private val active = androidx.compose.runtime.mutableStateOf(true)

    private fun showPair(selectionActive: Boolean = true) {
        active.value = selectionActive
        compose.setContent {
            val selectionActive = active.value
            FotoXplorrTheme(GalleryPreferencesState()) {
                Row {
                    listOf(true to "picked", false to "unpicked").forEachIndexed { i, (selected, tag) ->
                        Box(Modifier.width(144.dp).testTag(tag)) {
                            MediaTile(
                                asset = asset(i + 1L), favorite = false, sensitive = false,
                                blurSensitive = false, selected = selected && selectionActive, selectionActive = selectionActive,
                                fitToTile = true, loopAnimations = false, animated = false,
                                onOpen = {}, onToggleSelection = {}, onPeek = {}, onPeekEnd = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun pixels(tag: String, suffix: String): com.fotoxplorr.app.animate.Jdk.Raster {
        val path = "build/renders/selected-tile-$tag-$suffix.png"
        compose.onNodeWithTag(tag).captureRoboImage(path)
        return com.fotoxplorr.app.animate.Jdk.decode(java.io.File(path).readBytes())
    }

    private fun channelDelta(a: Int, b: Int) =
        (0..16 step 8).maxOf { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) }

    /** Largest per-channel difference between two equally sized rasters. */
    private fun worst(a: com.fotoxplorr.app.animate.Jdk.Raster, b: com.fotoxplorr.app.animate.Jdk.Raster): Int {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        return a.argb.indices.maxOf { channelDelta(a.argb[it], b.argb[it]) }
    }

    private fun luma(r: com.fotoxplorr.app.animate.Jdk.Raster): Double = r.argb.sumOf { p ->
        0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
    } / r.argb.size

    @Test
    fun `a selected tile draws plain and an unselected one is dimmed`() {
        showPair(selectionActive = false)
        val plainA = pixels("picked", "idle")
        val plainB = pixels("unpicked", "idle")
        active.value = true
        compose.waitForIdle()
        compose.onNodeWithText("✓", useUnmergedTree = true).assertDoesNotExist()
        val picked = pixels("picked", "selecting")
        val unpicked = pixels("unpicked", "selecting")
        // The selected tile is exactly what it was outside selection mode (the reference's look).
        assertTrue("selected tile moved by ${worst(picked, plainA)}", worst(picked, plainA) <= 2)
        // The tile left out is darkened by the wash: about (1 - UNSELECTED_DIM_ALPHA) of its luma.
        val ratio = luma(unpicked) / luma(plainB)
        assertEquals(1.0 - UNSELECTED_DIM_ALPHA, ratio, 0.05)
    }

    @Test
    fun `the tile still announces whether it is selected`() {
        showPair()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, TILE_SELECTED),
        ).assertCountEquals(1)
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, false) and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, TILE_NOT_SELECTED),
        ).assertCountEquals(1)
    }
}
