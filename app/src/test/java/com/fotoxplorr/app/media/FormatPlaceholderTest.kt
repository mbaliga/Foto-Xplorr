package com.fotoxplorr.app.media

import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream

/**
 * WP1.7's acceptance line, on a real Compose runtime: a file that cannot be drawn is a neutral tile
 * with a badge, never an empty one. Runs in both flavors (src/test is shared). The decision table
 * itself is core:formats' TileStateTest; this proves the wiring in [MediaImage].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FormatPlaceholderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun asset(name: String, mime: String, uri: String = "content://fake/$name") = MediaAsset(
        id = MediaId(name.hashCode().toLong()),
        contentUriString = uri,
        displayName = name,
        mimeType = mime,
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 1L,
        width = 0,
        height = 0,
        sizeBytes = 0L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `an undecodable raw draws a badge and a reason and says so to a screen reader`() {
        // X3F: a container the RAW preview extractor cannot walk, so it is refused up front. (A CR3
        // is decodable through its embedded preview now and goes to the loader first.)
        compose.setContent { MediaImage(asset("IMG_0001.X3F", ""), Modifier.size(200.dp)) }
        compose.onNodeWithText("X3F").assertIsDisplayed()
        compose.onNodeWithText("Raw photos can't be previewed yet.").assertIsDisplayed()
        compose.onNodeWithContentDescription("X3F raw photo, can't be shown here.").assertIsDisplayed()
    }

    @Test
    fun `a raw without a readable preview tries the loader and then shows a placeholder`() {
        compose.setContent { MediaImage(asset("IMG_0001.CR3", "", "content://fake/missing-raw"), Modifier.size(200.dp)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("CR3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This file couldn't be read.").assertIsDisplayed()
    }

    @Test
    fun `a stamp sized tile keeps the badge and drops the sentence`() {
        compose.setContent { MediaImage(asset("IMG_0001.X3F", ""), Modifier.size(48.dp)) }
        compose.onNodeWithText("X3F").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Raw photos can't be previewed yet.").fetchSemanticsNodes().size)
    }

    @Test
    fun `an exr names a pack it does not have`() {
        compose.setContent { MediaImage(asset("render.exr", "image/x-exr"), Modifier.size(200.dp)) }
        compose.onNodeWithText("EXR").assertIsDisplayed()
        compose.onNodeWithContentDescription("EXR image, can't be shown here.").assertIsDisplayed()
    }

    @Test
    fun `an unknown type is sniffed from its first bytes and becomes an exr placeholder`() {
        val uri = "content://fake/mystery"
        // EXR: TIFF is decodable now (core/formats/simple), so it no longer ends as a placeholder.
        val tiff = byteArrayOf(0x76, 0x2F, 0x31, 0x01) + ByteArray(60)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver)
            .registerInputStream(Uri.parse(uri), ByteArrayInputStream(tiff))
        compose.setContent { MediaImage(asset("mystery", "application/octet-stream", uri), Modifier.size(200.dp)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("EXR").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("EXR image, can't be shown here.").assertIsDisplayed()
    }

    @Test
    fun `a jpeg that fails to load ends as a placeholder and not an empty tile`() {
        compose.setContent { MediaImage(asset("broken.jpg", "image/jpeg", "content://fake/missing"), Modifier.size(200.dp)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("JPG").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This file couldn't be read.").assertIsDisplayed()
        // The name stays the content description; the sentence is the state.
        compose.onNodeWithContentDescription("broken.jpg").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "JPG image, can't be shown here."))
    }
}
