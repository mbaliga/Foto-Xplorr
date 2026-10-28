package com.fotoxplorr.app.board

import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.fotoxplorr.app.board.BoardTestKit.BLUE
import com.fotoxplorr.app.board.BoardTestKit.RED
import com.fotoxplorr.app.board.BoardTestKit.layer
import com.fotoxplorr.app.editor.MaxEdgePreset
import com.fotoxplorr.app.editor.OutputFormat
import com.fotoxplorr.app.gallery.GalleryPreferencesState
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.ui.FotoXplorrTheme
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mood board screen on screen, over an in-memory library and flat-colour photos: adding, nudging and
 * restyling with the buttons (the way to do everything without a drag), a few real touch gestures on the
 * board, undo and redo, Back stepping inside the screen, the saved-boards list, what survives the process,
 * and the export as a job with its refusals and its Cancel. Clicks are injected and nothing here says how
 * the board looks or feels; those are device checks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h2400dp-xhdpi")
class MoodBoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class BackOwner : OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    private val owner = BackOwner()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val library = MemoryBoardLibrary()
    private val source = FakePhotoSource(mapOf(1L to RED, 2L to BLUE, 3L to RED))
    private val sink = FakeBoardSink()
    private var state: MoodBoardState? = null
    private val board: Board? get() = state?.open?.board
    private var closed = false
    private val opened = mutableListOf<MediaAsset>()

    @After
    fun tearDown() = scope.cancel()

    private fun photo(id: Long, name: String) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = id * 1000, dateModifiedSeconds = 0,
        width = 400, height = 300, sizeBytes = id * 100, relativePath = "DCIM/Camera/", isFavorite = false, isTrashed = false,
    )

    private val a = photo(1, "a.jpg")
    private val b = photo(2, "b.jpg")
    private val c = photo(3, "c.jpg")
    private val gallery = listOf(c, b, a)

    private var relinkTo: ((Board) -> Board) = { it }

    @Composable
    private fun Screen(
        assets: List<MediaAsset> = gallery,
        runner: JobRunner? = JobRunner(scope),
    ) {
        FotoXplorrTheme(GalleryPreferencesState()) {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                MoodBoardScreen(
                    assets = assets,
                    newSource = { source },
                    relink = { relinkTo(it) },
                    library = library,
                    newSink = { sink },
                    runner = runner,
                    onOpenAsset = { asset, _ -> opened += asset },
                    onClose = { closed = true },
                    autosaveMillis = 50L,
                    onState = { state = it },
                )
            }
        }
    }

    /**
     * Clicks the node, after scrolling it into view when it sits in a scrolling panel (a click on a node the
     * panel has scrolled out of sight lands on nothing), and lets the recomposition it causes finish before
     * the next step reads or touches anything.
     */
    private fun act(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
        return node
    }

    private fun tapText(text: String) = act(compose.onNodeWithText(text))

    private fun press(description: String) = act(compose.onNodeWithContentDescription(description))

    private fun pressBack() = compose.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }

    private fun startNew() {
        compose.setContent { Screen() }
        tapText(BoardCopy.NEW_BOARD)
    }

    private fun addColour() {
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_COLOUR)
        tapText(BoardCopy.TOOL_ADD) // closes the panel again
    }

    private fun layers() = board!!.layers

    private fun waitFor(timeoutMillis: Long = 60_000, condition: () -> Boolean) =
        compose.waitUntil(timeoutMillis) { shadowOf(Looper.getMainLooper()).idle(); condition() }

    private fun canvas(): SemanticsNodeInteraction = compose.onNode(hasContentDescription("Board, ", substring = true))

    private fun boardWords(): String {
        val node = canvas().fetchSemanticsNode()
        return node.config[SemanticsProperties.ContentDescription].first()
    }

    // ---- the pages ----

    @Test
    fun `it opens on the list, and New board opens an empty editor`() {
        compose.setContent { Screen() }
        compose.onNodeWithText(BoardCopy.NEW_BOARD).assertIsEnabled()
        compose.onNodeWithText(BoardCopy.NO_BOARDS).assertExists()
        compose.onNodeWithText(BoardCopy.SAVES_BY_ITSELF).assertExists()
        tapText(BoardCopy.NEW_BOARD)
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertIsNotEnabled()
        compose.onNodeWithContentDescription(BoardCopy.REDO).assertIsNotEnabled()
        assertEquals(0, layers().size)
        assertEquals("Board, 0 layers.", boardWords())
    }

    @Test
    fun `Back on the list is left to the host, and the screen takes none of its own`() {
        compose.setContent { Screen() }
        compose.waitForIdle()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        tapText(BoardCopy.NEW_BOARD)
        assertTrue(owner.onBackPressedDispatcher.hasEnabledCallbacks())
        press(BoardCopy.BACK)
        compose.onNodeWithText(BoardCopy.NEW_BOARD).assertExists()
        press(BoardCopy.BACK)
        assertTrue("the header's Back on the list closes the experiment", closed)
    }

    @Test
    fun `Back closes a panel first and then goes back to the list`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        compose.onNodeWithText(BoardCopy.ADD_COLOUR).assertExists()
        pressBack()
        compose.onNodeWithText(BoardCopy.ADD_COLOUR).assertDoesNotExist()
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertExists()
        pressBack()
        compose.onNodeWithText(BoardCopy.NEW_BOARD).assertExists()
        assertFalse(owner.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // ---- adding ----

    @Test
    fun `adding a colour block puts a layer on the board and picks it`() {
        startNew()
        addColour()
        assertEquals(1, layers().size)
        assertTrue(layers().single().content is LayerContent.Colour)
        assertEquals("Board, 1 layer. Colour 1 picked.", boardWords())
    }

    @Test
    fun `shapes and text are added in the colour chosen for new things`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        press(BoardCopy.COLOUR_BLUE)
        tapText(BoardCopy.shape(ShapeKind.TRIANGLE))
        compose.onNode(hasSetTextAction()).performTextInput("Hi there")
        tapText(BoardCopy.ADD_THE_TEXT)
        val shape = layers()[0].content as LayerContent.Shape
        assertEquals(ShapeKind.TRIANGLE, shape.kind)
        assertEquals(0xFF1976D2.toInt(), shape.fillArgb)
        val text = layers()[1].content as LayerContent.Text
        assertEquals("Hi there", text.text)
        assertEquals(0xFF1976D2.toInt(), text.argb)
        press(BoardCopy.COLOUR_BLUE).assertIsSelected()
    }

    @Test
    fun `photos are picked in a grid, numbered, and added together by the Add button`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        compose.onNodeWithText(BoardCopy.photosPicked(0)).assertExists()
        compose.onNodeWithText(BoardCopy.addPhotos(0)).assertIsNotEnabled()
        press("b.jpg")
        press("a.jpg")
        compose.onNodeWithText(BoardCopy.photosPicked(2)).assertExists()
        // Choosing did not close the picker.
        compose.onNodeWithText(BoardCopy.addPhotos(2)).assertIsEnabled()
        tapText(BoardCopy.addPhotos(2))
        assertEquals(listOf(2L, 1L), layers().map { (it.content as LayerContent.Photo).ref.mediaId })
        assertEquals("the photo's identity is kept for finding it again", "b.jpg", (layers()[0].content as LayerContent.Photo).ref.identity!!.displayName)
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertIsEnabled()
    }

    @Test
    fun `Back from the photo picker returns to the board with nothing added`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        press("a.jpg")
        pressBack()
        assertEquals(0, layers().size)
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertExists()
    }

    @Test
    fun `a library with no photos says so in the picker`() {
        compose.setContent { Screen(assets = emptyList()) }
        tapText(BoardCopy.NEW_BOARD)
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        compose.onNodeWithText(BoardCopy.NO_PHOTOS).assertExists()
    }

    // ---- the buttons, one by one ----

    @Test
    fun `the arrows nudge the picked layer and Centre brings it back`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        val start = layers().single().transform
        press(BoardCopy.NUDGE_RIGHT)
        press(BoardCopy.NUDGE_RIGHT)
        press(BoardCopy.NUDGE_DOWN)
        press(BoardCopy.NUDGE_LEFT)
        press(BoardCopy.NUDGE_UP)
        press(BoardCopy.NUDGE_UP)
        val t = layers().single().transform
        assertEquals(start.cx + 10f, t.cx, 0.01f)
        assertEquals(start.cy - 10f, t.cy, 0.01f)
        press(BoardCopy.NUDGE_RIGHT)
        tapText(BoardCopy.CENTRE)
        assertEquals(540f, layers().single().transform.cx, 0.01f)
        assertEquals(540f, layers().single().transform.cy, 0.01f)
    }

    @Test
    fun `size and turn have their steppers`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        press(BoardCopy.BIGGER)
        press(BoardCopy.BIGGER)
        assertEquals(1.21f, layers().single().transform.scale, 0.001f)
        compose.onNodeWithText(BoardCopy.sizeNow(1.21f)).assertExists()
        press(BoardCopy.SMALLER)
        assertEquals(1.1f, layers().single().transform.scale, 0.001f)
        press(BoardCopy.TURN_RIGHT_COARSE)
        press(BoardCopy.TURN_RIGHT_FINE)
        press(BoardCopy.TURN_LEFT_COARSE)
        assertEquals(1f, layers().single().transform.rotationDeg, 0.001f)
        compose.onNodeWithText(BoardCopy.turnNow(1f)).assertExists()
    }

    @Test
    fun `opacity steps down and up in five percent`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithText(BoardCopy.opacityNow(1f)).assertExists()
        press(BoardCopy.OPACITY_LESS)
        press(BoardCopy.OPACITY_LESS)
        assertEquals(0.9f, layers().single().opacity, 0f)
        compose.onNodeWithText(BoardCopy.opacityNow(0.9f)).assertExists()
        press(BoardCopy.OPACITY_MORE)
        assertEquals(0.95f, layers().single().opacity, 0f)
        compose.onNodeWithContentDescription(BoardCopy.OPACITY_MORE).assertIsEnabled()
    }

    @Test
    fun `a blend is chosen with a chip that says it is selected`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithText(BoardCopy.blend(LayerBlend.NORMAL)).assertIsSelected()
        tapText(BoardCopy.blend(LayerBlend.MULTIPLY))
        assertEquals(LayerBlend.MULTIPLY, layers().single().blend)
        compose.onNodeWithText(BoardCopy.blend(LayerBlend.MULTIPLY)).assertIsSelected()
        compose.onNodeWithText(BoardCopy.blend(LayerBlend.NORMAL)).assertExists()
    }

    @Test
    fun `order, duplicate, lock and delete`() {
        startNew()
        addColour()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        val top = layers().last().id
        tapText(BoardCopy.BACKWARD)
        assertEquals(top, layers().first().id)
        tapText(BoardCopy.TO_FRONT)
        assertEquals(top, layers().last().id)
        tapText(BoardCopy.DUPLICATE)
        assertEquals(3, layers().size)
        tapText(BoardCopy.DELETE)
        assertEquals(2, layers().size)
        assertEquals("Board, 2 layers.", boardWords())
    }

    @Test
    fun `a locked layer disables its buttons and says why`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        tapText(BoardCopy.LOCK)
        assertTrue(layers().single().locked)
        compose.onNodeWithText(BoardCopy.LOCK).assertIsOn()
        compose.onNodeWithContentDescription(BoardCopy.NUDGE_RIGHT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(BoardCopy.BIGGER).assertIsNotEnabled()
        compose.onNodeWithText(BoardCopy.DELETE).assertIsNotEnabled()
        compose.onNodeWithText(BoardCopy.LOCKED_NOTE).assertExists()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithContentDescription(BoardCopy.OPACITY_LESS).assertIsNotEnabled()
        tapText(BoardCopy.TOOL_LAYER)
        tapText(BoardCopy.LOCK)
        assertFalse(layers().single().locked)
        compose.onNodeWithContentDescription(BoardCopy.NUDGE_RIGHT).assertIsEnabled()
    }

    @Test
    fun `nothing picked says to pick a layer`() {
        startNew()
        tapText(BoardCopy.TOOL_LAYER)
        compose.onNodeWithText(BoardCopy.NOTHING_PICKED).assertExists()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithText(BoardCopy.NOTHING_PICKED).assertExists()
    }

    @Test
    fun `colour and text of the picked layer are changed from its panel`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        compose.onNode(hasSetTextAction()).performTextInput("Hello")
        tapText(BoardCopy.ADD_THE_TEXT)
        tapText(BoardCopy.TOOL_LAYER)
        press(BoardCopy.COLOUR_RED)
        tapText(BoardCopy.TEXT_BOLD)
        tapText(BoardCopy.ALIGN_CENTRE)
        press(BoardCopy.TEXT_BIGGER)
        val text = layers().single().content as LayerContent.Text
        assertEquals(0xFFD32F2F.toInt(), text.argb)
        assertTrue(text.bold)
        assertEquals(TextAlign.CENTRE, text.align)
        assertEquals(64f * 1.1f, text.sizeUnits, 0.01f)
        compose.onNodeWithText(BoardCopy.ALIGN_CENTRE).assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextReplacement("Hey")
        assertEquals("Hey", (layers().single().content as LayerContent.Text).text)
    }

    // ---- masks ----

    @Test
    fun `masks are chosen from chips and a preset shape is one tap`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LOOK)
        tapText(BoardCopy.MASK_OVAL)
        assertEquals(MaskShape.Ellipse, layers().single().mask.shape)
        compose.onNodeWithText(BoardCopy.MASK_OVAL).assertIsSelected()
        tapText(BoardCopy.MASK_ROUNDED)
        assertTrue(layers().single().mask.shape is MaskShape.RoundedRect)
        press(BoardCopy.RADIUS_MORE)
        assertEquals(0.2f, (layers().single().mask.shape as MaskShape.RoundedRect).radius, 0f)
        tapText(BoardCopy.MASK_SHAPE)
        assertTrue(layers().single().mask.shape is MaskShape.Polygon)
        tapText(BoardCopy.PRESET_STAR)
        assertEquals(10, (layers().single().mask.shape as MaskShape.Polygon).points.size)
        press(BoardCopy.FEATHER_MORE)
        press(BoardCopy.FEATHER_MORE)
        assertEquals(0.1f, layers().single().mask.feather, 0f)
        tapText(BoardCopy.MASK_NONE)
        assertEquals(MaskShape.Full, layers().single().mask.shape)
        assertEquals("a feather stays when the shape goes", 0.1f, layers().single().mask.feather, 0f)
    }

    @Test
    fun `a custom shape is made by tapping its corners on the board`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LOOK)
        tapText(BoardCopy.MASK_SHAPE)
        tapText(BoardCopy.DRAW_BY_TAPS)
        compose.onNodeWithText(BoardCopy.TAP_POINTS).assertExists()
        compose.onNodeWithText(BoardCopy.DONE).assertIsNotEnabled()
        val size = canvas().fetchSemanticsNode().size
        val cx = size.width / 2f
        val cy = size.height / 2f
        val reach = size.width * 0.1f
        for (p in listOf(Offset(cx, cy - reach), Offset(cx + reach, cy + reach), Offset(cx - reach, cy + reach))) {
            canvas().performTouchInput { click(p) }
        }
        compose.onNodeWithText(BoardCopy.pointsNow(3)).assertExists()
        compose.onNodeWithText(BoardCopy.DONE).assertIsEnabled()
        tapText(BoardCopy.UNDO_POINT)
        compose.onNodeWithText(BoardCopy.pointsNow(2)).assertExists()
        canvas().performTouchInput { click(Offset(cx, cy + reach)) }
        tapText(BoardCopy.DONE)
        val polygon = layers().single().mask.shape as MaskShape.Polygon
        assertEquals(3, polygon.points.size)
        assertTrue(polygon.points.all { it.x in 0f..1f && it.y in 0f..1f })
        compose.onNodeWithText(BoardCopy.TAP_POINTS).assertDoesNotExist()
    }

    @Test
    fun `a subject mask is made by tapping the photo, and only a photo can have one`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithText(BoardCopy.MASK_SUBJECT).assertIsNotEnabled()
        compose.onNodeWithText(BoardCopy.SUBJECT_PHOTOS_ONLY).assertExists()

        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        press("a.jpg")
        tapText(BoardCopy.addPhotos(1))
        tapText(BoardCopy.TOOL_LOOK)
        tapText(BoardCopy.MASK_SUBJECT)
        compose.onNodeWithText(BoardCopy.MARK_HINT).assertExists()
        val photo = layers().last()
        val size = canvas().fetchSemanticsNode().size
        val ppu = size.width / 1080f
        val at = Offset(photo.transform.cx * ppu, photo.transform.cy * ppu)
        canvas().performTouchInput { click(at) }
        tapText(BoardCopy.MARK_NOT_SUBJECT)
        canvas().performTouchInput { click(at + Offset(10f, 10f)) }
        val ops = (layers().last().mask.shape as MaskShape.Subject).ops
        assertEquals(2, ops.size)
        assertTrue((ops[0] as com.fotoxplorr.app.lift.SelectionOp.Hint).foreground)
        assertFalse((ops[1] as com.fotoxplorr.app.lift.SelectionOp.Hint).foreground)
        compose.onNodeWithText(BoardCopy.marksNow(2)).assertExists()
        tapText(BoardCopy.UNDO_MARK)
        assertEquals(1, (layers().last().mask.shape as MaskShape.Subject).ops.size)
        tapText(BoardCopy.CLEAR_MARKS)
        assertEquals(0, (layers().last().mask.shape as MaskShape.Subject).ops.size)
    }

    // ---- canvas, background, layers ----

    @Test
    fun `the canvas shape and background are chosen from their panel`() {
        startNew()
        tapText(BoardCopy.TOOL_CANVAS)
        compose.onNodeWithText(BoardCopy.canvas(CanvasPreset.SQUARE)).assertIsSelected()
        tapText(BoardCopy.canvas(CanvasPreset.STORY_9_16))
        assertEquals(CanvasPreset.STORY_9_16.size, board!!.canvas)
        compose.onNodeWithText(BoardCopy.canvas(CanvasPreset.STORY_9_16)).assertIsSelected()
        press(BoardCopy.COLOUR_TEAL)
        assertEquals(BoardBackground.Colour(0xFF00897B.toInt()), board!!.background)
        tapText(BoardCopy.TRANSPARENT)
        assertEquals(BoardBackground.Transparent, board!!.background)
        compose.onNodeWithText(BoardCopy.TRANSPARENT_NOTE).assertExists()
    }

    @Test
    fun `the layer list picks a layer, reorders it, and locks it`() {
        startNew()
        addColour()
        addColour()
        tapText(BoardCopy.TOOL_LAYERS)
        val ids = layers().map { it.id }
        val topName = BoardCopy.layerName(layers()[1], 2)
        val bottomName = BoardCopy.layerName(layers()[0], 1)
        compose.onNodeWithText(topName).assertIsSelected()
        compose.onNodeWithText(bottomName).performClick()
        compose.onNodeWithText(bottomName).assertIsSelected()
        assertEquals("Board, 2 layers. $bottomName picked.", boardWords())
        press(BoardCopy.forwardLayer(bottomName))
        assertEquals(ids.reversed(), layers().map { it.id })
        press(BoardCopy.lockLayer(BoardCopy.layerName(layers()[0], 1)))
        assertTrue(layers()[0].locked)
        compose.onNodeWithText(BoardCopy.STATUS_LOCKED, substring = true).assertExists()
    }

    @Test
    fun `an empty board says so in the layer list`() {
        startNew()
        tapText(BoardCopy.TOOL_LAYERS)
        compose.onNodeWithText(BoardCopy.NO_LAYERS).assertExists()
    }

    // ---- undo and redo ----

    @Test
    fun `undo and redo step through the edits and a held key is one step`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        repeat(3) { press(BoardCopy.NUDGE_RIGHT) }
        val moved = layers().single().transform.cx
        press(BoardCopy.UNDO)
        assertEquals(moved - 30f, layers().single().transform.cx, 0.01f)
        press(BoardCopy.UNDO)
        assertEquals(0, layers().size)
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertIsNotEnabled()
        press(BoardCopy.REDO)
        press(BoardCopy.REDO)
        assertEquals(moved, layers().single().transform.cx, 0.01f)
        compose.onNodeWithContentDescription(BoardCopy.REDO).assertIsNotEnabled()
    }

    // ---- fingers on the board ----

    @Test
    fun `a tap on a layer picks it and a tap on the empty board lets go`() {
        startNew()
        addColour()
        val size = canvas().fetchSemanticsNode().size
        canvas().performTouchInput { click(Offset(4f, 4f)) }
        assertEquals("Board, 1 layer.", boardWords())
        canvas().performTouchInput { click(Offset(size.width / 2f, size.height / 2f)) }
        assertEquals("Board, 1 layer. Colour 1 picked.", boardWords())
        assertEquals("a tap changes nothing on the board", 1, layers().size)
    }

    @Test
    fun `a thin layer is picked by a tap within a finger's width of it`() {
        library.put("Thin", Board(layers = listOf(layer(1, 540f, 540f, w = 600f, h = 4f)), nextLayerId = 2))
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText("Thin")).fetchSemanticsNodes().isNotEmpty() }
        tapText("Thin")
        waitFor { board != null }
        val size = canvas().fetchSemanticsNode().size
        val ppu = size.width / 1080f
        // 8 px above the line: 14 units, far more than its 2 units of half height, less than the touch margin.
        canvas().performTouchInput { click(Offset(size.width / 2f, 540f * ppu - 8f)) }
        assertEquals("Board, 1 layer. ${BoardCopy.layerName(layers().single(), 1)} picked.", boardWords())
        // Well clear of it, nothing is picked.
        canvas().performTouchInput { click(Offset(size.width / 2f, 540f * ppu - 120f)) }
        assertEquals("Board, 1 layer.", boardWords())
    }

    @Test
    fun `dragging a layer moves it, and the whole drag is one undo step`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        tapText(BoardCopy.SNAP) // off, so the move is exactly the finger's
        val size = canvas().fetchSemanticsNode().size
        val ppu = size.width / 1080f
        val start = layers().single().transform
        val from = Offset(size.width / 2f, size.height / 2f)
        canvas().performTouchInput {
            down(from)
            moveBy(Offset(60f, 20f))
            moveBy(Offset(40f, 0f))
            up()
        }
        val t = layers().single().transform
        assertEquals(start.cx + 100f / ppu, t.cx, 2f)
        assertEquals(start.cy + 20f / ppu, t.cy, 2f)
        press(BoardCopy.UNDO)
        assertEquals(start.cx, layers().single().transform.cx, 0.01f)
        assertEquals("the add is still there", 1, layers().size)
    }

    @Test
    fun `a drag from a layer that is not picked moves that layer and picks it`() {
        startNew()
        addColour()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        tapText(BoardCopy.SNAP)
        // The second layer is picked: send it 100 units right, clear of the first's reach.
        repeat(10) { press(BoardCopy.NUDGE_RIGHT) }
        val first = layers()[0]
        val second = layers()[1]
        tapText(BoardCopy.TOOL_LAYERS)
        compose.onNodeWithText(BoardCopy.layerName(first, 1)).performClick()
        compose.onNodeWithText(BoardCopy.layerName(first, 1)).assertIsSelected()
        val size = canvas().fetchSemanticsNode().size
        val ppu = size.width / 1080f
        // Only the second layer is here: right of the first's edge and its touch margin.
        val from = Offset((second.transform.cx + second.drawnWidth / 2f - 20f) * ppu, second.transform.cy * ppu)
        canvas().performTouchInput {
            down(from)
            moveBy(Offset(-50f, 0f))
            moveBy(Offset(-30f, 0f))
            up()
        }
        assertEquals("the second layer moved", second.transform.cx - 80f / ppu, layers()[1].transform.cx, 2f)
        assertEquals("and the first did not", first.transform, layers()[0].transform)
        assertEquals("Board, 2 layers. ${BoardCopy.layerName(layers()[1], 2)} picked.", boardWords())
    }

    @Test
    fun `two fingers spreading make the picked layer bigger`() {
        startNew()
        addColour()
        val size = canvas().fetchSemanticsNode().size
        val c = Offset(size.width / 2f, size.height / 2f)
        canvas().performTouchInput {
            pinch(c + Offset(-40f, 0f), c + Offset(-100f, 0f), c + Offset(40f, 0f), c + Offset(100f, 0f))
        }
        val scale = layers().single().transform.scale
        assertTrue("the scale grew to $scale", scale > 1.8f && scale < 3.2f)
        press(BoardCopy.UNDO)
        assertEquals("a pinch is one step", 1f, layers().single().transform.scale, 0.001f)
    }

    @Test
    fun `a locked layer is not picked by a tap and cannot be dragged`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_LAYER)
        tapText(BoardCopy.LOCK)
        val size = canvas().fetchSemanticsNode().size
        val from = Offset(size.width / 2f, size.height / 2f)
        val before = layers().single().transform
        canvas().performTouchInput { down(from); moveBy(Offset(80f, 0f)); up() }
        assertEquals(before, layers().single().transform)
    }

    // ---- saving and the list ----

    @Test
    fun `a board saves itself, shows in the list, opens again and can be deleted`() {
        startNew()
        addColour()
        waitFor { library.entries.isNotEmpty() }
        val id = library.entries.keys.single()
        assertEquals(1, library.entries.getValue(id).board.layers.size)
        tapText(BoardCopy.TOOL_LAYER)
        press(BoardCopy.NUDGE_RIGHT)
        waitFor { library.entries.getValue(id).board.layers.single().transform.cx > 540f }
        assertEquals("still one file", 1, library.entries.size)

        press(BoardCopy.BACK) // closes the panel
        press(BoardCopy.BACK)
        waitFor { compose.onAllNodes(hasText(BoardCopy.boardSummary(1, BoardCopy.canvas(CanvasPreset.SQUARE)))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BoardCopy.DEFAULT_NAME).assertExists()

        compose.onNodeWithText(BoardCopy.DEFAULT_NAME).performClick()
        waitFor { board != null && board!!.layers.size == 1 }
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertIsNotEnabled()
        assertEquals("opened without a pick", "Board, 1 layer.", boardWords())

        press(BoardCopy.BACK)
        waitFor { compose.onAllNodes(hasContentDescription(BoardCopy.deleteBoard(BoardCopy.DEFAULT_NAME))).fetchSemanticsNodes().isNotEmpty() }
        press(BoardCopy.deleteBoard(BoardCopy.DEFAULT_NAME))
        compose.onNodeWithText(BoardCopy.DELETE_ASK).assertExists()
        tapText(BoardCopy.DELETE_KEEP)
        assertEquals(1, library.entries.size)
        press(BoardCopy.deleteBoard(BoardCopy.DEFAULT_NAME))
        tapText(BoardCopy.DELETE)
        waitFor { library.entries.isEmpty() }
        waitFor { compose.onAllNodes(hasText(BoardCopy.NO_BOARDS)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a new board that is left empty makes no file`() {
        startNew()
        press(BoardCopy.BACK)
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        assertTrue(library.entries.isEmpty())
        assertTrue(library.saves.isEmpty())
    }

    @Test
    fun `renaming a board changes its name in the file`() {
        startNew()
        addColour()
        // The name in the title bar opens the board's own panel, where the name is a text field.
        tapText(BoardCopy.DEFAULT_NAME)
        compose.onNodeWithText(BoardCopy.CANVAS_SHAPE).assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("Spring")
        waitFor { library.entries.values.any { it.name == "Spring" } }
        // The title bar and the field both say it.
        compose.onAllNodesWithText("Spring").assertCountEquals(2)
        // Emptied, the title shows the default name and the file gets it too.
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        waitFor { library.entries.values.any { it.name == BoardCopy.DEFAULT_NAME } }
    }

    @Test
    fun `a board that could not be fully read says so when it is opened`() {
        library.put("Odd", Board(), decoded = BoardJson.Decoded(Board(layers = listOf(layer(1, 100f, 100f))), 2, 0, false))
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText("Odd")).fetchSemanticsNodes().isNotEmpty() }
        tapText("Odd")
        waitFor { board != null }
        compose.onNodeWithText("Could not read 2 layers.").assertExists()
    }

    @Test
    fun `photos that moved are pointed at their new rows when a board is opened`() {
        val photoLayer = layer(1, 300f, 300f, content = BoardTestKit.photo(7))
        library.put("With a photo", Board(layers = listOf(photoLayer), nextLayerId = 2))
        relinkTo = { b -> b.copy(layers = b.layers.map { it.copy(content = LayerContent.Photo((it.content as LayerContent.Photo).ref.copy(mediaId = 2))) }) }
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText("With a photo")).fetchSemanticsNodes().isNotEmpty() }
        tapText("With a photo")
        waitFor { board != null }
        assertEquals(2L, (layers().single().content as LayerContent.Photo).ref.mediaId)
    }

    @Test
    fun `without a job list there is no Save, and the panel does not pretend there is`() {
        compose.setContent { Screen(runner = null) }
        tapText(BoardCopy.NEW_BOARD)
        addColour()
        openExport()
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsNotEnabled()
    }

    @Test
    fun `a damaged board cannot be opened but can be deleted`() {
        library.put("x", Board(), readable = false)
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText(BoardCopy.DAMAGED_BOARD)).fetchSemanticsNodes().isNotEmpty() }
        press(BoardCopy.deleteBoard(BoardCopy.DAMAGED_BOARD))
        tapText(BoardCopy.DELETE)
        waitFor { library.entries.isEmpty() }
    }

    @Test
    fun `a board that vanished between the list and opening says so`() {
        val id = library.put("Gone", Board())
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText("Gone")).fetchSemanticsNodes().isNotEmpty() }
        library.entries.remove(id)
        tapText("Gone")
        waitFor { compose.onAllNodes(hasText(BoardCopy.COULD_NOT_OPEN)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `at the limit a new board is refused with a line that says to delete one`() {
        repeat(BoardStore.MAX_BOARDS) { library.put("B$it", Board()) }
        compose.setContent { Screen() }
        waitFor { compose.onAllNodes(hasText(BoardCopy.LIST_FULL)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BoardCopy.NEW_BOARD).assertIsNotEnabled()
    }

    @Test
    fun `a save that is refused or fails is said in a line on the board`() {
        library.result = { BoardSave.Failed }
        startNew()
        addColour()
        waitFor { compose.onAllNodes(hasText(BoardCopy.COULD_NOT_SAVE)).fetchSemanticsNodes().isNotEmpty() }
        library.result = null
        addColour()
        waitFor { compose.onAllNodes(hasText(BoardCopy.COULD_NOT_SAVE)).fetchSemanticsNodes().isEmpty() }
    }

    // ---- what survives ----

    @Test
    fun `the open board, its pick and its panel survive the activity being recreated`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        tapText(BoardCopy.NEW_BOARD)
        addColour()
        addColour()
        tapText(BoardCopy.TOOL_CANVAS)
        tapText(BoardCopy.canvas(CanvasPreset.PORTRAIT_4_5))
        val before = board

        tester.emulateSavedInstanceStateRestore()

        assertEquals(before, board)
        assertEquals("Board, 2 layers. ${BoardCopy.layerName(layers()[1], 2)} picked.", boardWords())
        compose.onNodeWithText(BoardCopy.canvas(CanvasPreset.PORTRAIT_4_5)).assertIsSelected()
        assertTrue("and Back still has its steps", owner.onBackPressedDispatcher.hasEnabledCallbacks())
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertIsNotEnabled()
    }

    @Test
    fun `leaving the list open survives too, and a restored board is saved under its own file`() {
        val tester = StateRestorationTester(compose)
        tester.setContent { Screen() }
        tapText(BoardCopy.NEW_BOARD)
        addColour()
        waitFor { library.entries.size == 1 }
        tester.emulateSavedInstanceStateRestore()
        tapText(BoardCopy.TOOL_LAYER)
        press(BoardCopy.NUDGE_DOWN)
        waitFor { library.entries.values.single().board.layers.single().transform.cy > 540f }
        assertEquals("the restored board went on using its file", 1, library.entries.size)
    }

    // ---- exporting ----

    private fun openExport() {
        tapText(BoardCopy.EXPORT)
    }

    @Test
    fun `saving makes one new picture, shows how it went, and offers to open it`() {
        startNew()
        addColour()
        openExport()
        compose.onNodeWithText(BoardCopy.FORMAT_JPEG).assertIsSelected()
        tapText(BoardCopy.FORMAT_PNG)
        compose.onNodeWithText(BoardCopy.FORMAT_PNG).assertIsSelected()
        tapText(MaxEdgePreset.SMALL.label)
        compose.onNodeWithText(BoardCopy.aboutSize(1024, 1024)).assertExists()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.SAVED)).fetchSemanticsNodes().isNotEmpty() }
        val p = sink.published.single()
        assertEquals(OutputFormat.PNG, p.format)
        assertEquals(1024, p.width)
        assertEquals(BoardCopy.DEFAULT_NAME, p.baseName)
        compose.onNodeWithText(BoardCopy.sizeNote(1024, 1024, false)).assertExists()
    }

    @Test
    fun `until the gallery lists the new picture it says so, and then Open is offered`() {
        compose.setContent { Screen(assets = emptyList()) }
        tapText(BoardCopy.NEW_BOARD)
        addColour()
        openExport()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.SAVED)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BoardCopy.NOT_LISTED_YET).assertExists()
        compose.onNodeWithText(BoardCopy.OPEN_IT).assertDoesNotExist()
    }

    @Test
    fun `once the gallery lists the new picture Open takes the person to it`() {
        startNew()
        addColour()
        openExport()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.SAVED)).fetchSemanticsNodes().isNotEmpty() }
        // The fake sink's uri ends in row 1, and the gallery list holds a photo with that row number.
        tapText(BoardCopy.OPEN_IT)
        assertEquals(listOf(a), opened)
    }

    @Test
    fun `a see-through board offers only PNG and says why`() {
        startNew()
        addColour()
        tapText(BoardCopy.TOOL_CANVAS)
        tapText(BoardCopy.TRANSPARENT)
        openExport()
        compose.onNodeWithText(BoardCopy.PNG_NEEDED).assertExists()
        compose.onNodeWithText(BoardCopy.FORMAT_PNG).assertIsSelected()
        compose.onNodeWithText(BoardCopy.FORMAT_JPEG).assertIsNotEnabled()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { sink.published.isNotEmpty() }
        assertEquals(OutputFormat.PNG, sink.published.single().format)
    }

    @Test
    fun `Save is off for an empty board`() {
        startNew()
        openExport()
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsNotEnabled()
    }

    @Test
    fun `a photo that cannot be used is named before saving and the button waits`() {
        source.available = { false }
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        press("a.jpg")
        tapText(BoardCopy.addPhotos(1))
        openExport()
        compose.onNodeWithText(BoardCopy.MISSING_PHOTOS_NOTE).assertExists()
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsNotEnabled()
        tapText(BoardCopy.SHOW_MISSING)
        compose.onNodeWithText(BoardCopy.MISSING_PHOTO, substring = true).assertExists()
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a photo that became protected after the panel was opened is refused with nothing saved`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        press("a.jpg")
        tapText(BoardCopy.addPhotos(1))
        openExport()
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsEnabled()
        source.available = { false }
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.problem(BoardExportProblem.PHOTOS_UNAVAILABLE))).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(sink.published.isEmpty())
        compose.onNodeWithText(BoardCopy.SHOW_MISSING).assertExists()
    }

    @Test
    fun `Cancel stops a save and nothing is left`() {
        val gate = CompletableDeferred<Unit>()
        sink.beforeLook = { gate.await() }
        startNew()
        addColour()
        openExport()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.SAVING)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsNotEnabled()
        tapText(BoardCopy.CANCEL)
        waitFor { compose.onAllNodes(hasText(BoardCopy.CANCELLED)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(sink.published.isEmpty())
        compose.onNodeWithText(BoardCopy.SAVE_PICTURE).assertIsEnabled()
        gate.complete(Unit)
    }

    @Test
    fun `leaving during a save is held once, then stops the save`() {
        val gate = CompletableDeferred<Unit>()
        sink.beforeLook = { gate.await() }
        startNew()
        addColour()
        openExport()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.SAVING)).fetchSemanticsNodes().isNotEmpty() }
        pressBack() // the panel
        pressBack() // held
        compose.onNodeWithText(BoardCopy.LEAVING_CANCELS).assertExists()
        compose.onNodeWithContentDescription(BoardCopy.UNDO).assertExists()
        pressBack() // leaves
        compose.onNodeWithText(BoardCopy.NEW_BOARD).assertExists()
        waitFor { sink.published.isEmpty() && compose.onAllNodes(hasText(BoardCopy.SAVING)).fetchSemanticsNodes().isEmpty() }
        gate.complete(Unit)
        // The stopped save's late "Cancelled." belongs to the board that was left, not to the next one.
        tapText(BoardCopy.NEW_BOARD)
        Thread.sleep(300)
        compose.waitForIdle()
        compose.onNodeWithText(BoardCopy.CANCELLED).assertDoesNotExist()
        assertTrue(sink.published.isEmpty())
    }

    @Test
    fun `a failure is told in words`() {
        sink.failWith = java.io.IOException("x")
        startNew()
        addColour()
        openExport()
        tapText(BoardCopy.SAVE_PICTURE)
        waitFor { compose.onAllNodes(hasText(BoardCopy.problem(BoardExportProblem.SAVE_FAILED))).fetchSemanticsNodes().isNotEmpty() }
    }

    // ---- the palette aid ----

    @Test
    fun `the colours of the photos are found on request and used as a background`() {
        startNew()
        tapText(BoardCopy.TOOL_COLOURS)
        compose.onNodeWithText(BoardCopy.FIND_COLOURS).assertIsNotEnabled()
        compose.onNodeWithText(BoardCopy.NO_PHOTO_COLOURS).assertExists()
        tapText(BoardCopy.TOOL_ADD)
        tapText(BoardCopy.ADD_PHOTO)
        press("b.jpg")
        tapText(BoardCopy.addPhotos(1))
        tapText(BoardCopy.TOOL_COLOURS)
        compose.onNodeWithText(BoardCopy.FIND_COLOURS).assertIsEnabled()
        tapText(BoardCopy.FIND_COLOURS)
        waitFor { compose.onAllNodes(hasContentDescription("#0000FF", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(BoardCopy.USE_AS_BACKGROUND).assertIsNotEnabled()
        compose.onNode(hasContentDescription("#0000FF", substring = true)).performClick()
        compose.onNode(hasContentDescription("#0000FF", substring = true)).assertIsSelected()
        tapText(BoardCopy.USE_AS_BACKGROUND)
        assertEquals(BoardBackground.Colour(BLUE), board!!.background)
        tapText(BoardCopy.ADD_AS_BLOCK)
        assertEquals(LayerContent.Colour(BLUE), layers().last().content)
        // The photos' colours are offered in the other pickers as well.
        tapText(BoardCopy.TOOL_CANVAS)
        assertNotNull(compose.onAllNodes(hasContentDescription("#0000FF", substring = true)).fetchSemanticsNodes().firstOrNull())
    }

    // ---- reaching everything with a screen reader and a thumb ----

    @Test
    fun `every button the editor offers is at least 48 dp`() {
        startNew()
        addColour()
        for (tool in listOf(BoardCopy.TOOL_ADD, BoardCopy.TOOL_LAYER, BoardCopy.TOOL_LOOK, BoardCopy.TOOL_LAYERS, BoardCopy.TOOL_CANVAS, BoardCopy.TOOL_COLOURS)) {
            compose.onNodeWithText(tool).assertHeightIsAtLeast(48.dp)
        }
        tapText(BoardCopy.TOOL_LAYER)
        for (d in listOf(
            BoardCopy.NUDGE_LEFT, BoardCopy.NUDGE_UP, BoardCopy.NUDGE_DOWN, BoardCopy.NUDGE_RIGHT, BoardCopy.BIGGER, BoardCopy.SMALLER,
            BoardCopy.TURN_LEFT_COARSE, BoardCopy.TURN_RIGHT_FINE, BoardCopy.UNDO, BoardCopy.REDO, BoardCopy.BACK,
        )) {
            val node = compose.onNodeWithContentDescription(d)
            node.assertHeightIsAtLeast(48.dp)
            node.assertWidthIsAtLeast(48.dp)
        }
        for (t in listOf(BoardCopy.CENTRE, BoardCopy.FORWARD, BoardCopy.TO_BACK, BoardCopy.LOCK, BoardCopy.DUPLICATE, BoardCopy.DELETE, BoardCopy.EXPORT)) {
            compose.onNodeWithText(t).assertHeightIsAtLeast(48.dp)
        }
        tapText(BoardCopy.TOOL_LOOK)
        for (d in listOf(BoardCopy.OPACITY_LESS, BoardCopy.OPACITY_MORE, BoardCopy.FEATHER_LESS)) {
            compose.onNodeWithContentDescription(d).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText(BoardCopy.blend(LayerBlend.OVERLAY)).assertHeightIsAtLeast(48.dp)
        tapText(BoardCopy.TOOL_CANVAS)
        compose.onNodeWithContentDescription(BoardCopy.COLOUR_PINK).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun `every colour swatch has a name for a screen reader`() {
        startNew()
        tapText(BoardCopy.TOOL_CANVAS)
        BoardColours.BASICS.forEach { compose.onNodeWithContentDescription(it.name).assertExists() }
        assertEquals(1, compose.onAllNodesWithContentDescription(BoardCopy.COLOUR_WHITE).fetchSemanticsNodes().size)
    }

    @Test
    fun `the tool chips say which one is open`() {
        startNew()
        tapText(BoardCopy.TOOL_ADD)
        compose.onNodeWithText(BoardCopy.TOOL_ADD).assertIsSelected()
        tapText(BoardCopy.TOOL_LOOK)
        compose.onNodeWithText(BoardCopy.TOOL_LOOK).assertIsSelected()
        compose.onNodeWithText(BoardCopy.TOOL_ADD).assertIsNotSelected()
    }

}
