package com.fotoxplorr.app.viewer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.palette.PaletteMemo
import com.fotoxplorr.app.render.ViewerFixtures
import com.fotoxplorr.app.ui.FotoXplorrTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The info room's sections as the person meets them: folds, one-node rows, long-press copy and the
 * same copy offered to a screen reader. [InfoSections] on a hand-built model for the gestures (so a
 * section's contents are not what is under test), then the real [PhotoDetailRoom] for the wiring
 * (Show More opens them; a video's read reaches its section).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class InfoSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val model = MetadataModel(
        sections = listOf(
            MetaSection(
                SectionId.FILE,
                "File",
                listOf(
                    MetaRow("Name", "IMG_0767.HEIC"),
                    MetaRow("Size", "2.1 MB"),
                ),
                initiallyExpanded = true,
            ),
            MetaSection(
                SectionId.LOCATION,
                "Location",
                listOf(
                    MetaRow("Coordinates", "51.5007° N, 0.1246° W", copyText = "51.50070, -0.12460"),
                    MetaRow("Source", "From the photo"),
                ),
                initiallyExpanded = false,
            ),
        ),
        loading = false,
    )

    @Before
    fun setUp() {
        ViewerFixtures.installSynchronousImageLoader(context)
        PaletteMemo.clear()
    }

    @After
    fun tearDown() = PaletteMemo.clear()

    @Test
    fun `an open section shows its rows and a closed one shows only its header`() {
        sections(model)
        compose.onNodeWithContentDescription("Name, IMG_0767.HEIC").assertExists()
        compose.onNodeWithContentDescription("Size, 2.1 MB").assertExists()
        compose.onNodeWithContentDescription("Location").assertExists()
        compose.onAllNodesWithContentDescription("Coordinates", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a header says whether it is open and a tap folds or unfolds it`() {
        sections(model)
        assertEquals("Expanded", state("File"))
        assertEquals("Collapsed", state("Location"))

        compose.onNodeWithContentDescription("Location").performClick()
        compose.waitForIdle()
        assertEquals("Expanded", state("Location"))
        compose.onNodeWithContentDescription("Source, From the photo").assertExists()

        compose.onNodeWithContentDescription("File").performClick()
        compose.waitForIdle()
        assertEquals("Collapsed", state("File"))
        compose.onAllNodesWithContentDescription("Name, ", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a row is one node that reads label then value`() {
        sections(model)
        // The label and the value are not two stops: the node is the pair, and nothing under it speaks.
        compose.onAllNodesWithContentDescription("Name, IMG_0767.HEIC").assertCountEquals(1)
        compose.onAllNodesWithText("IMG_0767.HEIC").assertCountEquals(0)
        compose.onAllNodesWithText("IMG_0767.HEIC", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `headers and rows are at least 48 dp tall`() {
        sections(model)
        compose.onNodeWithContentDescription("File").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Name, IMG_0767.HEIC").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `a long press copies the row's copy text and the row says so`() {
        sections(model)
        compose.onNodeWithContentDescription("Name, IMG_0767.HEIC").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("IMG_0767.HEIC", clipboardText())
        compose.onNodeWithContentDescription("Name, copied").assertExists()
        compose.onAllNodesWithText("Copied", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `coordinates copy as a plain machine pair, not the display line`() {
        sections(model)
        compose.onNodeWithContentDescription("Location").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Coordinates, 51.5007° N, 0.1246° W").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("51.50070, -0.12460", clipboardText())
    }

    @Test
    fun `a screen reader gets the same copy as a long click and as a named action`() {
        sections(model)
        val node = compose.onNodeWithContentDescription("Size, 2.1 MB")
        val config = node.fetchSemanticsNode().config
        val longClick = config.getOrNull(SemanticsActions.OnLongClick)
        assertEquals("Copy Size", longClick?.label)
        val custom = config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        assertEquals(listOf("Copy Size"), custom.map { it.label })

        node.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals("2.1 MB", clipboardText())

        // And the named action, invoked as the reader's actions menu would.
        compose.onNodeWithContentDescription("Name, IMG_0767.HEIC").let {
            compose.runOnUiThread {
                it.fetchSemanticsNode().config[SemanticsActions.CustomActions].single().action()
            }
        }
        compose.waitForIdle()
        assertEquals("IMG_0767.HEIC", clipboardText())
    }

    @Test
    fun `a failed read reads as a failure and offers nothing to copy`() {
        sections(
            MetadataModel(
                listOf(
                    MetaSection(
                        SectionId.FILE,
                        "File",
                        listOf(MetaRow("Photo details", MetadataSections.COULDNT_READ, failure = true)),
                        initiallyExpanded = true,
                    ),
                ),
                loading = false,
            ),
        )
        val node = compose.onNodeWithContentDescription("Photo details, Couldn't read")
        val config = node.fetchSemanticsNode().config
        assertNull(config.getOrNull(SemanticsActions.OnLongClick))
        assertNull(config.getOrNull(SemanticsActions.CustomActions))
        compose.onAllNodesWithContentDescription("not recorded", substring = true).assertCountEquals(0)
    }

    @Test
    fun `while a read is still going the list says more is coming`() {
        sections(model.copy(loading = true))
        compose.onNodeWithText("Reading more details…").assertExists()
    }

    @Test
    fun `Show More opens the sections inside the real room and Show Less is not needed to see them`() {
        val photo = ViewerFixtures.referencePhoto(context)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = photo,
                    exif = ViewerFixtures.referenceExif.copy(readOk = true),
                    reveal = { 1f },
                    settled = true,
                    nearbyCount = 2,
                    informationInitiallyExpanded = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Name, IMG_0767.HEIC").assertCountEquals(0)

        compose.onNodeWithText("Show More").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Name, IMG_0767.HEIC").assertExists()
        // Library starts folded; its header is there, its row is not.
        compose.onNodeWithContentDescription("Library").assertExists()
        compose.onAllNodesWithContentDescription("Nearby in time", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Library").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Nearby in time, 2 others within a minute, in this folder").assertExists()
        // The reference's three rows are still above them, dashes and all.
        compose.onNodeWithText("Colour space").assertExists()
    }

    @Test
    fun `a video's read reaches the Video section and a pending one is not an absence`() {
        val video = ViewerFixtures.referencePhoto(context).copy(
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            durationMillis = 64_000L,
        )
        val read = VideoDetails(
            videoMime = "video/hevc",
            width = 1920,
            height = 1080,
            frameRate = 30.0,
            hasAudio = true,
            audioMime = "audio/mp4a-latm",
            audioChannels = 2,
        )
        var details by mutableStateOf<VideoDetails?>(VideoDetails.PENDING)
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                PhotoDetailRoom(
                    asset = video,
                    exif = ImageExifDetails(readOk = true),
                    video = details,
                    reveal = { 1f },
                    settled = true,
                    informationInitiallyExpanded = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Reading more details…").assertExists()
        compose.onAllNodesWithContentDescription("Video", substring = false).assertCountEquals(0)

        compose.runOnUiThread { details = read }
        compose.waitForIdle()
        compose.onAllNodesWithText("Reading more details…").assertCountEquals(0)
        compose.onNodeWithContentDescription("Video").assertExists()
        compose.onNodeWithContentDescription("Video").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Frame rate, 30 fps").assertExists()
        compose.onNodeWithContentDescription("Audio, AAC · 2 channels").assertExists()
        assertNotNull(state("Video"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun sections(model: MetadataModel) {
        compose.setContent {
            FotoXplorrTheme(GalleryPreferencesState()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                        InfoSections(model = model, resetKey = "photo")
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun state(header: String): String? =
        compose.onNode(hasContentDescription(header)).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)

    private fun clipboardText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }
}
