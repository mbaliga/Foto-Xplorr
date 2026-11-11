package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** The pure plan under the export: frame sizes, the size ladder, layers, hand-overs and the soundtrack's volume. */
class ReelRenderPlanTest {
    private val fade = ReelTransition.CROSS_FADE
    private val slide = ReelTransition.SLIDE
    private val cut = ReelTransition.CUT

    private fun still(n: Int, ms: Long = 3_000L, tr: ReelTransition = cut, motion: ReelMotion = ReelMotion.None) =
        StillItem("r$n", n.toLong(), ms, motion, tr)

    private fun project(vararg items: ReelItem, aspect: ReelAspect = ReelAspect.WIDE, sound: ReelSoundtrack? = null) =
        ReelProject(items.toList(), aspect, sound)

    private fun build(
        p: ReelProject,
        attempt: ReelAttempt = ReelAttempt(1080, true),
        sound: SoundtrackFacts? = null,
        keep: Boolean = false,
    ) = ReelRenderPlan.build(p, attempt, ReelFrame.of(p.aspect, attempt.shortSide), ReelFit.of(p.aspect), sound, keep)

    // ---- frames and the ladder ---------------------------------------------------------------------------------

    @Test
    fun `fixed shapes at 1080, 720 and 480 have even sides`() {
        assertEquals(ReelFrame(1920, 1080), ReelFrame.of(ReelAspect.WIDE, 1080))
        assertEquals(ReelFrame(1080, 1920), ReelFrame.of(ReelAspect.TALL, 1080))
        assertEquals(ReelFrame(1080, 1080), ReelFrame.of(ReelAspect.SQUARE, 1080))
        assertEquals(ReelFrame(1280, 720), ReelFrame.of(ReelAspect.WIDE, 720))
        assertEquals(ReelFrame(854, 480), ReelFrame.of(ReelAspect.WIDE, 480))
        assertEquals(ReelFrame(480, 854), ReelFrame.of(ReelAspect.TALL, 480))
    }

    @Test
    fun `original takes the first picture's shape, held between tall and wide`() {
        assertEquals(ReelFrame(1440, 1080), ReelFrame.of(ReelAspect.ORIGINAL, 1080, 4f / 3f))
        assertEquals(ReelFrame(1080, 1440), ReelFrame.of(ReelAspect.ORIGINAL, 1080, 3f / 4f))
        assertEquals("a panorama is held at wide", ReelFrame(1920, 1080), ReelFrame.of(ReelAspect.ORIGINAL, 1080, 5f))
        assertEquals("a tall strip is held at tall", ReelFrame(1080, 1920), ReelFrame.of(ReelAspect.ORIGINAL, 1080, 0.1f))
        assertEquals("unknown is wide", ReelFrame(1920, 1080), ReelFrame.of(ReelAspect.ORIGINAL, 1080, null))
        assertEquals("nonsense is wide", ReelFrame(1920, 1080), ReelFrame.of(ReelAspect.ORIGINAL, 1080, Float.NaN))
    }

    @Test
    fun `every frame the ladder can make has even sides at any shape`() {
        val rnd = Random(3)
        repeat(300) {
            val ratio = 0.2f + rnd.nextFloat() * 4f
            val f = ReelFrame.of(ReelAspect.ORIGINAL, listOf(1080, 720, 480)[rnd.nextInt(3)], ratio)
            assertTrue(f.width % 2 == 0 && f.height % 2 == 0)
            assertTrue(f.ratio in 0.55f..1.8f)
        }
    }

    @Test
    fun `the ladder goes 1080, 720, 720 with cuts, 480 with cuts`() {
        assertEquals(
            listOf(ReelAttempt(1080, true), ReelAttempt(720, true), ReelAttempt(720, false), ReelAttempt(480, false)),
            ReelAttempts.from(1080, true),
        )
        assertEquals(listOf(ReelAttempt(720, true), ReelAttempt(720, false), ReelAttempt(480, false)), ReelAttempts.from(720, true))
        assertEquals(listOf(ReelAttempt(480, true), ReelAttempt(480, false)), ReelAttempts.from(480, true))
    }

    @Test
    fun `plain cuts have no transitions on any rung`() {
        assertEquals(listOf(ReelAttempt(1080, false), ReelAttempt(720, false), ReelAttempt(480, false)), ReelAttempts.from(1080, false))
        assertTrue(ReelAttempts.from(900, false).all { !it.transitions })
    }

