package com.fotoxplorr.app.vision

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The viewer's holder: naming from a decoded bitmap, what is dropped with the photo, and the choice it keeps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ColourVisionViewerStateTest {

    private val asset = ColourVisionKit.asset(id = 3)
    private val key = PhotoKey(asset)
    private val box = IntSize(1000, 1000)

    private val red = VisionTestKit.argb(230, 0, 0)
    private val blue = VisionTestKit.argb(0, 0, 230)

    /** Left half red, right half blue, 40 x 40. */
    private fun halves() = ColourVisionKit.bitmap(40, 40) { x, _ -> if (x < 20) red else blue }

    private fun loaded(naming: Boolean = true): ColourVisionViewer {
        val viewer = ColourVisionViewer(ColourVisionChoice(naming = naming))
        viewer.putOriginal(key, halves())
        return viewer
    }

    @Test
    fun `a tap names the colour of the decoded pixel under it`() {
        val viewer = loaded()
        assertTrue(viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f))
        assertEquals("red", viewer.name!!.label)
        assertEquals(red, viewer.name!!.argb)
        assertTrue(viewer.nameAt(key, Offset(750f, 500f), box, 1f, 0f, 0f, 0f))
        assertTrue(viewer.name!!.label, viewer.name!!.label.endsWith("blue"))
    }

    @Test
    fun `the name is of the file's colour, not of the filtered view`() {
        val viewer = loaded()
        viewer.update { it.withDeficiency(Deficiency.PROTAN) }
        viewer.putFiltered(FilteredPicture(FilterKey(Deficiency.PROTAN, VisionKind.SIMULATE, 10), halves()))
        viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f)
        assertEquals("red", viewer.name!!.label)
    }

    @Test
    fun `zoom and pan are undone before the pixel is read`() {
        val viewer = loaded()
        // Zoomed to 2x about the centre and moved 500 px left, the right half fills the box's left half: a tap at 250
        // lands on layer x = 500 + (250 - 500 + 500) / 2 = 625, which is blue.
        assertTrue(viewer.nameAt(key, Offset(250f, 500f), box, 2f, -500f, 0f, 0f))
        assertEquals(blue, viewer.name!!.argb)
    }

    @Test
    fun `a tap on the black around the picture is not a colour`() {
        val viewer = loaded()
        val wide = IntSize(1000, 2000)
        // 40 x 40 fitted to 1000 wide in a 2000 tall box: bars of 500 above and below.
        assertFalse(viewer.nameAt(key, Offset(500f, 100f), wide, 1f, 0f, 0f, 0f))
        assertNull(viewer.name)
        assertTrue(viewer.nameAt(key, Offset(250f, 1000f), wide, 1f, 0f, 0f, 0f))
    }

    @Test
    fun `with names off a tap is left to the viewer`() {
        val viewer = loaded(naming = false)
        assertFalse(viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f))
        assertNull(viewer.name)
    }

    @Test
    fun `before the photo is read a tap is left to the viewer`() {
        val viewer = ColourVisionViewer(ColourVisionChoice(naming = true))
        assertFalse(viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f))
    }

    @Test
    fun `a tap is never read from a different photo's pixels`() {
        val viewer = loaded()
        val other = PhotoKey(ColourVisionKit.asset(id = 4))
        assertFalse(viewer.holds(other))
        assertTrue(viewer.holds(key))
        assertFalse("the viewer has moved on but the old pixels are still held for a frame", viewer.nameAt(other, Offset(250f, 500f), box, 1f, 0f, 0f, 0f))
        // The same file changed on disk is another photo too.
        assertFalse(viewer.holds(PhotoKey(ColourVisionKit.asset(id = 3, modified = 999))))
    }

    @Test
    fun `turning names off clears the shown name`() {
        val viewer = loaded()
        viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f)
        assertNotNull(viewer.name)
        viewer.update { it.withNaming(false) }
        assertNull(viewer.name)
    }

    @Test
    fun `a washed-out colour is named with seems`() {
        val viewer = ColourVisionViewer(ColourVisionChoice(naming = true))
        viewer.putOriginal(key, ColourVisionKit.bitmap(10, 10) { _, _ -> VisionTestKit.argb(168, 176, 196) })
        viewer.nameAt(key, Offset(500f, 500f), box, 1f, 0f, 0f, 0f)
        assertTrue(viewer.name!!.label, viewer.name!!.label.startsWith("Seems "))
    }

    @Test
    fun `a new photo drops everything computed from the old one but keeps the choice`() {
        val viewer = loaded()
        viewer.update { it.withDeficiency(Deficiency.DEUTAN).withPatterns(true).withSeverityPosition(4f) }
        viewer.putFiltered(FilteredPicture(FilterKey(Deficiency.DEUTAN, VisionKind.SIMULATE, 4), halves()))
        viewer.putHatch(HatchState.NoneAlike)
        viewer.nameAt(key, Offset(250f, 500f), box, 1f, 0f, 0f, 0f)

        viewer.resetPhoto()

        assertNull(viewer.original)
        assertNull(viewer.originalKey)
        assertNull(viewer.filtered)
        assertNull(viewer.name)
        assertEquals(HatchState.Idle, viewer.hatch)
        assertEquals(Deficiency.DEUTAN, viewer.choice.deficiency)
        assertEquals(4, viewer.choice.severityStep)
        assertTrue(viewer.choice.patterns)
        assertTrue(viewer.choice.naming)
    }

    @Test
    fun `an unreadable photo leaves no key`() {
        val viewer = ColourVisionViewer(ColourVisionChoice())
        viewer.putOriginal(key, null)
        assertNull(viewer.originalKey)
        assertFalse(viewer.holds(key))
    }

    @Test
    fun `the saver keeps the choice and nothing else`() {
        val viewer = loaded()
        viewer.update { it.withDeficiency(Deficiency.TRITAN).withKind(VisionKind.HELP).withSeverityPosition(7f) }
        val saved = ColourVisionViewer.Saver.run { SaverScopeStub.save(viewer) }
        val back = ColourVisionViewer.Saver.restore(saved!!)!!
        assertEquals(viewer.choice, back.choice)
        assertNull(back.original)
        assertNull(back.filtered)
    }

    private object SaverScopeStub : androidx.compose.runtime.saveable.SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    // ---- the index belongs to the photo it was made from ----

    private fun indexOf(colour: Int) = ColourIndex.quantize(IntArray(16) { colour }, 4, 4, 2)

    @Test
    fun `an index made for another photo is not taken`() {
        val viewer = loaded()
        val other = PhotoKey(ColourVisionKit.asset(id = 4))
        viewer.putIndex(other, indexOf(red))
        assertNull("photo A's index must not land on photo B", viewer.indexFor(key))
        assertNull(viewer.index)
    }

    @Test
    fun `an index made after the viewer moved on is dropped`() {
        val viewer = loaded()
        val next = PhotoKey(ColourVisionKit.asset(id = 4))
        viewer.resetPhoto()
        viewer.putOriginal(next, halves())
        viewer.putIndex(key, indexOf(red))
        assertNull(viewer.indexFor(next))
        viewer.putIndex(next, indexOf(blue))
        assertNotNull(viewer.indexFor(next))
        assertNull("and it is for that photo only", viewer.indexFor(key))
    }

    @Test
    fun `a new photo drops the index and the names made from it`() {
        val viewer = loaded()
        viewer.putIndex(key, indexOf(red))
        viewer.putMainColours(listOf("red"))
        viewer.resetPhoto()
        assertNull(viewer.index)
        assertTrue(viewer.mainColours.isEmpty())
    }

    @Test
    fun `putting an original again clears what was made from the old one`() {
        val viewer = loaded()
        viewer.putIndex(key, indexOf(red))
        viewer.putOriginal(key, halves())
        assertNull(viewer.index)
    }

    // ---- a read that failed ----

    @Test
    fun `a failed read is remembered for that photo only, and cleared with it`() {
        val viewer = ColourVisionViewer(ColourVisionChoice(Deficiency.DEUTAN))
        assertFalse(viewer.failedToRead(key))
        viewer.markUnreadable(key)
        assertTrue(viewer.failedToRead(key))
        assertFalse(viewer.failedToRead(PhotoKey(ColourVisionKit.asset(id = 4))))
        viewer.resetPhoto()
        assertFalse(viewer.failedToRead(key))
    }
}
