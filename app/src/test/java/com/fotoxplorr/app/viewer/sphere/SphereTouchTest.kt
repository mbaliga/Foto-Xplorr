package com.fotoxplorr.app.viewer.sphere

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 360 view's root is a pointer target that keeps touches from the viewer beneath, and consumes
 * nothing, so its own buttons still click when the finger jitters between down and up (a real
 * touchscreen's ACTION_MOVE). A root that consumed on the Final pass ran BEFORE the buttons' Final
 * check (Final goes ancestor first) and cancelled every such tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
class SphereTouchTest {
    @get:Rule
    val compose = createComposeRule()

    // Unreadable on purpose: the screen shows its one-line failure and the Close button.
    private val asset = MediaAsset(
        id = MediaId(1L),
        contentUriString = "content://com.fotoxplorr.test.missing/sphere/1",
        displayName = "PANO.jpg",
        mimeType = "image/jpeg",
        bucketName = null,
        dateTakenMillis = 0L,
        dateModifiedSeconds = 0L,
        width = 4096,
        height = 2048,
        sizeBytes = 1L,
        relativePath = null,
        isFavorite = false,
        isTrashed = false,
    )

    @Test
    fun `close still fires when the finger moves a little between down and up`() {
        var closes = 0
        compose.setContent { SphereScreen(asset = asset, onClose = { closes++ }) }
        compose.onNodeWithContentDescription(SphereCopy.CLOSE).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(3f, 2f))
            moveBy(androidx.compose.ui.geometry.Offset(-2f, 1f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun `a touch on the sphere never reaches the viewer beneath`() {
        var beneath = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().testTag("beneath").pointerInput(Unit) {
                        awaitPointerEventScope { while (true) { awaitPointerEvent(); beneath++ } }
                    },
                )
                SphereScreen(asset = asset, onClose = {})
            }
        }
        compose.onNodeWithTag(SPHERE_TAG).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(40f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(0, beneath)
    }
}
