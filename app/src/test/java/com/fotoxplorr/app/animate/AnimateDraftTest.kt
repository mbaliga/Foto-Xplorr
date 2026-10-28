package com.fotoxplorr.app.animate

import com.fotoxplorr.app.editor.EditRecipe
import com.fotoxplorr.app.media.MediaAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The screen's rules without a screen: what each button does to the draft, the checks against the
 * library, the request the draft makes, and the one string it is saved as.
 */
class AnimateDraftTest {
    private val NOISE = "0123456789;,:-xA"
    private val photos = (1L..8L).map { AnimateKit.asset(it) }
    private val video = AnimateKit.video(20, durationMs = 12_000)
    private val gif = AnimateKit.asset(30, "party.gif", mime = "image/gif")
    private val library = AnimateLibrary(photos + video + gif)

    private fun ticked(vararg ids: Long): AnimateDraft = ids.fold(AnimateDraft()) { d, id -> d.togglePhoto(id) }

    private fun ids(d: AnimateDraft) = d.photos.map { it.id }

    // ---- the library ----------------------------------------------------------------------------------------

    @Test
    fun `photos are stills only - no video, no GIF, no vector, nothing in the bin`() {
        val svg = AnimateKit.asset(40, "logo.svg", mime = "image/svg+xml")
        val trashed = AnimateKit.asset(41).copy(isTrashed = true)
        val heic = AnimateKit.asset(42, "IMG.heic", mime = "image/heic")
        val lib = AnimateLibrary(photos + video + gif + svg + trashed + heic)
        assertEquals((1L..8L).toList() + 42L, lib.photos.map { it.id.value })
        assertEquals(listOf(20L), lib.videos.map { it.id.value })
    }

    @Test
    fun `moving-file candidates are GIFs, WebP files and PNGs saved in our own album, never a video or a plain PNG`() {
        val webp = AnimateKit.asset(31, "a.webp", mime = "image/webp")
        val ownPng = AnimateKit.asset(32, "trip-animation.png", mime = "image/png").copy(relativePath = "Pictures/Fotoz/Animations/")
        val plainPng = AnimateKit.asset(33, "shot.png", mime = "image/png").copy(relativePath = "Pictures/Screenshots/")
        val jpeg = AnimateKit.asset(34)
        val binned = AnimateKit.asset(35, "old.gif", mime = "image/gif").copy(isTrashed = true)
        val lib = AnimateLibrary(listOf(gif, webp, ownPng, plainPng, jpeg, binned, video))
        assertEquals(listOf(30L, 31L, 32L), lib.files.map { it.id.value })
    }

    @Test
    fun `the saved file is found by the row id at the end of its uri or by the whole uri`() {
        val saved = AnimateKit.asset(900)
        val list = photos + saved
        assertEquals(900L, AnimateLibrary.savedAsset(list, "content://media/external_primary/images/media/900")?.id?.value)
        assertEquals(900L, AnimateLibrary.savedAsset(list, "content://fake/900")?.id?.value)
        assertNull(AnimateLibrary.savedAsset(list, "content://media/external/images/media/901"))
        assertNull(AnimateLibrary.savedAsset(list, ""))
    }

    // ---- photos --------------------------------------------------------------------------------------------------

    @Test
    fun `a new photo joins the end with the time of the one before it`() {
        val d = ticked(3, 1).selectedHold(500)
        val more = d.togglePhoto(5)
        assertEquals(listOf(3L, 1L, 5L), ids(more))
        assertEquals(AnimateDraft.DEFAULT_HOLD_MS, more.photos[0].holdMs)
        assertEquals(500, more.photos[2].holdMs)
    }

    private fun AnimateDraft.selectedHold(ms: Int): AnimateDraft = select(photos.lastIndex).let { s ->
        s.copy(photos = s.photos.mapIndexed { i, p -> if (i == s.selected) p.copy(holdMs = ms) else p })
    }

    @Test
    fun `ticking a ticked photo takes it out, and the selection stays on a photo`() {
        val d = ticked(1, 2, 3).select(2)
        val less = d.togglePhoto(3)
        assertEquals(listOf(1L, 2L), ids(less))
        assertEquals(1, less.selected)
        assertEquals(listOf(2L, 3L), ids(ticked(1, 2, 3).togglePhoto(1)))
        assertEquals(0, AnimateDraft().togglePhoto(9).togglePhoto(9).selected)
    }

