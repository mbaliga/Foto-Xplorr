package com.fotoxplorr.app.pano

import com.fotoxplorr.app.editor.MAX_EXPORT_EDGE
import com.fotoxplorr.app.editor.MAX_EXPORT_PIXELS
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panorama screen's decisions with no Android: what is chosen and in what order, what is saved,
 * what the person is told when the job names a photo, the size shown before saving, and which
 * library row is the result. The screen itself is covered in [PanoramaScreenTest].
 */
class PanoramaDraftTest {

    private fun photo(id: Long, name: String = "p$id.jpg", taken: Long = id * 1000) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/images/media/$id", displayName = name,
        mimeType = "image/jpeg", bucketName = null, dateTakenMillis = taken, dateModifiedSeconds = 0,
        width = 4, height = 3, sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    private fun byId(vararg photos: MediaAsset) = photos.associateBy { it.id.value }

    // ---- choosing and ordering ------------------------------------------------------------------

    @Test
    fun `chosen photos are kept in date taken order, then name, until the person arranges them`() {
        val a = photo(1, "b.jpg", taken = 300)
        val b = photo(2, "a.jpg", taken = 100)
        val c = photo(3, "c.jpg", taken = 100)
        val all = byId(a, b, c)
        var draft = PanoramaDraft()
        // Picked newest first, the way the grid lists them.
        for (p in listOf(a, c, b)) draft = draft.toggled(p, all)
        assertEquals("date taken, then name", listOf(2L, 3L, 1L), draft.ids)
    }

    @Test
    fun `once arranged a new pick goes to the end and the order is the person's`() {
        val all = byId(photo(1), photo(2), photo(3), photo(4))
        var draft = PanoramaDraft().toggled(all.getValue(1), all).toggled(all.getValue(2), all).toggled(all.getValue(3), all)
        draft = draft.moved(0, +1)
        assertEquals(listOf(2L, 1L, 3L), draft.ids)
        assertTrue(draft.arranged)
        draft = draft.toggled(all.getValue(4), all)
        assertEquals("the new one is last, not sorted in", listOf(2L, 1L, 3L, 4L), draft.ids)
        draft = draft.sortedByDate(all)
        assertEquals(listOf(1L, 2L, 3L, 4L), draft.ids)
        assertFalse("sorting hands the order back to the default", draft.arranged)
    }

    @Test
    fun `tapping a chosen photo again unchooses it`() {
        val all = byId(photo(1), photo(2))
        val draft = PanoramaDraft().toggled(all.getValue(1), all).toggled(all.getValue(2), all)
        assertEquals(listOf(2L), draft.toggled(all.getValue(1), all).ids)
    }

    @Test
    fun `no more than twelve can be chosen`() {
        val photos = (1L..14L).map { photo(it) }
        val all = photos.associateBy { it.id.value }
        var draft = PanoramaDraft()
        for (p in photos) draft = draft.toggled(p, all)
        assertEquals(12, draft.ids.size)
        assertTrue(draft.isFull)
        assertEquals((1L..12L).toList(), draft.ids)
        // Unchoosing still works when full.
        assertEquals(11, draft.toggled(photos[3], all).ids.size)
    }

    @Test
    fun `two to twelve photos can be joined and nothing else can`() {
        assertFalse(PanoramaDraft(ids = emptyList()).canJoin)
        assertFalse(PanoramaDraft(ids = listOf(1)).canJoin)
        assertTrue(PanoramaDraft(ids = listOf(1, 2)).canJoin)
        assertTrue(PanoramaDraft(ids = (1L..12L).toList()).canJoin)
        assertFalse(PanoramaDraft(ids = (1L..13L).toList()).canJoin)
    }

    // ---- the move buttons -----------------------------------------------------------------------

