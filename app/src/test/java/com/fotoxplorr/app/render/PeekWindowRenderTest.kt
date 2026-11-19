package com.fotoxplorr.app.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.gallery.PeekAction
import com.fotoxplorr.app.gallery.PeekCard
import com.fotoxplorr.app.gallery.peekActionSlots
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stays-open preview's card at the owner's reference size (2026-11-19): 392 dp on a 440 dp
 * phone, so 784 px at xhdpi, placed 24 dp in from the top left of a white screen.
 *
 * Two renders. `peek-window-mask` fills the photo with flat white on a white screen, so every
 * non-white pixel is chrome and its darkness is its coverage: the fidelity script measures the
 * outlines from it row by row and compares them with the reference's. `peek-window` fills the photo
 * with a gradient for the side-by-side look. Roborazzi runs in record mode here, so these prove
 * nothing by themselves; the comparison is the script's, and what a device draws is a device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class PeekWindowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(name: String, actions: List<PeekAction>, fill: Brush) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    PeekCard(
                        asset = asset,
                        blurred = false,
                        actions = actions,
                        animate = false,
                        side = 392.dp,
                        onAction = {},
                        onClose = {},
                        modifier = Modifier.align(Alignment.TopStart).offset(24.dp, 24.dp),
                        photo = { m -> Box(m.background(fill)) },
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/renders/$name.png")
    }

    @Test
    fun `the window chrome over a flat photo`() =
        render("peek-window-mask", peekActionSlots(movable = true, inTrash = false), Brush.linearGradient(listOf(Color.White, Color.White)))

    @Test
    fun `the window chrome over a gradient photo`() = render(
        "peek-window",
        peekActionSlots(movable = true, inTrash = false),
        Brush.linearGradient(listOf(Color(0xFF7FB2E5), Color(0xFFE8B07A), Color(0xFF5E8F5A))),
    )

    @Test
    fun `the window chrome for a source file`() = render(
        "peek-window-source",
        peekActionSlots(movable = false, inTrash = false),
        Brush.linearGradient(listOf(Color(0xFF7FB2E5), Color(0xFFE8B07A), Color(0xFF5E8F5A))),
    )

    private val asset = MediaAsset(
        id = MediaId(1),
        contentUriString = "content://media/external/images/media/1",
        displayName = "IMG_1.jpg",
        mimeType = "image/jpeg",
        bucketName = "Camera",
        bucketId = 100L,
        dateTakenMillis = 1_756_684_800_000L,
        dateModifiedSeconds = 1_756_684_800L,
        width = 4032,
        height = 3024,
        sizeBytes = 3_200_000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )
}
