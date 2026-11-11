package com.fotoxplorr.app.reel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale
import kotlin.random.Random

/**
 * The auto-draft, with answers from a table instead of pictures: how a pile is cut into scenes, what is
 * thrown out, how the length is fitted, and that the result is a plan the person can edit.
 */
class ReelDraftBuilderTest {

    private val minute = 60_000L
    private val t0 = 1_750_000_000_000L // 15 June 2025, 15:06 UTC

    private val options = ReelDraftOptions(zone = ZoneOffset.UTC, locale = Locale.ENGLISH)
    private val noTitle = options.copy(withTitleCard = false)

    private fun photo(id: Long, atMin: Long, fav: Boolean = false, lat: Double? = null, lon: Double? = null, w: Int = 4000, h: Int = 3000) =
        ReelCandidate(id, t0 + atMin * minute, false, 0L, w, h, lat, lon, fav)

    private fun video(id: Long, atMin: Long, ms: Long = 8_000L, fav: Boolean = false) =
        ReelCandidate(id, t0 + atMin * minute, true, ms, 1920, 1080, null, null, fav)

    private class Fake(
        val sharp: Map<Long, Float> = emptyMap(),
        val sig: Map<Long, Int> = emptyMap(),
        val moments: Map<Long, Long> = emptyMap(),
        val focus: Map<Long, NormPoint> = emptyMap(),
    ) : ReelSignals {
        val sharpAsked = ArrayList<Long>()
        val sigAsked = ArrayList<Long>()
        val momentAsked = ArrayList<Long>()
        val focusAsked = ArrayList<Long>()
        override fun sharpness(mediaId: Long): Float? { sharpAsked += mediaId; return sharp[mediaId] }
        override fun signature(mediaId: Long): Int? { sigAsked += mediaId; return sig[mediaId] }
        override fun keyMomentMs(mediaId: Long): Long? { momentAsked += mediaId; return moments[mediaId] }
        override fun focus(mediaId: Long): NormPoint? { focusAsked += mediaId; return focus[mediaId] }
    }

    private fun ReelProject.media(): List<Long> = items.mapNotNull {
        when (it) {
            is StillItem -> it.mediaId
            is ClipItem -> it.mediaId
            is TitleItem -> null
        }
    }

    // ---- nothing and next to nothing --------------------------------------------------------

    @Test
    fun `no candidates make an empty project`() {
        val d = ReelDraftBuilder.build(emptyList(), options = options)
        assertTrue(d.project.isEmpty)
        assertEquals(ReelDraftReport(0, 0, 0, 0, 0, 0, 0), d.report)
    }

    @Test
    fun `a video under half a second is left out`() {
        val d = ReelDraftBuilder.build(listOf(video(1, 0, ms = 400L), photo(2, 1)), options = noTitle)
        assertEquals(listOf(2L), d.project.media())
        assertEquals(1, d.report.unusable)
    }

    @Test
    fun `a wide photo in a tall frame still gets a usable pan when the window is a sliver`() {
        val strip = photo(1, 0, w = 6000, h = 100)
        val m = ReelDraftBuilder.autoMotion(strip, 0, 9f / 16f, null) as ReelMotion.Pan
        assertTrue(m.start.isUsable && m.end.isUsable)
    }

    @Test
    fun `candidates that cannot be placed leave an empty project and are counted`() {
        val d = ReelDraftBuilder.build(
            listOf(photo(1, 0).copy(dateTakenMillis = 0L), video(2, 0, ms = 0L), photo(3, 5), photo(3, 6)),
            options = options,
        )
        // Undated photo, video of no length, and the repeated id 3 (its second copy).
        assertEquals(3, d.report.unusable)
        assertEquals(listOf(3L), d.project.media())
    }