    @Test
    fun `move left and right swap neighbours and stop at the ends`() {
        val draft = PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(10, 20, 30))
        assertEquals(listOf(20L, 10L, 30L), draft.moved(1, -1).ids)
        assertEquals(listOf(10L, 30L, 20L), draft.moved(1, +1).ids)
        assertEquals("the first cannot go further left", draft.ids, draft.moved(0, -1).ids)
        assertEquals("the last cannot go further right", draft.ids, draft.moved(2, +1).ids)
        assertEquals("an index that is not there changes nothing", draft.ids, draft.moved(7, +1).ids)
        assertFalse("a refused move is not an arrangement", draft.moved(0, -1).arranged)
        assertTrue(draft.canMove(1, -1))
        assertFalse(draft.canMove(0, -1))
        assertFalse(draft.canMove(2, +1))
    }

    @Test
    fun `removing a photo keeps the order of the rest and the last removal goes back to choosing`() {
        var draft = PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(1, 2, 3), arranged = true)
        draft = draft.removed(2)
        assertEquals(listOf(1L, 3L), draft.ids)
        assertEquals(PanoramaStep.ARRANGE, draft.step)
        draft = draft.removed(1).removed(3)
        assertTrue(draft.ids.isEmpty())
        assertEquals("nothing left to arrange", PanoramaStep.PICK, draft.step)
        assertEquals("an id that is not chosen changes nothing", draft, draft.removed(99))
    }

    // ---- after the library changes --------------------------------------------------------------

    @Test
    fun `a photo that is no longer in the library drops out, and an empty library drops nothing`() {
        val draft = PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(1, 2, 3))
        assertEquals(listOf(1L, 3L), draft.reconciled(setOf(1L, 3L, 9L)).ids)
        // A list that is still loading is not "everything was locked".
        assertEquals(draft, draft.reconciled(emptySet()))
        assertEquals(PanoramaStep.PICK, draft.reconciled(setOf(9L)).step)
        assertTrue(draft.reconciled(setOf(9L)).ids.isEmpty())
    }

    // ---- what is saved --------------------------------------------------------------------------

    @Test
    fun `the draft survives being written and read back`() {
        val draft = PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(30, 10, 20), arranged = true)
        assertEquals(draft, PanoramaDraft.decode(draft.encode()))
        assertEquals(PanoramaDraft(), PanoramaDraft.decode(PanoramaDraft().encode()))
        val negative = PanoramaDraft(ids = listOf(-5, 7))
        assertEquals(negative, PanoramaDraft.decode(negative.encode()))
    }

    @Test
    fun `saved text that is damaged or from a future build gives an empty draft, never a crash`() {
        for (bad in listOf("", "garbage", "9;ARRANGE;1;1,2", "1;SIDEWAYS;0;1,2", "1;PICK;0;a,b", "1;PICK", ";;;")) {
            assertEquals("'$bad'", PanoramaDraft(), PanoramaDraft.decode(bad))
        }
    }

    @Test
    fun `a saved draft is held to the same limits as a live one`() {
        val tooMany = PanoramaDraft(ids = (1L..20L).toList()).encode()
        assertEquals(12, PanoramaDraft.decode(tooMany).ids.size)
        assertEquals("a repeated id is one photo", listOf(1L, 2L), PanoramaDraft.decode("1;PICK;0;1,2,1,2").ids)
    }

    // ---- the words for a photo the job could not join -------------------------------------------

    @Test
    fun `a photo that does not match is named in plain words and can be removed`() {
        val problem = PanoramaProblem.of(PanoCopy.cannotJoin(3))
        assertEquals(3, problem.photo)
        assertEquals("Photo 3 doesn't overlap enough.", problem.text)
        assertEquals(3, PanoramaProblem.of(PanoCopy.cannotRead(3)).photo)
        assertEquals(PanoCopy.cannotRead(3), PanoramaProblem.of(PanoCopy.cannotRead(3)).text)
    }

    @Test
    fun `every other sentence passes through with no photo to remove`() {
        for (message in listOf(PanoCopy.PROTECTED, PanoCopy.NO_PAIR, PanoCopy.MIXED, PanoCopy.OUT_OF_MEMORY, PanoCopy.FAILED)) {
            val problem = PanoramaProblem.of(message)
            assertNull(problem.photo)
            assertEquals(message, problem.text)
        }
    }

    @Test
    fun `the job names photos the same way the screen reads them, for all twelve`() {
        for (n in 1..12) {
            assertEquals(n, PanoramaProblem.of(PanoCopy.cannotJoin(n)).photo)
            assertEquals(n, PanoramaProblem.of(PanoCopy.cannotRead(n)).photo)
        }
        assertNull("photo 13 cannot exist", PanoramaProblem.of(PanoCopy.cannotJoin(13)).photo)
    }

    // ---- the size shown before saving ------------------------------------------------------------

    @Test
    fun `a sweep that fits the budget is saved at its own size`() {
        // 1200 x 300 canvas at scale 1, nothing cropped away: well under 24 MP.
        val size = PanoramaEstimate.exportSize(1200, 300, 1100, 280, previewScale = 1.0)!!
        assertEquals(PanoSize(1100, 280), size)
    }

    @Test
    fun `a preview at a fraction of full size is scaled back up to the budget`() {
        // The full spread is 8000 x 6000 = 48 MP; the preview was drawn at 1/20 (400 x 300), so saving
        // at 24 MP is a scale of 0.707, i.e. 14.14x the preview in each direction.
        val size = PanoramaEstimate.exportSize(400, 300, 380, 280, previewScale = 0.05)!!
        assertEquals(5_374.0, size.width.toDouble(), 30.0)
        assertEquals(3_959.0, size.height.toDouble(), 30.0)
        assertTrue(size.width.toLong() * size.height <= PanoGeometry.DEFAULT_MAX_PIXELS)
    }

    @Test
    fun `a very wide sweep is held to the editor's longest edge, not only to the pixel budget`() {
        // 24000 x 4000 would fit 24 MP at half size (12000 wide), but the editor's export edge is lower.
        val size = PanoramaEstimate.exportSize(1200, 200, 1100, 180, previewScale = 0.05)!!
        assertEquals(MAX_EXPORT_EDGE.toDouble() * 1100 / 1200, size.width.toDouble(), 20.0)
        assertTrue(size.width <= MAX_EXPORT_EDGE)
    }

    @Test
    fun `the size never goes past the editor's ceilings or the budget given`() {
        val small = PanoramaEstimate.exportSize(1200, 200, 1100, 180, previewScale = 0.05, budget = 1_000_000L)!!
        assertTrue(small.width.toLong() * small.height <= 1_000_000L)
        val huge = PanoramaEstimate.exportSize(1200, 200, 1100, 180, previewScale = 0.0001, budget = Long.MAX_VALUE)!!
        assertTrue(huge.width.toLong() * huge.height <= MAX_EXPORT_PIXELS)
    }

    @Test
    fun `no size is claimed when the scale is not known`() {
        assertNull(PanoramaEstimate.exportSize(1200, 300, 1100, 280, previewScale = 0.0))
        assertNull(PanoramaEstimate.exportSize(1200, 300, 1100, 280, previewScale = Double.NaN))
        assertNull(PanoramaEstimate.exportSize(0, 300, 1100, 280, previewScale = 1.0))
    }

    @Test
    fun `sizes are shown to the nearest ten pixels so an estimate does not look exact`() {
        assertEquals("About 6130 by 1500 pixels.", PanoCopy.aboutSize(6134, 1498))
        assertEquals("About 40 by 10 pixels.", PanoCopy.aboutSize(44, 6))
        assertEquals("Saves at up to 24 megapixels.", PanoCopy.upToSize(24_000_000L))
    }

    // ---- the saved picture ------------------------------------------------------------------------

    @Test
    fun `the saved picture is found by its row id, however the uri is spelled`() {
        val saved = photo(42)
        val others = listOf(photo(1), saved, photo(7))
        assertEquals(saved, PanoramaDraft.savedAsset(others, "content://media/external/images/media/42"))
        assertEquals(saved, PanoramaDraft.savedAsset(others, "content://media/external_primary/images/media/42"))
        assertNull("not in the list yet", PanoramaDraft.savedAsset(listOf(photo(1)), "content://media/external/images/media/42"))
        assertNull(PanoramaDraft.savedAsset(others, "not a uri"))
        assertNull(PanoramaDraft.savedAsset(others, ""))
    }

    // ---- Back ---------------------------------------------------------------------------------------

    @Test
    fun `Back steps from arranging to choosing and then leaves it to the host`() {
        assertEquals(PanoramaStep.PICK, PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(1, 2)).backed().step)
        assertTrue("the chosen photos are kept", PanoramaDraft(step = PanoramaStep.ARRANGE, ids = listOf(1, 2)).backed().ids == listOf(1L, 2L))
        assertTrue(PanoramaDraft(step = PanoramaStep.ARRANGE).hasInnerStep)
        assertFalse("on the first step Back is the host's", PanoramaDraft(step = PanoramaStep.PICK).hasInnerStep)
    }

    // ---- the words --------------------------------------------------------------------------------

    @Test
    fun `every sentence the screen says is short and one sentence`() {
        val all = listOf(
            PanoCopy.TITLE, PanoCopy.BLURB, PanoCopy.PICK_HINT, PanoCopy.ORDER_HINT, PanoCopy.chosen(7), PanoCopy.BACK, PanoCopy.NEXT,
            PanoCopy.SORT_BY_DATE, PanoCopy.PREVIEW, PanoCopy.PREVIEWING, PanoCopy.SAVE, PanoCopy.CANCEL, PanoCopy.CANCELLED,
            PanoCopy.PREVIEW_DESCRIPTION, PanoCopy.OPEN, PanoCopy.NOT_LISTED_YET, PanoCopy.LEAVING_CANCELS, PanoCopy.NO_PHOTOS,
            PanoCopy.moveLeft(2), PanoCopy.moveRight(2), PanoCopy.remove(2), PanoCopy.noOverlap(2), PanoCopy.photoInStrip(2, "a.jpg"),
            PanoCopy.aboutSize(100, 50), PanoCopy.upToSize(24_000_000L), PanoCopy.SAVING,
        )
        for (text in all) {
            assertTrue("'$text' is not blank", text.isNotBlank() && text == text.trim())
            assertTrue("'$text' is at most 60 characters", text.length <= 60)
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            assertTrue("'$text' is one sentence", sentences.size <= 1)
        }
    }
}