    @Test
    fun `no more than the most photos`() {
        val many = (1L..AnimateDraft.MAX_PHOTOS + 5).fold(AnimateDraft()) { d, id -> d.togglePhoto(id) }
        assertEquals(AnimateDraft.MAX_PHOTOS, many.photos.size)
        // An unticked one can still be taken out once full.
        assertEquals(AnimateDraft.MAX_PHOTOS - 1, many.togglePhoto(1).photos.size)
    }

    @Test
    fun `move left and right swap with the neighbour, the selection goes with the photo, and the ends stop`() {
        var d = ticked(1, 2, 3).select(0)
        assertFalse(d.canMoveLeft)
        assertTrue(d.canMoveRight)
        assertSame(d, d.movedBy(-1))
        d = d.movedBy(1)
        assertEquals(listOf(2L, 1L, 3L), ids(d))
        assertEquals(1, d.selected)
        d = d.movedBy(1)
        assertEquals(listOf(2L, 3L, 1L), ids(d))
        assertEquals(2, d.selected)
        assertFalse(d.canMoveRight)
        assertSame(d, d.movedBy(1))
        assertEquals(listOf(2L, 1L, 3L), ids(d.movedBy(-1)))
    }

    @Test
    fun `remove takes out the selected photo but never leaves fewer than two`() {
        var d = ticked(1, 2, 3).select(1)
        assertTrue(d.canRemove)
        d = d.removed()
        assertEquals(listOf(1L, 3L), ids(d))
        assertEquals(1, d.selected)
        assertFalse(d.canRemove)
        assertSame(d, d.removed())
        // Removing the last of a longer strip moves the selection to the new last.
        val e = ticked(1, 2, 3).select(2).removed()
        assertEquals(1, e.selected)
        assertEquals(listOf(1L, 2L), ids(e))
    }

    @Test
    fun `the time on screen steps within its range and says when a step would do nothing`() {
        var d = ticked(1, 2).select(0)
        assertEquals(1000, d.photos[0].holdMs)
        d = d.holdBy(AnimateDraft.HOLD_COARSE_MS)
        assertEquals(1500, d.photos[0].holdMs)
        assertEquals(1000, d.photos[1].holdMs)
        d = d.holdBy(-100_000)
        assertEquals(AnimateDraft.MIN_HOLD_MS, d.photos[0].holdMs)
        assertFalse(d.canHoldBy(-AnimateDraft.HOLD_FINE_MS))
        assertSame(d, d.holdBy(-AnimateDraft.HOLD_FINE_MS))
        d = d.holdBy(1_000_000)
        assertEquals(AnimateDraft.MAX_HOLD_MS, d.photos[0].holdMs)
        assertFalse(d.canHoldBy(AnimateDraft.HOLD_FINE_MS))
        assertTrue(d.canHoldBy(-AnimateDraft.HOLD_FINE_MS))
        assertFalse(AnimateDraft().canHoldBy(100))
    }

    @Test
    fun `same for all copies the selected time to every photo, and is off when they already agree`() {
        val d = ticked(1, 2, 3).select(1).holdBy(500)
        assertTrue(d.canSameForAll)
        val all = d.sameForAll()
        assertTrue(all.photos.all { it.holdMs == 1500 })
        assertFalse(all.canSameForAll)
        assertSame(all, all.sameForAll())
    }

    @Test
    fun `speed divides the time on screen, in steps of 25 from 25 to 400 percent`() {
        val d = ticked(1, 2).select(0).holdBy(1000)
        assertEquals(2000, d.effectiveHoldMs(d.photos[0]))
        val fast = d.speedBy(4)
        assertEquals(200, fast.speedPercent)
        assertEquals(1000, fast.effectiveHoldMs(fast.photos[0]))
        val slowest = d.speedBy(-100)
        assertEquals(AnimateDraft.SPEED_MIN, slowest.speedPercent)
        assertEquals(8000, slowest.effectiveHoldMs(slowest.photos[0]))
        assertEquals(AnimateDraft.SPEED_MAX, d.speedBy(100).speedPercent)
        assertSame(slowest, slowest.speedBy(-1))
        // The fastest speed never takes a photo below one millisecond, and the slowest never above the seam's limit.
        val tiny = ticked(1, 2).holdBy(-1000).speedBy(100)
        assertTrue(tiny.effectiveHoldMs(tiny.photos[0]) >= 1)
        val long = ticked(1, 2).holdBy(AnimateDraft.MAX_HOLD_MS).speedBy(-100)
        assertTrue(long.effectiveHoldMs(long.photos[0]) <= AnimStill.MAX_HOLD_MS)
    }