    @Test
    fun `one photo makes one photo and a card`() {
        val d = ReelDraftBuilder.build(listOf(photo(7, 0)), options = options)
        assertEquals(listOf(7L), d.project.media())
        assertTrue(d.project.items.first() is TitleItem)
        assertEquals(1, d.report.picked)
        assertEquals(1, d.report.scenes)
        assertTrue(ReelPlan.canRender(d.project))
    }

    @Test
    fun `one photo without a card is just that photo, shown as long as a photo may be`() {
        val d = ReelDraftBuilder.build(listOf(photo(7, 0)), options = noTitle)
        assertEquals(1, d.project.items.size)
        assertEquals(ReelLimits.STILL_MAX_MS, d.project.items[0].durationMs)
    }

    @Test
    fun `one video makes one clip no longer than four seconds`() {
        val d = ReelDraftBuilder.build(listOf(video(9, 0, ms = 60_000L)), options = noTitle)
        val clip = d.project.items.single() as ClipItem
        assertEquals(9L, clip.mediaId)
        assertEquals(ReelLimits.CLIP_MAX_MS, clip.durationMs)
        assertEquals(60_000L, clip.sourceDurationMs)
        assertTrue(ReelPlan.canRender(d.project))
    }

    // ---- scenes ---------------------------------------------------------------------------------

    @Test
    fun `time gaps cut the line into scenes with fades between them and cuts inside`() {
        val pile = listOf(
            photo(1, 0), photo(2, 5), photo(3, 10),
            photo(4, 200), photo(5, 205), photo(6, 210),
            photo(7, 600), photo(8, 605), photo(9, 610),
        )
        val d = ReelDraftBuilder.build(pile, options = options)
        assertEquals(3, d.report.scenes)
        assertEquals((1L..9L).toList(), d.project.media())
        val fades = d.project.items.mapIndexedNotNull { i, item -> i.takeIf { item.transition == ReelTransition.CROSS_FADE } }
        // After the card, and after the third and sixth photos (indexes shift by one for the card).
        assertEquals(listOf(0, 3, 6), fades)
        assertEquals(ReelTransition.CUT, d.project.items.last().transition)
    }

    @Test
    fun `a gap exactly as long as the limit stays one scene and one millisecond more splits`() {
        val gap = options.sceneGapMs
        val same = listOf(photo(1, 0), photo(2, 0).copy(dateTakenMillis = t0 + gap))
        assertEquals(1, ReelDraftBuilder.build(same, options = noTitle).report.scenes)
        val split = listOf(photo(1, 0), photo(2, 0).copy(dateTakenMillis = t0 + gap + 1))
        assertEquals(2, ReelDraftBuilder.build(split, options = noTitle).report.scenes)
    }

    @Test
    fun `places far apart split a scene even a minute apart`() {
        val pile = listOf(photo(1, 0, lat = 38.70, lon = -9.14), photo(2, 1, lat = 38.75, lon = -9.14))
        assertEquals(2, ReelDraftBuilder.build(pile, options = noTitle).report.scenes)
        val near = listOf(photo(1, 0, lat = 38.7000, lon = -9.14), photo(2, 1, lat = 38.7020, lon = -9.14))
        assertEquals(1, ReelDraftBuilder.build(near, options = noTitle).report.scenes)
    }

    @Test
    fun `a photo with no place does not split and the place is remembered across it`() {
        val pile = listOf(
            photo(1, 0, lat = 38.70, lon = -9.14),
            photo(2, 1),
            photo(3, 2, lat = 38.75, lon = -9.14),
        )
        assertEquals(2, ReelDraftBuilder.build(pile, options = noTitle).report.scenes)
    }

    @Test
    fun `an impossible place is no place`() {
        val pile = listOf(photo(1, 0, lat = 38.70, lon = -9.14), photo(2, 1, lat = 95.0, lon = 400.0))
        assertEquals(1, ReelDraftBuilder.build(pile, options = noTitle).report.scenes)
    }

