package com.fotoxplorr.app.reel

import android.graphics.Matrix
import androidx.media3.common.C
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Composition built from a plan, looked at as plain objects: how many tracks, what is in each, where the
 * empty stretches are, what effects each item carries. Nothing is decoded or encoded, so this shows the wiring and the numbers,
 * not that a phone's codecs accept it; that is on the device list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReelCompositionTest {
    private val fade = ReelTransition.CROSS_FADE
    private val gapId = "androidx-media3-GapMediaItem"

    private fun still(n: Int, ms: Long = 3_000L, tr: ReelTransition = ReelTransition.CUT, motion: ReelMotion = ReelMotion.None) =
        StillItem("r$n", n.toLong(), ms, motion, tr)

    private fun clip(n: Int, tr: ReelTransition = ReelTransition.CUT) = ClipItem("r$n", n.toLong(), 1_000L, 4_500L, 10_000L, tr)
    private fun card(n: Int) = TitleItem("r$n", "June 2025", 0xFF223344.toInt(), 2_500L)

    private fun plan(
        project: ReelProject,
        attempt: ReelAttempt = ReelAttempt(1080, true),
        sound: SoundtrackFacts? = null,
        keep: Boolean = false,
    ): ReelRenderPlan = ReelRenderPlan.build(project, attempt, ReelFrame.of(project.aspect, attempt.shortSide), ReelFit.of(project.aspect), sound, keep)

    private fun stagedFor(plan: ReelRenderPlan): ReelStaged {
        val pictures = HashMap<String, StagedPicture>()
        val clips = HashMap<String, String>()
        for (item in plan.items) {
            when (item.kind) {
                PlannedKind.CLIP -> clips[item.id] = "content://media/external/video/media/${item.mediaId}"
                PlannedKind.STILL -> pictures[item.id] = StagedPicture(File("/cache/still-${item.id}.jpg"), 4000, 3000)
                PlannedKind.CARD -> pictures[item.id] = StagedPicture(File("/cache/card-${item.id}.png"), plan.frame.width, plan.frame.height)
            }
        }
        return ReelStaged(pictures, clips)
    }

    private fun build(plan: ReelRenderPlan): Composition = ReelComposition.build(plan, stagedFor(plan))

    private fun EditedMediaItem.isGap() = mediaItem.mediaId == gapId

    // ---- tracks ---------------------------------------------------------------------------------------------

    @Test
    fun `cuts only is one video track with the items in order`() {
        val p = plan(ReelProject(listOf(still(1), clip(2), card(3))))
        val c = build(p)
        assertEquals(1, c.sequences.size)
        val items = c.sequences[0].editedMediaItems
        assertEquals(3, items.size)
        assertTrue(items.none { it.isGap() })
        assertEquals(setOf(C.TRACK_TYPE_VIDEO), c.sequences[0].trackTypes)
        assertSame("no compositor for one track", VideoCompositorSettings.DEFAULT, c.videoCompositorSettings)
    }

    @Test
    fun `hand-overs make two video tracks with the empty stretches between their items`() {
        val p = plan(ReelProject(listOf(still(1, tr = fade), still(2, tr = fade), still(3))))
        val c = build(p)
        assertEquals(2, c.sequences.size)
        // Items 0 and 2 are on layer 0 (the last item always is), item 1 on layer 1.
        val first = c.sequences[0].editedMediaItems
        assertEquals(3, first.size)
        assertFalse(first[0].isGap())
        assertTrue(first[1].isGap())
        assertEquals("item 0 ends at 3 s, item 2 starts at 5 s", 2_000_000L, first[1].durationUs)
        assertFalse(first[2].isGap())
        val second = c.sequences[1].editedMediaItems
        assertEquals(2, second.size)
        assertTrue(second[0].isGap())
        assertEquals("item 1 starts at 2.5 s", 2_500_000L, second[0].durationUs)
        assertFalse(second[1].isGap())
        assertTrue(c.videoCompositorSettings is ReelCompositorSettings)
    }

    @Test
    fun `each track adds up to where its last item ends`() {
        val p = plan(ReelProject(List(6) { still(it + 1, 2_000L + 500L * it, if (it % 2 == 0) fade else ReelTransition.SLIDE) }))
        val c = build(p)
        for (layer in 0 until p.layerCount) {
            var total = 0L
            for (e in c.sequences[layer].editedMediaItems) {
                total += if (e.isGap()) e.durationUs else (e.mediaItem.localConfiguration?.imageDurationMs ?: 0L) * 1000L
            }
            assertEquals("layer $layer", p.itemsOn(layer).last().endUs, total)
        }
    }

    @Test
    fun `the colour mode tone-maps HDR sources, because the compositor takes SDR only`() {
        assertEquals(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL, build(plan(ReelProject(listOf(still(1))))).hdrMode)
    }

    @Test
    fun `the sound of clips needs an audio track in the video tracks too`() {
        val p = plan(ReelProject(listOf(clip(1, fade), still(2))), keep = true)
        val c = build(p)
        for (sequence in c.sequences) assertEquals(setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO), sequence.trackTypes)
        assertFalse("clip sound is kept", c.sequences[1].editedMediaItems.first { !it.isGap() }.removeAudio)
    }

    // ---- items -----------------------------------------------------------------------------------------------

    @Test
    fun `a still is an image for its time with a move and then a stretch to the frame`() {
        val zoom = ReelMotion.ZoomIn()
        val p = plan(ReelProject(listOf(still(1, 3_000L, motion = zoom))))
        val item = build(p).sequences[0].editedMediaItems.single()
        assertEquals(3_000L, item.mediaItem.localConfiguration!!.imageDurationMs)
        assertEquals(30, item.frameRate)
        assertEquals("file:///cache/still-r1.jpg", item.mediaItem.localConfiguration!!.uri.toString())
        assertEquals("said outright, not guessed from the name", "image/jpeg", item.mediaItem.localConfiguration!!.mimeType)
        val effects = item.effects.videoEffects
        assertEquals(2, effects.size)
        assertTrue(effects[0] is ReelKenBurns)
        assertTrue(effects[1] is Presentation)
        assertTrue("removing sound is moot for an image", item.effects.audioProcessors.isEmpty())
    }

    @Test
    fun `a title card and a picture that keeps its own shape are only stretched`() {
        val cards = build(plan(ReelProject(listOf(card(1), still(2))))).sequences[0].editedMediaItems
        assertEquals("a card is a PNG", "image/png", cards[0].mediaItem.localConfiguration!!.mimeType)
        assertEquals("a card has no move", 1, cards[0].effects.videoEffects.size)
        assertEquals("a fixed shape does move a still", 1, cards[0].effects.videoEffects.count { it is Presentation })
        val original = build(plan(ReelProject(listOf(still(1, motion = ReelMotion.ZoomIn())), aspect = ReelAspect.ORIGINAL))).sequences[0].editedMediaItems.single()
        assertEquals("the picture is already on its frame", listOf(Presentation::class.java), original.effects.videoEffects.map { it.javaClass })
    }

    @Test
    fun `a clip keeps its trim and is muted unless asked`() {
        val p = plan(ReelProject(listOf(clip(1), still(2))))
        val item = build(p).sequences[0].editedMediaItems[0]
        val clipping = item.mediaItem.clippingConfiguration
        assertEquals(1_000L, clipping.startPositionMs)
        assertEquals(4_500L, clipping.endPositionMs)
        assertEquals("content://media/external/video/media/1", item.mediaItem.localConfiguration!!.uri.toString())
        assertTrue(item.removeAudio)
        assertEquals(1, item.effects.videoEffects.size)
        assertTrue(item.effects.videoEffects[0] is Presentation)
    }

    @Test
    fun `an item with no staged picture or address is an error, not a hole`() {
        val p = plan(ReelProject(listOf(still(1))))
        try {
            ReelComposition.build(p, ReelStaged(emptyMap(), emptyMap()))
            fail("expected an error")
        } catch (expected: IllegalStateException) {
            // fine
        }
    }

    // ---- the soundtrack ----------------------------------------------------------------------------------------

    @Test
    fun `the soundtrack is its own audio track, trimmed, with the fade riding on it`() {
        val track = ReelSoundtrack("content://docs/music/1", trimStartMs = 12_000L, fadeOutMs = 1_500L)
        val p = plan(ReelProject(listOf(still(1), still(2)), soundtrack = track), sound = SoundtrackFacts(200_000L))
        val c = build(p)
        assertEquals(2, c.sequences.size)
        val audio = c.sequences.last()
        assertEquals(setOf(C.TRACK_TYPE_AUDIO), audio.trackTypes)
        val item = audio.editedMediaItems.single()
        assertEquals("content://docs/music/1", item.mediaItem.localConfiguration!!.uri.toString())
        assertEquals(12_000L, item.mediaItem.clippingConfiguration.startPositionMs)
        assertEquals(18_000L, item.mediaItem.clippingConfiguration.endPositionMs)
        assertTrue(item.removeVideo)
        val processor = item.effects.audioProcessors.single()
        assertTrue(processor is ReelFadeProcessor)
        assertTrue(item.effects.videoEffects.isEmpty())
    }

    @Test
    fun `no soundtrack and no clip sound means no audio track at all`() {
        val c = build(plan(ReelProject(listOf(clip(1), still(2)))))
        assertTrue(c.sequences.all { s -> s.trackTypes == setOf(C.TRACK_TYPE_VIDEO) })
        assertEquals(1, c.sequences.size)
    }

    // ---- the compositor ------------------------------------------------------------------------------------------

    @Test
    fun `the output is always the frame, whatever the first input is`() {
        val p = plan(ReelProject(listOf(still(1, tr = fade), still(2)), aspect = ReelAspect.TALL))
        val settings = ReelCompositorSettings(p)
        assertEquals(Size(1080, 1920), settings.getOutputSize(listOf(Size(1, 1), Size(4000, 3000))))
        assertEquals(Size(1080, 1920), settings.getOutputSize(emptyList()))
    }

    @Test
    fun `each input is drawn with its layer's opacity and push at that moment`() {
        val p = plan(ReelProject(listOf(still(1, 4_000L, fade), still(2, 4_000L, ReelTransition.SLIDE), still(3, 4_000L))))
        val settings = ReelCompositorSettings(p)
        for (layer in 0 until 2) {
            var t = 0L
            while (t < p.totalUs) {
                val expected = ReelLayers.stateAt(p, layer, t)
                val got = settings.getOverlaySettings(layer, t)
                assertEquals(expected.alpha, got.alphaScale, 0f)
                // The picture lands at (background anchor - its own anchor), and the library takes each only within -1..1.
                assertEquals(expected.shiftX, got.backgroundFrameAnchor.first - got.overlayFrameAnchor.first, 1e-6f)
                assertTrue(got.backgroundFrameAnchor.first in -1f..1f && got.overlayFrameAnchor.first in -1f..1f)
                assertEquals("never moves up or down", 0f, got.backgroundFrameAnchor.second - got.overlayFrameAnchor.second, 0f)
                assertEquals(1f, got.scale.first, 0f)
                t += 40_000L
            }
        }
    }

    @Test
    fun `a slide that has the picture a whole frame away is still a setting the library accepts`() {
        val p = plan(ReelProject(listOf(still(1, 4_000L, ReelTransition.SLIDE), still(2, 4_000L))))
        val settings = ReelCompositorSettings(p)
        val join = p.joins.single()
        val atStart = settings.getOverlaySettings(p.items[1].layer, join.startUs)
        assertEquals("the new picture starts one frame to the right", 2f, atStart.backgroundFrameAnchor.first - atStart.overlayFrameAnchor.first, 1e-6f)
        val outgoing = settings.getOverlaySettings(p.items[0].layer, join.startUs + join.durationUs - 1L)
        assertTrue("the old picture is nearly a whole frame to the left", outgoing.backgroundFrameAnchor.first - outgoing.overlayFrameAnchor.first < -1.9f)
    }

    @Test
    fun `an empty stretch is drawn invisible`() {
        val p = plan(ReelProject(listOf(still(1, 4_000L, fade), still(2, 4_000L))))
        val settings = ReelCompositorSettings(p)
        // Item 0 is on layer 1 and ends at 4 s; layer 1 has nothing after it.
        assertEquals(0f, settings.getOverlaySettings(1, 6_000_000L).alphaScale, 0f)
    }

    // ---- the move across a still --------------------------------------------------------------------------------

    private fun values(m: Matrix): FloatArray = FloatArray(9).also { m.getValues(it) }

    private fun cropOf(w: NdcWindow): Matrix {
        val crop = Crop(w.centerX - w.halfWidth, w.centerX + w.halfWidth, w.centerY - w.halfHeight, w.centerY + w.halfHeight)
        crop.configure(1000, 1000)
        return crop.getMatrix(0L)
    }

    @Test
    fun `the move's matrix is the library's own crop of the same window`() {
        // The export library's Crop is the one place its coordinate conventions (y up, centre then scale) are written down.
        val motion = ReelMotion.Pan(NormRect(0.05f, 0.1f, 0.45f, 0.5f), NormRect(0.4f, 0.45f, 0.9f, 0.95f))
        val span = 2_900_000L
        val effect = ReelKenBurns(motion, 4000, 3000, 16f / 9f, span)
        for (step in 0..10) {
            val t = 7_000_000L + span * step / 10
            val ours = values(effect.getMatrix(t))
            val progress = step / 10f
            val window = ReelStillMath.cropAt(motion, progress, 4000, 3000, 16f / 9f)
            val theirs = values(cropOf(window))
            for (i in ours.indices) assertEquals("step $step value $i", theirs[i], ours[i], 1e-4f)
        }
    }

    @Test
    fun `the move runs from the first frame it sees to the last, whatever the clock says`() {
        val effect = ReelKenBurns(ReelMotion.ZoomIn(), 4000, 3000, 16f / 9f, 1_000_000L)
        val start = values(effect.getMatrix(123_456_789L))
        val same = values(effect.getMatrix(123_456_789L))
        val end = values(effect.getMatrix(124_456_789L))
        val past = values(effect.getMatrix(999_999_999L))
        assertEquals(start.toList(), same.toList())
        assertNotEquals(start.toList(), end.toList())
        assertEquals("held at the end", end.toList(), past.toList())
        // Zoom in: the picture grows (scale above the start's) and the start is the plain cover.
        assertEquals(1f, start[Matrix.MSCALE_X], 1e-5f)
        assertEquals(ReelMotion.ZOOM_SCALE, end[Matrix.MSCALE_X], 1e-4f)
    }

    @Test
    fun `two items do not share a clock`() {
        val a = ReelKenBurns(ReelMotion.ZoomIn(), 4000, 3000, 16f / 9f, 1_000_000L)
        val b = ReelKenBurns(ReelMotion.ZoomIn(), 4000, 3000, 16f / 9f, 1_000_000L)
        a.getMatrix(0L)
        a.getMatrix(900_000L)
        val first = values(b.getMatrix(50_000_000L))
        assertEquals("b starts from its own first frame", 1f, first[Matrix.MSCALE_X], 1e-5f)
    }

    // ---- sorting a real export failure --------------------------------------------------------------------------

    @Test
    fun `a failure that has an out-of-memory error among its causes steps down for memory`() {
        val e = ExportException.createForUnexpected(RuntimeException("wrapped", OutOfMemoryError("heap")))
        assertEquals(ReelFailureKind.MEMORY, ReelFailures.classify(e, soundInPlan = true))
    }

    @Test
    fun `a codec failure is sorted by whether the codec was for sound`() {
        val audio = ExportException.createForCodec(IllegalStateException("x"), ExportException.ERROR_CODE_DECODING_FAILED, ExportException.CodecInfo("fmt", false, true, "c2.aac"))
        val video = ExportException.createForCodec(IllegalStateException("x"), ExportException.ERROR_CODE_DECODING_FAILED, ExportException.CodecInfo("fmt", true, true, "c2.avc"))
        assertEquals(ReelFailureKind.AUDIO, ReelFailures.classify(audio, soundInPlan = true))
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(audio, soundInPlan = false))
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(video, soundInPlan = true))
    }

    @Test
    fun `an unexpected runtime failure steps down`() {
        assertEquals(ReelFailureKind.STEP_DOWN, ReelFailures.classify(ExportException.createForUnexpected(IllegalStateException("x")), true))
    }
}
