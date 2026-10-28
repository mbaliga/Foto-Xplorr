package com.fotoxplorr.app.reel

import com.fotoxplorr.app.reel.ReelScreenKit.photo
import com.fotoxplorr.app.reel.ReelScreenKit.stills
import com.fotoxplorr.app.reel.ReelScreenKit.video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure edits the editor's buttons make: adding, time, trim, moves, words, sound, and what is left alone. */
class ReelEditingTest {

    private fun ids(p: ReelProject) = p.items.map { it.id }

    // ---- adding ----

    @Test
    fun `photos become stills and videos become clips of up to four seconds from the start`() {
        val added = ReelEditing.add(ReelProject(), listOf(photo(1), video(2, 12_000L), video(3, 2_000L)))
        assertEquals(3, added.added)
        assertEquals(0, added.skipped)
        val items = added.project.items
        assertEquals(StillItem("r1", 1L), items[0])
        assertEquals(ClipItem("r2", 2L, 0L, ReelLimits.CLIP_MAX_MS, 12_000L), items[1])
        assertEquals(ClipItem("r3", 3L, 0L, 2_000L, 2_000L), items[2])
        assertEquals(ReelLimits.STILL_DEFAULT_MS, items[0].durationMs)
    }

    @Test
    fun `a video with no length, a trashed item and anything past the room are left out and counted`() {
        val trashed = photo(5).copy(isTrashed = true)
        val added = ReelEditing.add(ReelProject(), listOf(photo(1), video(2, 0L), video(3, 200L), trashed))
        assertEquals(1, added.added)
        assertEquals(3, added.skipped)
        assertEquals(listOf(1L), added.project.items.map { (it as StillItem).mediaId })

        val full = ReelProject(items = (1..ReelLimits.MAX_ITEMS).map { StillItem("r$it", it.toLong()) })
        assertEquals(0, ReelEditing.room(full))
        val none = ReelEditing.add(full, listOf(photo(999)))
        assertEquals(0, none.added)
        assertEquals(1, none.skipped)
        assertSame(full, none.project)
    }

    @Test
    fun `a photo this phone cannot decode is left out and counted, and a video is never judged by its format`() {
        val nef = photo(5).copy(mimeType = "", displayName = "IMG_5.NEF")
        val svg = photo(6).copy(mimeType = "image/svg+xml", displayName = "logo.svg")
        val gif = photo(7).copy(mimeType = "image/gif", displayName = "loop.gif")
        val added = ReelEditing.add(ReelProject(), listOf(photo(1), nef, svg, gif, video(2, 5_000L)))
        assertEquals(listOf(1L, 7L, 2L), added.project.items.map { (it as? StillItem)?.mediaId ?: (it as ClipItem).mediaId })
        assertEquals(3, added.added)
        assertEquals(2, added.skipped)
        assertTrue(ReelEditing.canUse(photo(1)))
        assertTrue(ReelEditing.canUse(video(2)))
        assertFalse(ReelEditing.canUse(nef))
        assertFalse(ReelEditing.canUse(svg))
        assertFalse(ReelEditing.canUse(photo(1).copy(isTrashed = true)))
    }

    @Test
    fun `items go in after the one named, in the order given, with ids no item has`() {
        val base = stills(10, 11, 12)
        val added = ReelEditing.add(base, listOf(photo(20), photo(21)), after = 0)
        assertEquals(listOf(10L, 20L, 21L, 11L, 12L), added.project.items.map { (it as StillItem).mediaId })
        assertEquals(added.project.items.size, ids(added.project).toSet().size)
        assertEquals(listOf(30L, 10L), ReelEditing.add(base, listOf(photo(30)), after = -1).project.items.take(2).map { (it as StillItem).mediaId })
        // An index past the end puts them at the end.
        assertEquals(20L, (ReelEditing.add(base, listOf(photo(20)), after = 99).project.items.last() as StillItem).mediaId)
    }

    @Test
    fun `a card is added with cleaned words and the project stays valid`() {
        val p = ReelEditing.addCard(stills(1, 2), "Hello\u0007\nWorld", after = 0)
        val card = p.items[1] as TitleItem
        assertEquals("Hello\nWorld", card.text)
        assertEquals(ReelLimits.TITLE_DEFAULT_MS, card.durationMs)
        assertEquals(3, p.items.size)
        assertTrue(ReelPlan.canRender(p))
    }