    @Test
    fun `a size between rungs starts at the rung below it, and a tiny one at 480`() {
        assertEquals(720, ReelAttempts.from(900, true).first().shortSide)
        assertEquals(480, ReelAttempts.from(100, true).first().shortSide)
        assertEquals(1080, ReelAttempts.from(4000, true).first().shortSide)
    }

    @Test
    fun `smaller rungs ask for less bitrate`() {
        assertTrue(ReelAttempt(1080, true).bitrate > ReelAttempt(720, true).bitrate)
        assertTrue(ReelAttempt(720, true).bitrate > ReelAttempt(480, true).bitrate)
    }

    // ---- the flat plan --------------------------------------------------------------------------------------

    @Test
    fun `cuts only is one layer, the items end to end, no overlap`() {
        val p = project(still(1, 3_000, fade), still(2, 2_500, slide), still(3, 4_000))
        val plan = build(p, ReelAttempt(1080, false))
        assertEquals(1, plan.layerCount)
        assertFalse(plan.isLayered)
        assertTrue(plan.joins.isEmpty())
        assertEquals(listOf(0L, 3_000_000L, 5_500_000L), plan.items.map { it.startUs })
        assertEquals(9_500_000L, plan.totalUs)
        assertEquals(9_500L, plan.totalMs)
        assertTrue(plan.items.all { it.layer == 0 && it.gapBeforeUs == 0L })
    }

    @Test
    fun `a project of cuts is flat even with transitions allowed`() {
        val plan = build(project(still(1), still(2), still(3)))
        assertEquals(1, plan.layerCount)
        assertEquals(9_000_000L, plan.totalUs)
    }

    @Test
    fun `a single item is flat`() {
        val plan = build(project(still(1, 3_000, fade)))
        assertEquals(1, plan.layerCount)
        assertEquals(3_000_000L, plan.totalUs)
    }

    @Test
    fun `an empty project plans nothing`() {
        val plan = build(ReelProject())
        assertTrue(plan.items.isEmpty())
        assertEquals(0L, plan.totalUs)
        assertNull(plan.audio)
    }

    // ---- the layered plan ----------------------------------------------------------------------------------------

    @Test
    fun `hand-overs make two layers and the reel is shorter by the overlaps`() {
        val p = project(still(1, 3_000, fade), still(2, 3_000, fade), still(3, 3_000))
        val plan = build(p)
        assertEquals(2, plan.layerCount)
        assertEquals(p.totalDurationMs * 1000L, plan.totalUs)
        assertEquals(8_000_000L, plan.totalUs)
        assertEquals(listOf(0L, 2_500_000L, 5_000_000L), plan.items.map { it.startUs })
        assertEquals(2, plan.joins.size)
        assertEquals(PlannedJoin(0, fade, 2_500_000L, 500_000L), plan.joins[0])
        assertEquals(PlannedJoin(1, fade, 5_000_000L, 500_000L), plan.joins[1])
    }

    @Test
    fun `the last item is always on layer 0 and layers alternate`() {
        for (n in 2..9) {
            val plan = build(ReelProject(List(n) { still(it + 1, 3_000, fade) }))
            assertEquals("last of $n", 0, plan.items.last().layer)
            assertEquals((0 until n).map { (n - 1 - it) % 2 }, plan.items.map { it.layer })
        }
    }

    @Test
    fun `a layer that starts later holds the empty time before its first item`() {
        val plan = build(project(still(1, 3_000, fade), still(2, 3_000)))
        // Item 1 (the last) is layer 0 and starts at 2.5 s; item 0 is layer 1 and starts at 0.
        assertEquals(0, plan.items[1].layer)
        assertEquals(2_500_000L, plan.items[1].gapBeforeUs)
        assertEquals(1, plan.items[0].layer)
        assertEquals(0L, plan.items[0].gapBeforeUs)
    }