    // ---- a part of a video ----------------------------------------------------------------------------------------

    @Test
    fun `a picked video starts at its beginning and runs five seconds, or all of a shorter one`() {
        val d = AnimateDraft().pickClip(video)
        assertEquals(20L, d.clipId)
        assertEquals(0L, d.clipStartMs)
        assertEquals(5_000L, d.clipEndMs)
        val short = AnimateKit.video(21, durationMs = 2_000)
        assertEquals(2_000L, AnimateDraft().pickClip(short).clipEndMs)
        val unknown = AnimateKit.video(22, durationMs = 0)
        assertEquals(5_000L, AnimateDraft().pickClip(unknown).clipEndMs)
        // Picking the same video again keeps the part chosen.
        val moved = d.endBy(1000, 12_000)
        assertSame(moved, moved.pickClip(video))
    }

    @Test
    fun `the start and end move in steps, the end stays past the start and inside the video, and a part is at most a minute`() {
        var d = AnimateDraft().pickClip(video)
        d = d.endBy(AnimateDraft.CLIP_COARSE_MS, 12_000)
        assertEquals(6_000L, d.clipEndMs)
        d = d.startBy(AnimateDraft.CLIP_COARSE_MS)
        assertEquals(1_000L, d.clipStartMs)
        assertEquals(5_000L, d.clipLengthMs)
        // The start cannot pass the end, and keeps the shortest part.
        d = d.startBy(100_000)
        assertEquals(d.clipEndMs - AnimateDraft.MIN_CLIP_MS, d.clipStartMs)
        d = d.startBy(-100_000)
        assertEquals(0L, d.clipStartMs)
        // The end cannot go before the shortest part, or past the video.
        d = d.endBy(-100_000, 12_000)
        assertEquals(AnimateDraft.MIN_CLIP_MS, d.clipEndMs)
        d = d.endBy(100_000, 12_000)
        assertEquals(12_000L, d.clipEndMs)
        assertSame(d, d.endBy(1, 12_000))
        // A long video: a part is at most MAX_CLIP_MS.
        val long = AnimateDraft().pickClip(AnimateKit.video(23, durationMs = 600_000)).endBy(1_000_000, 600_000)
        assertEquals(AnimateDraft.MAX_CLIP_MS, long.clipEndMs)
        // An end limit below the shortest part still leaves a part of that length.
        assertEquals(AnimateDraft.MIN_CLIP_MS, AnimateDraft().pickClip(video).endBy(-100_000, 50).clipEndMs)
    }

    @Test
    fun `the end never comes before the start however the buttons are pressed`() {
        val rnd = Random(7)
        var d = AnimateDraft().pickClip(video)
        repeat(2_000) {
            val delta = (rnd.nextInt(5) - 2) * (if (rnd.nextBoolean()) 100L else 1000L)
            d = if (rnd.nextBoolean()) d.startBy(delta) else d.endBy(delta, 12_000)
            assertTrue("start ${d.clipStartMs} end ${d.clipEndMs}", d.clipStartMs >= 0 && d.clipEndMs >= d.clipStartMs + AnimateDraft.MIN_CLIP_MS)
            assertTrue(d.clipEndMs <= 12_000)
        }
    }

    @Test
    fun `frames a second step within 1 to 30`() {
        var d = AnimateDraft()
        assertEquals(12, d.clipFps)
        d = d.fpsBy(5)
        assertEquals(17, d.clipFps)
        assertEquals(30, d.fpsBy(100).clipFps)
        assertEquals(1, d.fpsBy(-100).clipFps)
        val top = d.fpsBy(100)
        assertSame(top, top.fpsBy(1))
    }

    // ---- steps ---------------------------------------------------------------------------------------------------------

    @Test
    fun `each source needs its own choice before Next`() {
        assertFalse(AnimateDraft().canContinue)
        assertFalse(ticked(1).canContinue)
        assertTrue(ticked(1, 2).canContinue)
        val clip = AnimateDraft().withSource(AnimateSource.CLIP)
        assertFalse(clip.canContinue)
        assertTrue(clip.pickClip(video).canContinue)
        val file = AnimateDraft().withSource(AnimateSource.FILE)
        assertFalse(file.canContinue)
        assertTrue(file.pickFile(gif).canContinue)
    }