    // ---- time ----

    @Test
    fun `a photo's time moves by steps and stays between the shortest and the longest`() {
        var p = stills(1)
        p = ReelEditing.nudgeDuration(p, 0, ReelEditing.STEP_MS)
        assertEquals(3_500L, p.items[0].durationMs)
        repeat(100) { p = ReelEditing.nudgeDuration(p, 0, ReelEditing.STEP_MS) }
        assertEquals(ReelEditing.EDIT_MAX_MS, p.items[0].durationMs)
        repeat(100) { p = ReelEditing.nudgeDuration(p, 0, -ReelEditing.STEP_MS) }
        assertEquals(ReelLimits.MIN_ITEM_MS, p.items[0].durationMs)
        assertTrue(ReelPlan.validate(p).none { it.kind == ReelIssueKind.ITEM_TOO_SHORT })
    }

    @Test
    fun `a card's time moves too and a clip's does not`() {
        val p = ReelEditing.addCard(ReelProject(), "T")
        assertEquals(ReelLimits.TITLE_DEFAULT_MS + 500L, ReelEditing.nudgeDuration(p, 0, 500L).items[0].durationMs)
        val clip = ReelProject(items = listOf(ClipItem("r1", 1, 0, 3_000, 10_000)))
        assertSame(clip, ReelEditing.nudgeDuration(clip, 0, 500L))
        assertSame(clip, ReelEditing.nudgeDuration(clip, 7, 500L))
    }

    @Test
    fun `trimming a clip keeps it inside its video and at least half a second long`() {
        val clip = ReelProject(items = listOf(ClipItem("r1", 1, 2_000, 5_000, 6_000)))
        fun trimmed(p: ReelProject) = p.items[0] as ClipItem

        assertEquals(1_500L, trimmed(ReelEditing.trim(clip, 0, -500L, 0L)).inMs)
        assertEquals(5_500L, trimmed(ReelEditing.trim(clip, 0, 0L, 500L)).outMs)
        // The end stops at the video's own length.
        assertEquals(6_000L, trimmed(ReelEditing.trim(clip, 0, 0L, 9_000L)).outMs)
        // The start stops at 0 and at half a second before the end.
        assertEquals(0L, trimmed(ReelEditing.trim(clip, 0, -9_000L, 0L)).inMs)
        assertEquals(4_500L, trimmed(ReelEditing.trim(clip, 0, 9_000L, 0L)).inMs)
        // The end cannot come closer than half a second to the start.
        val shortest = trimmed(ReelEditing.trim(clip, 0, 0L, -9_000L))
        assertEquals(2_500L, shortest.outMs)
        assertEquals(2_000L, shortest.inMs)
        assertTrue(ReelPlan.canRender(ReelEditing.trim(clip, 0, 0L, 9_000L)))
    }

    @Test
    fun `a clip of unknown length has no ceiling but the longest an item may be`() {
        val clip = ReelProject(items = listOf(ClipItem("r1", 1, 0, 3_000, 0)))
        val longer = ReelEditing.trim(clip, 0, 0L, 600_000_000L).items[0] as ClipItem
        assertEquals(ReelLimits.ITEM_MAX_MS, longer.outMs - longer.inMs)
    }

    @Test
    fun `trim only works on a clip`() {
        val p = stills(1)
        assertSame(p, ReelEditing.trim(p, 0, 500L, 0L))
        assertSame(p, ReelEditing.trim(p, 3, 500L, 0L))
    }

    // ---- moves ----

    @Test
    fun `each move button reads back as itself and gives a window the picture can show`() {
        for (imageAspect in listOf(0.5f, 0.75f, 1f, 4f / 3f, 16f / 9f, 3f)) {
            for (frameAspect in listOf(16f / 9f, 9f / 16f, 1f)) {
                for (choice in MotionChoice.entries) {
                    val motion = ReelMoves.motionFor(choice, imageAspect, frameAspect)
                    assertEquals("$choice on $imageAspect in $frameAspect", choice, ReelMoves.choiceOf(motion))
                    val project = ReelProject(items = listOf(StillItem("r1", 1, motion = motion)))
                    assertTrue("$choice is a sound plan", ReelPlan.canRender(project))
                    // Every window along the way is inside the picture and has the frame's shape.
                    for (step in 0..10) {
                        val w = ReelGeometry.windowAt(motion, step / 10f, (imageAspect * 1000).toInt(), 1000, frameAspect)
                        assertTrue(w.left >= -1e-4f && w.top >= -1e-4f && w.right <= 1f + 1e-4f && w.bottom <= 1f + 1e-4f)
                        assertEquals(frameAspect, w.width * imageAspect / w.height, frameAspect * 1e-2f)
                    }
                }
            }
        }
    }