    @Test
    fun `gaps and items tile every layer exactly, for any mix of hand-overs`() {
        val rnd = Random(11)
        repeat(200) {
            val n = 1 + rnd.nextInt(12)
            val items = List(n) { i -> still(i + 1, 500L + 100L * rnd.nextInt(45), ReelTransition.entries[rnd.nextInt(3)]) }
            val p = ReelProject(items)
            val plan = build(p)
            assertEquals(p.totalDurationMs * 1000L, plan.totalUs)
            for (layer in 0 until plan.layerCount) {
                var end = 0L
                for (item in plan.itemsOn(layer)) {
                    assertTrue("gap is never negative", item.gapBeforeUs >= 0L)
                    assertEquals("a layer has no overlap with itself", end + item.gapBeforeUs, item.startUs)
                    end = item.endUs
                }
            }
            // The last item ends the reel, and nothing ends after it.
            assertEquals(plan.totalUs, plan.items.last().endUs)
            assertTrue(plan.items.all { it.endUs <= plan.totalUs })
        }
    }

    // ---- what each layer shows --------------------------------------------------------------------------------------

    /** A pretend picture: each item is the flat colour (index + 1); layers are composited back to front with ordinary alpha. */
    private fun shown(plan: ReelRenderPlan, t: Long): Pair<Float, Float> {
        var value = 0f
        var cover = 0f
        for (layer in plan.layerCount - 1 downTo 0) {
            val item = plan.items.firstOrNull { it.layer == layer && t >= it.startUs && t < it.endUs }
            val a = ReelLayers.stateAt(plan, layer, t).alpha
            val c = if (item != null) item.index + 1f else 0f
            value = a * c + (1f - a) * value
            cover = a + (1f - a) * cover
        }
        return value to cover
    }

    @Test
    fun `a cross-fade is a straight mix of the two pictures at every moment, and the frame is always full`() {
        val plan = build(project(still(1, 4_000, fade), still(2, 4_000, fade), still(3, 4_000)))
        var t = 0L
        while (t < plan.totalUs) {
            val (value, cover) = shown(plan, t)
            assertEquals("t=$t covered", 1f, cover, 1e-4f)
            val join = plan.joins.firstOrNull { t >= it.startUs && t < it.startUs + it.durationUs }
            val expected = if (join != null) {
                val p = (t - join.startUs).toFloat() / join.durationUs
                (1f - p) * (join.fromIndex + 1) + p * (join.fromIndex + 2)
            } else {
                plan.items.first { t >= it.startUs && t < it.endUs }.index + 1f
            }
            assertEquals("t=$t", expected, value, 1e-3f)
            t += 25_000L
        }
    }

    @Test
    fun `random reels always fill the frame, and a fade is always the mix of the two it joins`() {
        val rnd = Random(19)
        repeat(120) {
            val n = 2 + rnd.nextInt(9)
            val items = List(n) { i -> still(i + 1, 600L + 100L * rnd.nextInt(40), listOf(fade, fade, cut)[rnd.nextInt(3)]) }
            val plan = build(ReelProject(items))
            var t = 0L
            while (t < plan.totalUs) {
                val (value, cover) = shown(plan, t)
                assertEquals(1f, cover, 1e-4f)
                val join = plan.joins.firstOrNull { it.transition == fade && t >= it.startUs && t < it.startUs + it.durationUs }
                if (join != null) {
                    val p = (t - join.startUs).toFloat() / join.durationUs
                    assertEquals((1f - p) * (join.fromIndex + 1) + p * (join.fromIndex + 2), value, 1e-3f)
                }
                t += 37_000L
            }
        }
    }

    @Test
    fun `a slide pushes the old picture out to the left while the new one comes in from the right`() {
        val plan = build(project(still(1, 4_000, slide), still(2, 4_000)))
        val join = plan.joins.single()
        val outgoing = plan.items[0]
        val incoming = plan.items[1]
        for (step in 0..10) {
            val t = join.startUs + join.durationUs * step / 11
            val p = (t - join.startUs).toFloat() / join.durationUs
            val eased = ReelGeometry.ease(p)
            val a = ReelLayers.stateAt(plan, outgoing.layer, t)
            val b = ReelLayers.stateAt(plan, incoming.layer, t)
            assertEquals(1f, a.alpha, 0f)
            assertEquals(1f, b.alpha, 0f)
            assertEquals(-2f * eased, a.shiftX, 1e-5f)
            assertEquals(2f * (1f - eased), b.shiftX, 1e-5f)
            assertEquals("the two edges meet: one frame wide apart", 2f, b.shiftX - a.shiftX, 1e-4f)
        }
        // Before the hand-over the first is in place; after it the second is.
        assertEquals(LayerState.SHOWN, ReelLayers.stateAt(plan, outgoing.layer, 1_000_000L))
        assertEquals(LayerState.SHOWN, ReelLayers.stateAt(plan, incoming.layer, join.startUs + join.durationUs))
    }

