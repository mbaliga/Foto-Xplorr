package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.render.noOpActions
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
 * The multi-select chrome keeps its behaviour while drawn to the owner's reference: every control
 * has a 48dp target whatever is drawn, the labels people and TalkBack rely on, the source-file
 * gate (ADR-020), the trash variant, and the count bar clearing the selection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w440dp-h956dp-xhdpi")
class SelectionOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private val selection = GallerySelection(selectedIds = setOf(MediaId(1L), MediaId(2L)), selecting = true)

    private fun asset(id: Long, sourceRef: String? = null) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/$id", displayName = "$id.jpg",
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = 0L, dateModifiedSeconds = 0L,
        width = 10, height = 10, sizeBytes = 1L, relativePath = null, isFavorite = false,
        isTrashed = false, sourceRef = sourceRef,
    )

    private fun show(
        inTrash: Boolean = false,
        assets: List<MediaAsset> = listOf(asset(1), asset(2)),
        onChange: (GallerySelection) -> Unit = {},
        actions: GalleryActions = noOpActions(),
        selection: GallerySelection = this.selection,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                FotoXplorrTheme(GalleryPreferencesState()) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        SelectionOverlay(selection, assets, inTrash, actions, onChange)
                    }
                }
            }
        }
    }

    private fun selected(n: Int) = GallerySelection(selectedIds = (1L..n).map { MediaId(it) }.toSet(), selecting = true)

    private fun SemanticsNodeInteraction.boundsDp(): androidx.compose.ui.geometry.Rect {
        val r = fetchSemanticsNode().boundsInRoot
        val d = compose.density.density
        return androidx.compose.ui.geometry.Rect(r.left / d, r.top / d, r.right / d, r.bottom / d)
    }

    private fun SemanticsNodeInteraction.overflows(): Boolean {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        // softWrap = false lays the paragraph out at the full constraint, so didOverflowWidth is
        // always set; what matters is whether the drawn width holds the whole run.
        val layout = results.single()
        return layout.size.width < kotlin.math.ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
    }

    /**
     * The count bar may reach left only as far as it does in the reference (x = 198): never over
     * the Copy glyph's target (which ends at 187), and never so far that the cut around the tab
     * eats the leading digits. The count and the unit must be whole, unclipped, inside the bar
     * and right of the cut.
     */
    private fun assertCountBarFits(count: Int) {
        val bar = compose.onNodeWithText("SELECTED", useUnmergedTree = false).boundsDp()
        val digits = compose.onNodeWithText("$count", useUnmergedTree = true)
        val unit = compose.onNodeWithText("SELECTED", useUnmergedTree = true)
        val copy = compose.onNodeWithContentDescription("Copy to folder").boundsDp()
        assertTrue("bar starts at ${bar.left}, left of 198", bar.left >= 197.5f)
        assertTrue("bar (${bar.left}) covers Copy's target (ends ${copy.right})", bar.left >= copy.right)
        // The cut around the tab reaches x ~ 205 at the digits' top; the digits start right of it.
        assertTrue("digits start at ${digits.boundsDp().left}, under the cut", digits.boundsDp().left >= 207f)
        assertTrue("unit ends at ${unit.boundsDp().right}, past the bar's end ${bar.right}", unit.boundsDp().right <= bar.right + 0.5f)
        assertTrue("digits overflow", !digits.overflows())
        assertTrue("unit overflows", !unit.overflows())
    }

    @Test
    fun `at the reference size the bar starts where the reference's does`() {
        show(selection = selected(9999))
        val bar = compose.onNodeWithText("SELECTED", useUnmergedTree = false).boundsDp()
        assertEquals(198f, bar.left, 1f)
        assertEquals(440f - 6.8f, bar.right, 0.5f)
        assertCountBarFits(9999)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `on a 360dp phone a 4-digit count fits without covering the tab`() {
        show(selection = selected(9999))
        assertCountBarFits(9999)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `on a 360dp phone a 2-digit count keeps the reference's type size`() {
        show(selection = selected(42))
        assertCountBarFits(42)
    }

    @Test
    @Config(qualifiers = "w393dp-h852dp-xhdpi")
    fun `on a 393dp phone 1000 selected fits`() {
        show(selection = selected(1000))
        assertCountBarFits(1000)
    }

    @Test
    fun `at font scale 1_3 the 4-digit count still fits`() {
        show(selection = selected(9999), fontScale = 1.3f)
        assertCountBarFits(9999)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `at font scale 2 on a 360dp phone the 4-digit count still fits`() {
        show(selection = selected(9999), fontScale = 2f)
        assertCountBarFits(9999)
    }

    /**
     * The outlines are screen-absolute (tab left, bar right, cut on the bar's left end), so an RTL
     * locale must get the same layout, not a mirrored one with the bar fused into the tab.
     */
    @Test
    fun `in an RTL locale the chrome is laid out exactly as in LTR`() {
        show(selection = selected(9999), direction = LayoutDirection.Rtl)
        val bar = compose.onNodeWithText("SELECTED", useUnmergedTree = false).boundsDp()
        assertEquals(198f, bar.left, 1f)
        assertEquals(440f - 6.8f, bar.right, 0.5f)
        // Glyph targets in slot order from the left: 48dp boxes centred on 71, 117, 163.
        listOf("Export as zip" to 47f, "Move to folder" to 93f, "Copy to folder" to 139f).forEach { (label, left) ->
            assertEquals(label, left, compose.onNodeWithContentDescription(label).boundsDp().left, 0.5f)
        }
        val trash = compose.onNodeWithContentDescription("Move to trash").boundsDp()
        assertTrue("trash centre ${trash.center.x} is not at the right", trash.center.x > 340f && trash.center.x < 372f)
        assertCountBarFits(9999)
        // The band of photograph between the tab's right flank and the bar's cut survives: at
        // y = 14..30 the 5.5dp gap is white (the test's backdrop), not black.
        val path = "build/renders/selection-rtl.png"
        compose.onRoot().captureRoboImage(path)
        val img = com.fotoxplorr.app.animate.Jdk.decode(java.io.File(path).readBytes())
        val d = compose.density.density
        for (y in 14..30 step 4) {
            val flank = 204.68f - 0.404f * y // the tab's right flank, from the reference
            val mid = flank + 2.75f
            val px = img.argb[(y * d).toInt() * img.width + (mid * d).toInt()]
            assertTrue("row $y: gap at x=$mid is ${Integer.toHexString(px)}, not the backdrop", (px and 0xFF) > 200)
        }
    }

    private fun SemanticsNodeInteraction.assertTargetAtLeast48() {
        val size = fetchSemanticsNode().size
        val density = compose.density.density
        assertTrue("touch target ${size.width / density} x ${size.height / density} dp", size.width >= 48.dp.value * density - 0.5f && size.height >= 48.dp.value * density - 0.5f)
    }

    private fun clickLabel(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label

    @Test
    fun `the three tab glyphs keep their labels and 48dp targets`() {
        show()
        listOf("Export as zip", "Move to folder", "Copy to folder").forEach { label ->
            val node = compose.onNodeWithContentDescription(label, useUnmergedTree = false)
            node.assertTargetAtLeast48()
            assertEquals(label, clickLabel(node))
        }
    }

    @Test
    fun `the count bar reads the count, clears on tap and is a 48dp target`() {
        var cleared: GallerySelection? = null
        show(onChange = { cleared = it })
        val bar = compose.onNodeWithText("SELECTED", useUnmergedTree = false)
        bar.assertTargetAtLeast48()
        assertEquals("Clear selection", clickLabel(bar))
        compose.onNodeWithText("2").assertExists()
        bar.performClick()
        assertEquals(0, cleared?.count)
    }

    @Test
    fun `the trash is a 48dp target labelled for what it does`() {
        show()
        val trash = compose.onNodeWithContentDescription("Move to trash")
        trash.assertTargetAtLeast48()
        assertEquals("Move to trash", clickLabel(trash))
        trash.assertIsEnabled()
    }

    @Test
    fun `in the trash the tab has no glyphs and the trash deletes permanently`() {
        var deleted: List<MediaAsset>? = null
        show(inTrash = true, actions = noOpActions().copy(onDeletePermanently = { deleted = it }))
        compose.onNodeWithContentDescription("Export as zip").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy to folder").assertDoesNotExist()
        val trash = compose.onNodeWithContentDescription("Delete permanently")
        assertEquals("Delete permanently", clickLabel(trash))
        trash.performClick()
        assertEquals(2, deleted?.size)
    }

    @Test
    fun `a selected source file hides Move and disables the trash`() {
        show(assets = listOf(asset(1), asset(2, sourceRef = "drive:x")))
        compose.onNodeWithContentDescription("Move to folder").assertDoesNotExist()
        compose.onNodeWithContentDescription("Export as zip").assertExists()
        compose.onNodeWithContentDescription("Copy to folder").assertExists()
        compose.onNodeWithContentDescription("Move to trash").assertIsNotEnabled()
    }
}

/** The count bar's fitting rule, on its own: reference insets when they fit, then tighter, then smaller type. */
class CountBarFitTest {
    private val linear: (Float) -> Pair<Float, Float> = { k -> 69f * k to 94f * k }

    @Test
    fun `room enough keeps the reference insets and type`() {
        assertEquals(CountBarFit(COUNT_TEXT_START, COUNT_TEXT_END, 1f), fitCountBar(235.2f, linear))
    }

    @Test
    fun `a little short shrinks the insets, start first, and never past room`() {
        val fit = fitCountBar(200f, linear)
        assertEquals(1f, fit.scale, 0f)
        assertEquals(200f, fit.start + 69f + COUNT_UNIT_GAP + 94f + fit.end, 0.01f)
        assertTrue(fit.start >= COUNT_TEXT_START_MIN && fit.end >= COUNT_TEXT_END_MIN)
    }

    @Test
    fun `far too short shrinks the type to fit between the minimum insets`() {
        val fit = fitCountBar(155f, linear)
        assertTrue(fit.scale < 1f)
        val total = fit.start + (69f + 94f) * fit.scale + COUNT_UNIT_GAP + fit.end
        assertTrue("total $total", total <= 155f && total >= 154f)
    }

    @Test
    fun `non-linear font scaling is searched, not assumed proportional`() {
        // Widths that shrink more slowly than the scale, as Android 14's font scaling makes them.
        val curved: (Float) -> Pair<Float, Float> = { k -> val f = kotlin.math.sqrt(k); 90f * f to 110f * f }
        val fit = fitCountBar(155f, curved)
        val (c, u) = curved(fit.scale)
        assertTrue("scale ${fit.scale}", fit.scale < 0.6f)
        assertTrue(fit.start + c + COUNT_UNIT_GAP + u + fit.end <= 155f)
    }
}