    @Test
    fun `distance between places is the great circle distance`() {
        val a = photo(1, 0, lat = 0.0, lon = 0.0)
        val b = photo(2, 0, lat = 0.0, lon = 1.0)
        assertEquals(111_195.0, ReelDraftBuilder.distanceMeters(a, b), 200.0)
        assertEquals(0.0, ReelDraftBuilder.distanceMeters(a, a), 1e-6)
    }

    // ---- duplicates, blur, favourites -----------------------------------------------------------

    @Test
    fun `near duplicates by signature keep the sharper of each pair`() {
        val pile = listOf(photo(1, 0), photo(2, 1), photo(3, 2), photo(4, 3))
        val fake = Fake(
            sharp = mapOf(1L to 200f, 2L to 100f, 3L to 150f, 4L to 300f),
            sig = mapOf(1L to 0b0, 2L to 0b1, 3L to 0xFFFF, 4L to (0xFFFF xor 0b10)),
        )
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(listOf(1L, 4L), d.project.media())
        assertEquals(2, d.report.duplicates)
    }

    @Test
    fun `signatures four bits apart are not duplicates`() {
        val pile = listOf(photo(1, 0), photo(2, 1))
        val fake = Fake(sharp = mapOf(1L to 100f, 2L to 100f), sig = mapOf(1L to 0, 2L to 0b1111))
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(2, d.project.items.size)
        assertEquals(0, d.report.duplicates)
    }

    @Test
    fun `with no signature a burst within three seconds is one photo`() {
        val burst = listOf(photo(1, 0), photo(2, 0).copy(dateTakenMillis = t0 + 1_000L))
        val fake = Fake(sharp = mapOf(1L to 50f, 2L to 90f))
        assertEquals(listOf(2L), ReelDraftBuilder.build(burst, fake, noTitle).project.media())
        val apart = listOf(photo(1, 0), photo(2, 0).copy(dateTakenMillis = t0 + 10_000L))
        assertEquals(2, ReelDraftBuilder.build(apart, fake, noTitle).project.items.size)
    }

    @Test
    fun `two shots a second apart that look different are both kept`() {
        val pair = listOf(photo(1, 0), photo(2, 0).copy(dateTakenMillis = t0 + 1_000L))
        val fake = Fake(sharp = mapOf(1L to 90f, 2L to 90f), sig = mapOf(1L to 0, 2L to -1))
        assertEquals(2, ReelDraftBuilder.build(pair, fake, noTitle).project.items.size)
    }