    @Test
    fun `a layer shows nothing outside its items`() {
        val plan = build(project(still(1, 3_000, fade), still(2, 3_000, fade), still(3, 3_000)))
        // Item 1 is on layer 1 and runs 2.5 s to 5.5 s.
        assertEquals(LayerState.HIDDEN, ReelLayers.stateAt(plan, 1, 1_000_000L))
        assertEquals(LayerState.HIDDEN, ReelLayers.stateAt(plan, 1, 6_000_000L))
        assertEquals(LayerState.HIDDEN, ReelLayers.stateAt(plan, 0, -5L))
        assertEquals(LayerState.HIDDEN, ReelLayers.stateAt(plan, 0, plan.totalUs))
    }

    @Test
    fun `only the top layer changes opacity in a fade`() {
        val plan = build(project(still(1, 3_000, fade), still(2, 3_000, fade), still(3, 3_000)))
        var t = 0L
        while (t < plan.totalUs) {
            val state = ReelLayers.stateAt(plan, 1, t)
            if (state != LayerState.HIDDEN) assertEquals("layer 1 is opaque while it shows", 1f, state.alpha, 0f)
            t += 20_000L
        }
    }

    @Test
    fun `a cut has no overlap and no change of state`() {
        val plan = build(project(still(1, 3_000, cut), still(2, 3_000, fade), still(3, 3_000)))
        assertEquals(1, plan.joins.size)
        assertEquals(1, plan.joins.single().fromIndex)
    }

    // ---- items -------------------------------------------------------------------------------------------------

    @Test
    fun `clips keep their trim and cards their words`() {
        val clip = ClipItem("c", 9L, 2_000L, 5_500L, 20_000L)
        val card = TitleItem("t", "June 2025\nLisbon", 0xFF112233.toInt(), 2_500L)
        val plan = build(project(card, clip, still(1)), ReelAttempt(1080, false))
        val c = plan.items[1]
        assertEquals(PlannedKind.CLIP, c.kind)
        assertEquals(9L, c.mediaId)
        assertEquals(2_000_000L, c.inUs)
        assertEquals(5_500_000L, c.outUs)
        assertEquals(3_500_000L, c.durationUs)
        val t = plan.items[0]
        assertEquals(PlannedKind.CARD, t.kind)
        assertEquals("June 2025\nLisbon", t.text)
        assertEquals(0xFF112233.toInt(), t.colorArgb)
        assertEquals(0L, t.mediaId)
    }

    @Test
    fun `a still keeps its move in a fixed shape, and loses it when each picture keeps its own shape`() {
        val zoom = ReelMotion.ZoomIn()
        assertEquals(zoom, build(project(still(1, 3_000, cut, zoom), still(2))).items[0].motion)
        val original = build(project(still(1, 3_000, cut, zoom), still(2), aspect = ReelAspect.ORIGINAL))
        assertEquals(ReelFit.CONTAIN, original.fit)
        assertEquals(ReelMotion.None, original.items[0].motion)
    }

    // ---- sound ----------------------------------------------------------------------------------------------

    private val track = ReelSoundtrack("content://music/1", trimStartMs = 10_000L, fadeOutMs = 2_000L)

    @Test
    fun `the soundtrack is cut to the reel with a fade at its end`() {
        val plan = build(project(still(1), still(2), sound = track), sound = SoundtrackFacts(180_000L))
        val a = plan.audio!!
        assertEquals(10_000_000L, a.startUs)
        assertEquals(6_000_000L, a.lengthUs)
        assertEquals(16_000_000L, a.endUs)
        assertEquals(2_000_000L, a.fadeOutUs)
        assertEquals(1f, a.gain, 0f)
        assertTrue(plan.hasAudio)
    }