    @Test
    fun `Next goes on only when the source is complete, and Back goes back to the start`() {
        val none = AnimateDraft()
        assertSame(none, none.next())
        var d = ticked(1, 2)
        assertEquals(AnimatePage.SOURCE, d.page)
        d = d.next()
        assertEquals(AnimatePage.ARRANGE, d.page)
        assertTrue(d.hasInnerStep)
        d = d.next()
        assertEquals(AnimatePage.OUTPUT, d.page)
        assertSame(d, d.next())
        d = d.backed()
        assertEquals(AnimatePage.ARRANGE, d.page)
        d = d.backed()
        assertEquals(AnimatePage.SOURCE, d.page)
        assertFalse(d.hasInnerStep)
        assertSame(d, d.backed())
    }

    @Test
    fun `changing the source keeps what was picked for the others`() {
        val d = ticked(1, 2).withSource(AnimateSource.CLIP).pickClip(video).withSource(AnimateSource.PHOTOS)
        assertEquals(listOf(1L, 2L), ids(d))
        assertEquals(20L, d.clipId)
        assertTrue(d.hasChoices)
        assertFalse(AnimateDraft().hasChoices)
    }

    // ---- against the library ---------------------------------------------------------------------------------------------

    @Test
    fun `a photo that has left the library drops out, the selection stays valid, and the step goes back if the strip is too short`() {
        val d = ticked(1, 2, 3).select(2).next()
        val without3 = AnimateLibrary(photos.filter { it.id.value != 3L } + video + gif)
        val r = d.reconciled(without3)
        assertEquals(listOf(1L, 2L), ids(r))
        assertEquals(1, r.selected)
        assertEquals(AnimatePage.ARRANGE, r.page)
        val without2and3 = AnimateLibrary(photos.filter { it.id.value == 1L })
        val r2 = d.reconciled(without2and3)
        assertEquals(listOf(1L), ids(r2))
        assertEquals(AnimatePage.SOURCE, r2.page)
    }

    @Test
    fun `an empty library is a list still loading, not everything gone`() {
        val d = ticked(1, 2).next()
        assertSame(d, d.reconciled(AnimateLibrary(emptyList())))
    }