    @Test
    fun `a pan really travels the way its name says`() {
        fun centre(choice: MotionChoice): Pair<Float, Float> {
            val m = ReelMoves.motionFor(choice, 4f / 3f, 16f / 9f) as ReelMotion.Pan
            return (m.end.centreX - m.start.centreX) to (m.end.centreY - m.start.centreY)
        }
        assertTrue(centre(MotionChoice.PAN_RIGHT).first > 0.05f)
        assertTrue(centre(MotionChoice.PAN_LEFT).first < -0.05f)
        assertTrue(centre(MotionChoice.PAN_DOWN).second > 0.05f)
        assertTrue(centre(MotionChoice.PAN_UP).second < -0.05f)
    }

    @Test
    fun `a move is set on a photo only`() {
        val p = ReelEditing.addCard(stills(1), "T")
        val moved = ReelEditing.setMotion(p, 0, ReelMotion.ZoomIn())
        assertEquals(ReelMotion.ZoomIn(), (moved.items[0] as StillItem).motion)
        assertSame(p, ReelEditing.setMotion(p, 1, ReelMotion.ZoomIn()))
        assertSame(p, ReelEditing.setMotion(p, 9, ReelMotion.ZoomIn()))
    }

    // ---- hand-over, words, colour, name ----

    @Test
    fun `hand-overs are set on one item or on all of them`() {
        val p = stills(1, 2, 3)
        val one = ReelEditing.setTransition(p, 1, ReelTransition.SLIDE)
        assertEquals(listOf(ReelTransition.CUT, ReelTransition.SLIDE, ReelTransition.CUT), one.items.map { it.transition })
        assertEquals(ReelTransition.CROSS_FADE, p.withTransitions(ReelTransition.CROSS_FADE).items.first().transition)
        assertSame(p, ReelEditing.setTransition(p, 5, ReelTransition.SLIDE))
    }

    @Test
    fun `card words are cut to the limit and lose control characters but keep line breaks`() {
        val p = ReelEditing.addCard(ReelProject(), "x")
        val long = "a".repeat(ReelLimits.TEXT_MAX_CHARS + 50)
        assertEquals(ReelLimits.TEXT_MAX_CHARS, (ReelEditing.setCardText(p, 0, long).items[0] as TitleItem).text.length)
        assertEquals("one\ntwo", (ReelEditing.setCardText(p, 0, "one\u0000\ntwo").items[0] as TitleItem).text)
        assertEquals(0xFF000000.toInt(), (ReelEditing.setCardColour(p, 0, 0xFF000000.toInt()).items[0] as TitleItem).backgroundArgb)
    }

    @Test
    fun `an empty card is allowed to be typed but the plan says it is empty`() {
        val p = ReelEditing.setCardText(ReelEditing.addCard(ReelProject(), "x"), 0, "")
        assertTrue(ReelPlan.validate(p).any { it.kind == ReelIssueKind.TITLE_EMPTY })
        assertTrue(ReelPlan.canRender(p))
    }

    @Test
    fun `the name is typed untrimmed and saved trimmed`() {
        assertEquals("My ", ReelEditing.setName(ReelProject(), "My ").name)
        assertEquals("My trip", ReelEditing.cleanName("  My\u0007 trip  "))
        assertEquals(ReelEditing.NAME_MAX, ReelEditing.cleanName("x".repeat(200)).length)
        assertEquals(ReelEditing.NAME_MAX, ReelEditing.setName(ReelProject(), "x".repeat(200)).name.length)
    }

    // ---- sound ----