    @Test
    fun `a blurred photo is dropped when its scene has a sharp one`() {
        val pile = listOf(photo(1, 0), photo(2, 3))
        val fake = Fake(sharp = mapOf(1L to 10f, 2L to 120f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(listOf(2L), d.project.media())
        assertEquals(1, d.report.blurred)
    }

    @Test
    fun `a favourite is never called blurred`() {
        val pile = listOf(photo(1, 0, fav = true), photo(2, 3))
        val fake = Fake(sharp = mapOf(1L to 5f, 2L to 120f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(listOf(1L, 2L), d.project.media())
        assertEquals(0, d.report.blurred)
    }

    @Test
    fun `a scene of only blurred photos keeps its best so the day is not missing`() {
        val pile = listOf(photo(1, 0), photo(2, 1), photo(3, 2))
        val fake = Fake(sharp = mapOf(1L to 5f, 2L to 30f, 3L to 12f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(listOf(2L), d.project.media())
        assertEquals(2, d.report.blurred)
    }

    @Test
    fun `a blurred photo does not stay when the scene has a video`() {
        val pile = listOf(photo(1, 0), photo(2, 1), video(3, 2))
        val fake = Fake(sharp = mapOf(1L to 5f, 2L to 8f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle)
        assertEquals(listOf(3L), d.project.media())
    }

    @Test
    fun `an unknown sharpness is not blurred`() {
        val pile = listOf(photo(1, 0), photo(2, 3))
        val d = ReelDraftBuilder.build(pile, Fake(sharp = mapOf(1L to 100f)), noTitle)
        assertEquals(listOf(1L, 2L), d.project.media())
        assertEquals(0, d.report.blurred)
    }

    @Test
    fun `with room for one, a favourite beats a sharper photo`() {
        val pile = listOf(photo(1, 0, fav = true), photo(2, 3))
        val fake = Fake(sharp = mapOf(1L to 60f, 2L to 900f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle.copy(targetMs = 5_000L))
        assertEquals(listOf(1L), d.project.media())
    }

    @Test
    fun `with room for one, the sharper photo wins between two ordinary ones`() {
        val pile = listOf(photo(1, 0), photo(2, 3))
        val fake = Fake(sharp = mapOf(1L to 60f, 2L to 900f))
        val d = ReelDraftBuilder.build(pile, fake, noTitle.copy(targetMs = 5_000L))
        assertEquals(listOf(2L), d.project.media())
    }

    // ---- fitting the length ---------------------------------------------------------------------

    private fun trip(scenes: Int, perScene: Int, withVideos: Boolean = false): List<ReelCandidate> {
        val out = ArrayList<ReelCandidate>()
        var id = 1L
        for (s in 0 until scenes) {
            for (i in 0 until perScene) {
                val at = s * 240L + i * 4L
                out += if (withVideos && i % 4 == 3) video(id++, at, ms = 6_000L + i * 700L) else photo(id++, at)
            }
        }
        return out
    }

    @Test
    fun `the reel never passes the target and is within one item of it`() {
        val pile = trip(scenes = 12, perScene = 9, withVideos = true)
        for (target in listOf(5_000L, 10_000L, 20_000L, 30_000L, 45_000L, 60_000L, 120_000L)) {
            for (title in listOf(true, false)) {
                val d = ReelDraftBuilder.build(pile, options = options.copy(targetMs = target, withTitleCard = title))
                val total = d.project.totalDurationMs
                assertTrue("target $target title $title total $total", total <= target)
                assertTrue("target $target title $title total $total", total >= target - ReelLimits.STILL_MAX_MS)
                assertTrue(ReelPlan.canRender(d.project))
            }
        }
    }

    @Test
    fun `the default target is thirty seconds`() {
        val d = ReelDraftBuilder.build(trip(10, 8), options = options)
        assertEquals(30_000L, ReelDraftOptions.DEFAULT_TARGET_MS)
        assertTrue(d.project.totalDurationMs in 26_000L..30_000L)
    }

    @Test
    fun `photos are shown between two and a half and three and a half seconds`() {
        val d = ReelDraftBuilder.build(trip(8, 6), options = options)
        for (item in d.project.items.filterIsInstance<StillItem>()) {
            assertTrue(item.durationMs in ReelLimits.STILL_MIN_MS..ReelLimits.STILL_MAX_MS)
        }
    }

    @Test
    fun `clips are cut to at most four seconds`() {
        val d = ReelDraftBuilder.build(trip(6, 8, withVideos = true), options = options)
        val clips = d.project.items.filterIsInstance<ClipItem>()
        assertTrue(clips.isNotEmpty())
        for (c in clips) {
            assertTrue(c.durationMs in 1..ReelLimits.CLIP_MAX_MS)
            assertTrue(c.inMs >= 0 && c.outMs <= c.sourceDurationMs)
        }
    }

    @Test
    fun `a target below five seconds is raised to five`() {
        val d = ReelDraftBuilder.build(trip(3, 3), options = noTitle.copy(targetMs = 100L))
        assertTrue(d.project.totalDurationMs in 2_500L..5_000L)
        assertEquals(1, d.project.items.size)
    }

    @Test
    fun `a target above five minutes is lowered to five minutes`() {
        val d = ReelDraftBuilder.build(trip(40, 20), options = options.copy(targetMs = 3_600_000L))
        assertTrue(d.project.totalDurationMs <= ReelDraftOptions.MAX_TARGET_MS)
    }

    @Test
    fun `fewer pictures than the target takes all of them`() {
        val d = ReelDraftBuilder.build(trip(2, 3), options = options)
        assertEquals(6, d.report.picked)
        assertEquals(0, d.report.overLength)
        assertTrue(d.project.totalDurationMs < 30_000L)
    }

    @Test
    fun `every scene gets its best before any scene gets a second`() {
        // 12 scenes, room for about nine photos: nine different scenes are visited, none twice.
        val pile = trip(12, 5)
        val d = ReelDraftBuilder.build(pile, options = noTitle)
        val scenesHit = d.project.media().map { (it - 1) / 5 }
        assertEquals(scenesHit.size, scenesHit.toSet().size)
    }

    @Test
    fun `when only some scenes fit the biggest are kept`() {
        val pile = listOf(photo(1, 0)) + listOf(photo(2, 300), photo(3, 303), photo(4, 306), photo(5, 309)) + listOf(photo(6, 600))
        val d = ReelDraftBuilder.build(pile, options = noTitle.copy(targetMs = 5_000L))
        assertTrue(d.project.media().single() in 2L..5L)
    }

    @Test
    fun `a big scene gets more of the reel than a small one but the small one is not lost`() {
        val big = (1L..30L).map { photo(it, it * 2) }
        val small = listOf(photo(100, 900), photo(101, 903))
        val d = ReelDraftBuilder.build(big + small, options = noTitle.copy(targetMs = 20_000L))
        val media = d.project.media()
        assertTrue(media.any { it >= 100 })
        assertTrue(media.count { it < 100 } > media.count { it >= 100 })
    }

    // ---- order ----------------------------------------------------------------------------------

    @Test
    fun `the reel is in the order the pictures were taken whatever order they came in`() {
        val pile = trip(5, 4, withVideos = true).shuffled(Random(3))
        val d = ReelDraftBuilder.build(pile, options = noTitle)
        val taken = pile.associate { it.mediaId to it.dateTakenMillis }
        val times = d.project.media().map { taken.getValue(it) }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `the same pile gives the same reel`() {
        val pile = trip(9, 7, withVideos = true)
        val fake = Fake(sharp = pile.associate { it.mediaId to (it.mediaId * 37 % 200).toFloat() })
        assertEquals(ReelDraftBuilder.build(pile, fake, options), ReelDraftBuilder.build(pile.reversed(), fake, options))
    }

    @Test
    fun `an auto drafted project round trips`() {
        val candidates = (1..40).map {
            ReelCandidate(it.toLong(), 1_700_000_000_000L + it * 1_000_000L, it % 7 == 0, 8_000L, 4000, 3000, 38.7 + it / 1000.0, -9.1)
        }
        val draft = ReelDraftBuilder.build(candidates, options = ReelDraftOptions(zone = ZoneOffset.UTC, locale = Locale.ENGLISH))
        assertTrue(draft.project.items.size > 3)
        assertEquals(draft.project, ReelCodec.decode(ReelCodec.encode(draft.project))!!.project)
    }

    @Test
    fun `item ids are unique and the project passes the plan check`() {
        val d = ReelDraftBuilder.build(trip(10, 10, withVideos = true), options = options)
        assertEquals(d.project.items.size, d.project.items.map { it.id }.toSet().size)
        assertEquals(emptyList<ReelIssue>(), ReelPlan.validate(d.project))
    }

    // ---- title card -----------------------------------------------------------------------------

    @Test
    fun `the card names the day, or the month, or both ends`() {
        val day = ReelDraftBuilder.build(listOf(photo(1, 0), photo(2, 5)), options = options)
        assertEquals("June 15, 2025", (day.project.items.first() as TitleItem).text)

        val month = ReelDraftBuilder.build(listOf(photo(1, 0), photo(2, 60 * 24 * 3)), options = options)
        assertEquals("June 2025", (month.project.items.first() as TitleItem).text)

        val span = ReelDraftBuilder.build(listOf(photo(1, 0), photo(2, 60 * 24 * 30)), options = options)
        assertEquals("June 15, 2025 – July 15, 2025", (span.project.items.first() as TitleItem).text)
    }

    @Test
    fun `the card carries the place on a second line`() {
        val d = ReelDraftBuilder.build(listOf(photo(1, 0)), options = options.copy(placeName = "  Lisbon "))
        assertEquals("June 15, 2025\nLisbon", (d.project.items.first() as TitleItem).text)
    }

    @Test
    fun `the card dates come from what was picked, not from what was left out`() {
        val pile = listOf(photo(1, 0), photo(2, 1), photo(3, 60 * 24 * 40))
        val fake = Fake(sharp = mapOf(1L to 100f, 2L to 100f, 3L to 100f))
        val d = ReelDraftBuilder.build(pile, fake, options)
        assertEquals(3, d.project.media().size)
        assertTrue((d.project.items.first() as TitleItem).text.contains("July"))
    }

    @Test
    fun `no card when it is not asked for or the reel is under ten seconds`() {
        assertFalse(ReelDraftBuilder.build(trip(3, 3), options = noTitle).project.items.first() is TitleItem)
        assertFalse(ReelDraftBuilder.build(trip(3, 3), options = options.copy(targetMs = 9_000L)).project.items.first() is TitleItem)
        assertTrue(ReelDraftBuilder.build(trip(3, 3), options = options.copy(targetMs = 10_000L)).project.items.first() is TitleItem)
    }

    @Test
    fun `no card for a reel with nothing in it`() {
        assertTrue(ReelDraftBuilder.build(listOf(photo(1, 0).copy(dateTakenMillis = 0)), options = options).project.isEmpty)
    }

    @Test
    fun `the card colour is steady for a day and comes from the palette`() {
        val a = ReelDraftBuilder.build(listOf(photo(1, 0)), options = options).project.items.first() as TitleItem
        val b = ReelDraftBuilder.build(listOf(photo(2, 5)), options = options).project.items.first() as TitleItem
        assertEquals(a.backgroundArgb, b.backgroundArgb)
        val later = ReelDraftBuilder.build(listOf(photo(1, 60 * 24)), options = options).project.items.first() as TitleItem
        assertNotEquals(a.backgroundArgb, later.backgroundArgb)
    }

    @Test
    fun `the draft keeps the aspect asked for`() {
        assertEquals(ReelAspect.TALL, ReelDraftBuilder.build(trip(2, 2), options = options.copy(aspect = ReelAspect.TALL)).project.aspect)
        assertEquals(null, ReelDraftBuilder.build(trip(2, 2), options = options).project.soundtrack)
    }

    // ---- clips ----------------------------------------------------------------------------------

    @Test
    fun `a clip starts a second before its key moment`() {
        assertEquals(4_000L to 8_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, 5_000L))
        assertEquals(1_000L to 5_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, 2_000L))
    }

    @Test
    fun `a clip with no key moment is taken from the middle`() {
        assertEquals(2_000L to 6_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, null))
    }

    @Test
    fun `a key moment near either end keeps the window inside the video`() {
        assertEquals(0L to 4_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, 200L))
        assertEquals(4_000L to 8_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, 7_900L))
        assertEquals(4_000L to 8_000L, ReelDraftBuilder.clipWindow(8_000L, 4_000L, 999_999L))
    }

    @Test
    fun `a video shorter than the window is used whole`() {
        assertEquals(0L to 2_500L, ReelDraftBuilder.clipWindow(2_500L, 4_000L, 1_000L))
    }

    @Test
    fun `the draft asks for the key moment of a chosen video`() {
        val fake = Fake(moments = mapOf(5L to 6_000L))
        val d = ReelDraftBuilder.build(listOf(video(5, 0, ms = 10_000L)), fake, noTitle)
        val clip = d.project.items.single() as ClipItem
        assertEquals(5_000L, clip.inMs)
        assertEquals(9_000L, clip.outMs)
    }

    // ---- motion ---------------------------------------------------------------------------------

    @Test
    fun `a photo with a known subject zooms toward it`() {
        val fake = Fake(focus = mapOf(1L to NormPoint(0.2f, 0.4f)))
        val item = ReelDraftBuilder.build(listOf(photo(1, 0)), fake, noTitle).project.items.single() as StillItem
        assertEquals(ReelMotion.ZoomIn(NormPoint(0.2f, 0.4f)), item.motion)
    }

    @Test
    fun `a wide photo in a tall frame pans across, one way then the other`() {
        val wide = photo(1, 0, w = 1600, h = 900)
        val m0 = ReelDraftBuilder.autoMotion(wide, 0, 9f / 16f, null) as ReelMotion.Pan
        val m1 = ReelDraftBuilder.autoMotion(wide, 1, 9f / 16f, null) as ReelMotion.Pan
        assertTrue(m0.start.left < m0.end.left)
        assertTrue(m1.start.left > m1.end.left)
        assertEquals(1f, m0.start.height, 1e-6f)
        assertTrue(m0.start.isUsable && m0.end.isUsable)
    }

    @Test
    fun `a tall photo in a wide frame pans down, then up`() {
        val tall = photo(1, 0, w = 900, h = 1600)
        val m0 = ReelDraftBuilder.autoMotion(tall, 0, 16f / 9f, null) as ReelMotion.Pan
        val m1 = ReelDraftBuilder.autoMotion(tall, 1, 16f / 9f, null) as ReelMotion.Pan
        assertTrue(m0.start.top < m0.end.top)
        assertTrue(m1.start.top > m1.end.top)
    }

    @Test
    fun `a photo that fits the frame zooms in, zooms out and pans in turn`() {
        val p = photo(1, 0, w = 1600, h = 900)
        val kinds = (0..5).map { ReelDraftBuilder.autoMotion(p, it, 16f / 9f, null)::class }
        assertEquals(
            listOf(ReelMotion.ZoomIn::class, ReelMotion.ZoomOut::class, ReelMotion.Pan::class, ReelMotion.ZoomIn::class, ReelMotion.ZoomOut::class, ReelMotion.Pan::class),
            kinds,
        )
        assertEquals(ReelMotion.ZoomIn(NormPoint.CENTRE), ReelDraftBuilder.autoMotion(p, 0, 16f / 9f, null))
    }

    @Test
    fun `in the original shape a photo never pans along a side`() {
        val wide = photo(1, 0, w = 1600, h = 900)
        for (n in 0..5) {
            val m = ReelDraftBuilder.autoMotion(wide, n, null, null)
            if (m is ReelMotion.Pan) {
                assertTrue(m.start.width < 1f && m.start.height < 1f)
            }
        }
    }

    @Test
    fun `every motion the draft makes passes the plan check`() {
        val rnd = Random(5)
        val shapes = listOf(16f / 9f, 9f / 16f, 1f, null)
        repeat(100) {
            val c = photo(1, 0, w = rnd.nextInt(100, 6000), h = rnd.nextInt(100, 6000))
            val m = ReelDraftBuilder.autoMotion(c, rnd.nextInt(0, 12), shapes[rnd.nextInt(4)], null)
            assertTrue(ReelPlan.canRender(ReelProject(listOf(StillItem("r1", 1, motion = m)))))
        }
    }

    @Test
    fun `a photo of unknown size still gets a valid motion`() {
        val m = ReelDraftBuilder.autoMotion(photo(1, 0, w = 0, h = 0), 2, 9f / 16f, null)
        assertTrue(ReelPlan.canRender(ReelProject(listOf(StillItem("r1", 1, motion = m)))))
    }

    // ---- what is asked of the signals -----------------------------------------------------------

    @Test
    fun `a photo alone in its scene costs no sharpness and no signature`() {
        val pile = listOf(photo(1, 0), photo(2, 300), photo(3, 600))
        val fake = Fake()
        ReelDraftBuilder.build(pile, fake, noTitle)
        assertTrue(fake.sharpAsked.isEmpty())
        assertTrue(fake.sigAsked.isEmpty())
    }

    @Test
    fun `each signal is asked at most once per picture`() {
        val pile = trip(4, 8, withVideos = true)
        val fake = Fake()
        ReelDraftBuilder.build(pile, fake, options)
        for (asked in listOf(fake.sharpAsked, fake.sigAsked, fake.momentAsked, fake.focusAsked)) {
            assertEquals(asked.size, asked.toSet().size)
        }
        // The expensive ones are asked only about pictures of a scene that has company.
        assertTrue(fake.sharpAsked.isNotEmpty())
    }

    @Test
    fun `focus and key moment are asked only for what was chosen`() {
        val pile = trip(8, 12, withVideos = true)
        val fake = Fake()
        val d = ReelDraftBuilder.build(pile, fake, options)
        val chosen = d.project.media().toSet()
        assertTrue(fake.focusAsked.all { it in chosen })
        assertTrue(fake.momentAsked.all { it in chosen })
    }

    // ---- many ----------------------------------------------------------------------------------

    @Test
    fun `random piles always give a valid plan inside the target`() {
        val rnd = Random(21)
        repeat(60) { round ->
            val n = rnd.nextInt(0, 120)
            val pile = (1..n).map {
                val at = rnd.nextLong(0, 60L * 24 * 10)
                if (rnd.nextInt(5) == 0) video(it.toLong(), at, ms = rnd.nextLong(0, 40_000))
                else photo(it.toLong(), at, fav = rnd.nextInt(10) == 0, lat = if (rnd.nextBoolean()) 38.7 + rnd.nextDouble(0.0, 0.1) else null, lon = -9.1)
            }
            val fake = Fake(
                sharp = pile.filter { rnd.nextBoolean() }.associate { it.mediaId to rnd.nextFloat() * 200f },
                sig = pile.filter { rnd.nextBoolean() }.associate { it.mediaId to rnd.nextInt() },
            )
            val target = rnd.nextLong(5_000, 90_000)
            val d = ReelDraftBuilder.build(pile, fake, options.copy(targetMs = target, withTitleCard = rnd.nextBoolean()))
            assertTrue("round $round", ReelPlan.canRender(d.project))
            assertTrue("round $round total ${d.project.totalDurationMs} target $target", d.project.totalDurationMs <= target)
            val r = d.report
            assertEquals("round $round", n, r.considered)
            assertEquals("round $round", r.picked, d.project.media().size)
            // Every candidate is picked or counted once as left out.
            assertEquals("round $round", n, r.picked + r.unusable + r.duplicates + r.blurred + r.overLength)
        }
    }

    @Test
    fun `two hundred candidates are drafted fast`() {
        val pile = trip(25, 8, withVideos = true)
        assertEquals(200, pile.size)
        val fake = Fake(
            sharp = pile.associate { it.mediaId to (it.mediaId * 53 % 180).toFloat() },
            sig = pile.associate { it.mediaId to (it.mediaId / 3).toInt() },
        )
        val started = System.nanoTime()
        val d = ReelDraftBuilder.build(pile, fake, options)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(d.project.items.size > 5)
        // A wide margin for a cold JIT on a slow machine; the work is a few thousand comparisons.
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun `five thousand candidates in one scene are still drafted quickly`() {
        val pile = (1L..5_000L).map { photo(it, it / 20) }
        val fake = Fake(
            sharp = pile.associate { it.mediaId to (it.mediaId * 53 % 180).toFloat() },
            sig = pile.associate { it.mediaId to (it.mediaId * 2_654_435_761L).toInt() },
        )
        val started = System.nanoTime()
        ReelDraftBuilder.build(pile, fake, options)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 5_000)
    }
}