    @Test
    fun `a video or file that has left is forgotten, and a part is held within a video that turns out shorter`() {
        val withClip = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video).endBy(4000, 12_000).next()
        assertEquals(AnimatePage.ARRANGE, withClip.page)
        val shorter = AnimateKit.video(20, durationMs = 3_000)
        val held = withClip.reconciled(AnimateLibrary(photos + shorter))
        assertEquals(3_000L, held.clipEndMs)
        assertTrue(held.clipStartMs <= 3_000L - AnimateDraft.MIN_CLIP_MS)
        val gone = withClip.reconciled(AnimateLibrary(photos))
        assertNull(gone.clipId)
        assertEquals(AnimatePage.SOURCE, gone.page)
        val withFile = AnimateDraft().withSource(AnimateSource.FILE).pickFile(gif).next()
        val noFile = withFile.reconciled(AnimateLibrary(photos))
        assertNull(noFile.fileId)
        assertEquals(AnimatePage.SOURCE, noFile.page)
    }

    // ---- the request -------------------------------------------------------------------------------------------------------

    @Test
    fun `photos make a slideshow with each time divided by the speed, and a repeat for ever by default`() {
        val d = ticked(3, 1, 2).select(0).holdBy(1000).speedBy(4)
        val request = d.toRequest(library)!!
        val input = request.input as AnimateInput.Stills
        assertEquals(listOf(3L, 1L, 2L), input.items.map { it.asset.id.value })
        assertEquals(listOf(1000, 500, 500), input.items.map { it.holdMs })
        assertNull(input.transition)
        assertEquals(0, request.plays)
        assertEquals(AnimOutput.GIF, request.output)
        assertEquals(AnimateDefaults.limits(AnimOutput.GIF).maxLongEdge, request.limits.maxLongEdge)
        assertFalse(request.keepTransparency)
    }

    @Test
    fun `repeat off plays once, and a fade gives the seam a transition that loops only with the repeat`() {
        val off = ticked(1, 2).withLoop(false).withFade(true).toRequest(library)!!
        assertEquals(1, off.plays)
        val t = (off.input as AnimateInput.Stills).transition!!
        assertEquals(AnimateDraft.FADE_FRAMES, t.frames)
        assertEquals(AnimateDraft.FADE_FRAME_MS, t.frameMs)
        assertFalse(t.loop)
        val on = ticked(1, 2).withFade(true).toRequest(library)!!
        assertTrue((on.input as AnimateInput.Stills).transition!!.loop)
    }

    @Test
    fun `saved edits are asked for only when they are switched on`() {
        val recipe = EditRecipe(quarterTurns = 1)
        val asked = ArrayList<Long>()
        val off = ticked(1, 2).toRequest(library) { asked += it.id.value; recipe }!!
        assertTrue(asked.isEmpty())
        assertTrue((off.input as AnimateInput.Stills).items.all { it.recipe == null })
        val on = ticked(1, 2).withUseEdits(true).toRequest(library) { if (it.id.value == 1L) recipe else null }!!
        val items = (on.input as AnimateInput.Stills).items
        assertSame(recipe, items[0].recipe)
        assertNull(items[1].recipe)
    }

    @Test
    fun `a video part makes a clip request at the chosen rate`() {
        val d = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video).startBy(1000).fpsBy(3).withOutput(AnimOutput.VIDEO).withLoop(false)
        val request = d.toRequest(library)!!
        val clip = request.input as AnimateInput.Clip
        assertEquals(20L, clip.asset.id.value)
        assertEquals(1000L, clip.startMs)
        assertEquals(5000L, clip.endMs)
        assertEquals(15.0, clip.fps, 0.0)
        assertEquals(AnimOutput.VIDEO, request.output)
        assertEquals(AnimateDefaults.limits(AnimOutput.VIDEO).maxLongEdge, request.limits.maxLongEdge)
        assertEquals(1, request.plays)
    }

    @Test
    fun `a moving file is remade, and its see-through parts are kept only for a GIF or APNG`() {
        val d = AnimateDraft().withSource(AnimateSource.FILE).pickFile(gif)
        val request = d.toRequest(library)!!
        assertTrue(request.input is AnimateInput.Existing)
        assertTrue(request.keepTransparency)
        assertFalse(d.withOutput(AnimOutput.VIDEO).toRequest(library)!!.keepTransparency)
        assertFalse(d.withKeepTransparency(false).toRequest(library)!!.keepTransparency)
        assertTrue(d.withOutput(AnimOutput.APNG).toRequest(library)!!.keepTransparency)
        // Only a moving file has see-through parts to keep.
        assertFalse(ticked(1, 2).toRequest(library)!!.keepTransparency)
    }

    @Test
    fun `an incomplete or out-of-date draft makes no request`() {
        assertNull(AnimateDraft().toRequest(library))
        assertNull(ticked(1).toRequest(library))
        assertNull(ticked(1, 99).toRequest(library))
        assertNull(AnimateDraft().withSource(AnimateSource.CLIP).toRequest(library))
        assertNull(AnimateDraft().withSource(AnimateSource.CLIP).pickClip(AnimateKit.video(77)).toRequest(library))
        assertNull(AnimateDraft().withSource(AnimateSource.FILE).toRequest(library))
    }

    @Test
    fun `the preview key changes with what changes the picture or the timing, and with nothing else`() {
        val base = ticked(1, 2, 3)
        val key = base.previewKey()
        assertEquals(key, base.next().previewKey())
        assertEquals(key, base.select(2).previewKey())
        assertEquals(key, base.withOutput(AnimOutput.APNG).previewKey())
        assertNotEquals(key, base.togglePhoto(4).previewKey())
        assertNotEquals(key, base.movedBy(1).previewKey())
        assertNotEquals(key, base.select(1).holdBy(100).previewKey())
        assertNotEquals(key, base.speedBy(1).previewKey())
        assertNotEquals(key, base.withFade(true).previewKey())
        assertNotEquals(base.withFade(true).previewKey(), base.withFade(true).withLoop(false).previewKey())
        assertNotEquals(key, base.withUseEdits(true).previewKey())
        // A loop change alone does not change a preview that plays for ever.
        assertEquals(key, base.withLoop(false).previewKey())
        val clip = AnimateDraft().withSource(AnimateSource.CLIP).pickClip(video)
        assertNotEquals(clip.previewKey(), clip.startBy(100).previewKey())
        assertNotEquals(clip.previewKey(), clip.endBy(100, 12_000).previewKey())
        assertNotEquals(clip.previewKey(), clip.fpsBy(1).previewKey())
        assertNotEquals(clip.previewKey(), base.previewKey())
    }

    // ---- the saved string ---------------------------------------------------------------------------------------------------

    private fun everything(): AnimateDraft =
        ticked(5, 3, 8).select(1).holdBy(500).speedBy(-2).withFade(true).withLoop(false).withUseEdits(true).withKeepTransparency(false)
            .withSource(AnimateSource.CLIP).pickClip(video).startBy(1000).endBy(2000, 12_000).fpsBy(4)
            .pickFile(gif).withOutput(AnimOutput.VIDEO).next().next()

    @Test
    fun `a draft survives being written and read back, every field`() {
        val d = everything()
        assertEquals(AnimatePage.OUTPUT, d.page)
        val back = AnimateDraft.decode(d.encode())
        assertEquals(d, back)
        assertEquals(AnimateDraft(), AnimateDraft.decode(AnimateDraft().encode()))
    }

    @Test
    fun `a string that is not a draft is refused, whatever is wrong with it`() {
        val good = everything().encode()
        assertNull(AnimateDraft.decode(""))
        assertNull(AnimateDraft.decode("2" + good.drop(1)))
        assertNull(AnimateDraft.decode(good.replace("OUTPUT", "NOWHERE")))
        assertNull(AnimateDraft.decode(good.substringBeforeLast(';')))
        assertNull(AnimateDraft.decode("$good;extra"))
        assertNull(AnimateDraft.decode(good.replace("5:", "x:")))
        assertNull(AnimateDraft.decode(good.replace("1;", "9;")))
    }

    @Test
    fun `damaged or cut strings never throw and never give a draft that breaks the rules`() {
        val rnd = Random(11)
        val good = everything().encode()
        repeat(1_500) {
            val chars = good.toCharArray()
            repeat(1 + rnd.nextInt(4)) { chars[rnd.nextInt(chars.size)] = NOISE[rnd.nextInt(NOISE.length)] }
            val text = String(chars).let { if (rnd.nextInt(3) == 0) it.take(rnd.nextInt(it.length)) else it }
            val d = AnimateDraft.decode(text) ?: return@repeat
            assertTrue(d.photos.size <= AnimateDraft.MAX_PHOTOS)
            assertTrue(d.photos.isEmpty() || d.selected in d.photos.indices)
            assertTrue(d.photos.all { it.holdMs in AnimateDraft.MIN_HOLD_MS..AnimateDraft.MAX_HOLD_MS })
            assertTrue(d.speedPercent in AnimateDraft.SPEED_MIN..AnimateDraft.SPEED_MAX && d.speedPercent % AnimateDraft.SPEED_STEP == 0)
            assertTrue(d.clipStartMs >= 0 && d.clipEndMs >= d.clipStartMs + AnimateDraft.MIN_CLIP_MS && d.clipLengthMs <= AnimateDraft.MAX_CLIP_MS)
            assertTrue(d.clipFps in AnimateDraft.MIN_UI_FPS..AnimateDraft.MAX_UI_FPS)
            assertFalse("a step past the first needs a complete source", d.hasInnerStep && !d.canContinue)
        }
    }

    @Test
    fun `numbers out of range in a string are brought back into range`() {
        val text = "1;SOURCE;PHOTOS;99;;-5;999999999999;500;;GIF;1;33;0;1;0;1:5,2:99999999"
        val d = AnimateDraft.decode(text)!!
        assertEquals(1, d.selected)
        assertEquals(listOf(AnimateDraft.MIN_HOLD_MS, AnimateDraft.MAX_HOLD_MS), d.photos.map { it.holdMs })
        assertEquals(25, d.speedPercent)
        assertEquals(0L, d.clipStartMs)
        assertEquals(AnimateDraft.MAX_CLIP_MS, d.clipEndMs)
        assertEquals(AnimateDraft.MAX_UI_FPS, d.clipFps)
    }

    @Test
    fun `a step that needs a complete source is not kept for an incomplete one`() {
        val text = "1;OUTPUT;PHOTOS;0;;0;5000;12;;GIF;1;100;0;1;0;7:1000"
        val d = AnimateDraft.decode(text)!!
        assertEquals(AnimatePage.SOURCE, d.page)
        assertNotNull(d.photos.singleOrNull())
    }
}