    @Test
    fun `the volume holds, then falls in a straight line to nothing at the end`() {
        val a = ReelAudioPlan("u", 0L, 10_000_000L, 4_000_000L, 1f)
        assertEquals(1f, a.gainAtUs(0L), 0f)
        assertEquals(1f, a.gainAtUs(5_999_999L), 0f)
        assertEquals(1f, a.gainAtUs(6_000_000L), 1e-6f)
        assertEquals(0.5f, a.gainAtUs(8_000_000L), 1e-6f)
        assertEquals(0.25f, a.gainAtUs(9_000_000L), 1e-6f)
        assertEquals(0f, a.gainAtUs(10_000_000L), 0f)
        assertEquals(0f, a.gainAtUs(-1L), 0f)
        // Never rises again once it falls.
        var last = 2f
        var t = 6_000_000L
        while (t < 10_000_000L) {
            val g = a.gainAtUs(t)
            assertTrue(g <= last)
            last = g
            t += 100_000L
        }
    }

    @Test
    fun `a track shorter than the reel ends where the file ends, with its own fade`() {
        val plan = build(project(still(1), still(2), still(3), sound = track.copy(trimStartMs = 0L)), sound = SoundtrackFacts(5_000L))
        val a = plan.audio!!
        assertEquals(5_000_000L, a.lengthUs)
        assertEquals(2_000_000L, a.fadeOutUs)
        assertTrue("not looped: shorter than the reel", a.lengthUs < plan.totalUs)
    }

    @Test
    fun `a fade longer than the sound is the whole sound`() {
        val a = ReelAudioPlan.plan(track.copy(fadeOutMs = 60_000L), 180_000L, 6_000_000L, false)!!
        assertEquals(6_000_000L, a.fadeOutUs)
        assertEquals(1f, a.gainAtUs(0L), 1e-6f)
        assertEquals(0.5f, a.gainAtUs(3_000_000L), 1e-6f)
    }

    @Test
    fun `no fade means the sound simply ends`() {
        val a = ReelAudioPlan.plan(track.copy(fadeOutMs = 0L), 180_000L, 6_000_000L, false)!!
        assertEquals(0L, a.fadeOutUs)
        assertEquals(1f, a.gainAtUs(5_999_000L), 0f)
        assertEquals(0f, a.gainAtUs(6_000_000L), 0f)
    }

    @Test
    fun `a file that ends before it should start plays nothing`() {
        assertNull(ReelAudioPlan.plan(track, 10_000L, 6_000_000L, false))
        assertNull(ReelAudioPlan.plan(track, 9_000L, 6_000_000L, false))
        assertNotNull(ReelAudioPlan.plan(track, 10_001L, 6_000_000L, false))
        assertNull(ReelAudioPlan.plan(track, 180_000L, 0L, false))
        assertNull(ReelAudioPlan.plan(track.copy(uri = " "), 180_000L, 6_000_000L, false))
    }

    @Test
    fun `a file that does not say how long it is is taken as long enough`() {
        val a = ReelAudioPlan.plan(track, 0L, 6_000_000L, false)!!
        assertEquals(6_000_000L, a.lengthUs)
    }

    @Test
    fun `music is quieter under the sound of clips`() {
        val clip = ClipItem("c", 9L, 0L, 3_000L, 5_000L)
        val mixed = build(project(clip, still(1), sound = track), sound = SoundtrackFacts(60_000L), keep = true)
        assertTrue(mixed.keepClipSound)
        assertEquals(ReelAudioPlan.MIXED_GAIN, mixed.audio!!.gain, 0f)
        val alone = build(project(clip, still(1), sound = track), sound = SoundtrackFacts(60_000L), keep = false)
        assertFalse(alone.keepClipSound)
        assertEquals(1f, alone.audio!!.gain, 0f)
    }

    @Test
    fun `keeping the sound of clips means nothing when there is no clip`() {
        val plan = build(project(still(1), still(2)), keep = true)
        assertFalse(plan.keepClipSound)
        assertFalse(plan.hasAudio)
    }

    @Test
    fun `no soundtrack file facts means no sound, even if the project names one`() {
        assertNull(build(project(still(1), sound = track), sound = null).audio)
        assertNull(build(project(still(1)), sound = SoundtrackFacts(60_000L)).audio)
    }

    @Test
    fun `a plan with neither sound is silent`() {
        assertFalse(build(project(ClipItem("c", 9L, 0L, 3_000L, 5_000L))).hasAudio)
    }
}