    @Test
    fun `a sound file is set, replaced keeping its fade, nudged and taken away`() {
        var p = ReelEditing.setSoundtrack(stills(1), "content://doc/1")
        assertEquals(ReelSoundtrack("content://doc/1", 0L, ReelLimits.FADE_OUT_DEFAULT_MS), p.soundtrack)
        p = ReelEditing.nudgeFade(p, ReelEditing.FADE_STEP_MS)
        p = ReelEditing.nudgeMusicStart(p, ReelEditing.MUSIC_STEP_MS)
        assertEquals(3_000L, p.soundtrack?.fadeOutMs)
        assertEquals(5_000L, p.soundtrack?.trimStartMs)
        // A new file starts at the beginning and keeps the fade.
        p = ReelEditing.setSoundtrack(p, "content://doc/2")
        assertEquals(ReelSoundtrack("content://doc/2", 0L, 3_000L), p.soundtrack)
        assertTrue(ReelPlan.canRender(p))
        assertNull(ReelEditing.removeSoundtrack(p).soundtrack)
    }

    @Test
    fun `the fade and the music's start stay in their ranges, and nothing happens with no sound file`() {
        var p = ReelEditing.setSoundtrack(stills(1), "content://doc/1")
        repeat(100) { p = ReelEditing.nudgeFade(p, 1_000L) }
        assertEquals(ReelLimits.FADE_OUT_MAX_MS, p.soundtrack?.fadeOutMs)
        repeat(100) { p = ReelEditing.nudgeFade(p, -1_000L) }
        assertEquals(0L, p.soundtrack?.fadeOutMs)
        repeat(100) { p = ReelEditing.nudgeMusicStart(p, -5_000L) }
        assertEquals(0L, p.soundtrack?.trimStartMs)
        assertTrue(ReelPlan.canRender(p))
        val plain = stills(1)
        assertSame(plain, ReelEditing.nudgeFade(plain, 1_000L))
        assertSame(plain, ReelEditing.nudgeMusicStart(plain, 5_000L))
        assertSame(plain, ReelEditing.setSoundtrack(plain, " "))
    }

    // ---- what is gone, and picking after a removal ----

    @Test
    fun `items whose photo is not in the library are found by position`() {
        val p = ReelEditing.addCard(stills(1, 2, 3), "T")
        assertEquals(listOf(1, 2), ReelEditing.missing(p, setOf(1L)))
        assertEquals(emptyList<Int>(), ReelEditing.missing(p, setOf(1L, 2L, 3L)))
    }

    @Test
    fun `after a removal the item now in its place is picked, else the one before, else none`() {
        val p = stills(1, 2, 3)
        assertEquals("r3", ReelEditing.pickAfterRemoval(p.removed(1), 1))
        assertEquals("r2", ReelEditing.pickAfterRemoval(p.removed(2), 2))
        assertNull(ReelEditing.pickAfterRemoval(ReelProject(), 0))
    }

    @Test
    fun `duplicating makes a copy with its own id and a full reel makes none`() {
        val p = stills(1, 2)
        val d = ReelEditing.duplicate(p, 0)
        assertEquals(3, d.items.size)
        assertEquals(3, ids(d).toSet().size)
        assertEquals((d.items[0] as StillItem).mediaId, (d.items[1] as StillItem).mediaId)
        val full = ReelProject(items = (1..ReelLimits.MAX_ITEMS).map { StillItem("r$it", it.toLong()) })
        assertSame(full, ReelEditing.duplicate(full, 0))
    }

    @Test
    fun `an out-of-range request changes nothing`() {
        val p = stills(1, 2)
        assertSame(p, ReelEditing.setTransition(p, -1, ReelTransition.SLIDE))
        assertSame(p, ReelEditing.setCardText(p, 0, "x"))
        assertSame(p, ReelEditing.setCardColour(p, 0, 1))
        assertFalse(ReelEditing.indexOf(p, "nope") >= 0)
        assertEquals(-1, ReelEditing.indexOf(p, null))
    }

    @Test
    fun `the card colours are distinct and named`() {
        assertEquals(ReelEditing.CARD_COLOURS.size, ReelEditing.CARD_COLOURS.map { it.argb }.toSet().size)
        assertEquals(ReelEditing.CARD_COLOURS.size, ReelEditing.CARD_COLOURS.map { it.name }.toSet().size)
        assertTrue(ReelEditing.CARD_COLOURS.all { it.name.isNotBlank() })
    }
}
