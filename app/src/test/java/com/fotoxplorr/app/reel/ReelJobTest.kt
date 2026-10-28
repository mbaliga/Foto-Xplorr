package com.fotoxplorr.app.reel

import com.fotoxplorr.app.hyle.ActivityKind
import com.fotoxplorr.app.jobs.JobOutcome
import com.fotoxplorr.app.jobs.JobRunner
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.ZoneOffset

/**
 * [ReelJob] with a host, an encoder and a sink that are fakes: what the job refuses, in what order it stages
 * and renders, how it steps down, what it asks the person, and that it leaves nothing behind. What this cannot
 * show: anything the phone's codecs do, how long a real export takes, and the platform's own MediaStore.
 */
class ReelJobTest {
    private lateinit var work: File
    private val photoDate = 1_750_000_000_000L

    @Before
    fun directory() {
        work = File.createTempFile("reel-job-", "").apply { delete(); mkdirs() }
    }

    @After
    fun clean() {
        work.deleteRecursively()
    }

    // ---- fakes -----------------------------------------------------------------------------------------

    private fun asset(id: Long, video: Boolean = false, date: Long = photoDate + id) = MediaAsset(
        id = MediaId(id), contentUriString = "content://media/external/${if (video) "video" else "images"}/media/$id",
        displayName = "IMG_$id", mimeType = if (video) "video/mp4" else "image/jpeg", bucketName = null, dateTakenMillis = date,
        dateModifiedSeconds = 0, width = 4000, height = 3000, sizeBytes = 1, relativePath = null, isFavorite = false, isTrashed = false,
    )

    private class FakeHost(val library: Map<Long, MediaAsset>) : ReelHost {
        val blocked = HashSet<Long>()
        val unreadable = HashSet<Long>()
        var shape: Float? = 1.5f
        var probe: SoundtrackProbe = SoundtrackProbe.Usable(120_000L)
        val staged = ArrayList<Triple<Long, StageSpec, File>>()
        val cards = ArrayList<Pair<String, File>>()
        val shapeAsked = ArrayList<Long>()
        val probed = ArrayList<String>()
        var onStage: suspend (MediaAsset) -> Unit = {}
        var failCardWith: Throwable? = null

        override fun asset(mediaId: Long): MediaAsset? = library[mediaId]
        override fun mayUsePhoto(asset: MediaAsset): Boolean = asset.id.value !in blocked
        override fun mayUseVideo(asset: MediaAsset): Boolean = asset.id.value !in blocked
        override suspend fun shapeOf(asset: MediaAsset): Float? = shape.also { shapeAsked += asset.id.value }

        override suspend fun stagePhoto(asset: MediaAsset, spec: StageSpec, into: File): StagedPicture? {
            staged += Triple(asset.id.value, spec, into)
            onStage(asset)
            if (asset.id.value in unreadable || asset.id.value in blocked) return null
            into.writeBytes(ByteArray(64))
            return StagedPicture(into, 2000, 1500)
        }

        override suspend fun stageCard(text: String, backgroundArgb: Int, frame: ReelFrame, into: File): StagedPicture {
            failCardWith?.let { throw it }
            cards += text to into
            into.writeBytes(ByteArray(16))
            return StagedPicture(into, frame.width, frame.height)
        }

        override suspend fun probeSoundtrack(uri: String): SoundtrackProbe = probe.also { probed += uri }
    }

    private class FakeEncoder : ReelEncoder {
        var can = true
        val plans = ArrayList<ReelRenderPlan>()
        val stagedSeen = ArrayList<ReelStaged>()
        var behaviour: suspend (index: Int, plan: ReelRenderPlan) -> Unit = { _, _ -> }
        var content: ByteArray = cleanMp4()

        override fun canEncode(): Boolean = can

        override suspend fun encode(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit) {
            plans += plan
            stagedSeen += staged
            onProgress(0.3f)
            behaviour(plans.size - 1, plan)
            output.writeBytes(content)
            onProgress(1f)
        }
    }

    private class FakeSink(val dir: File) : ReelSink {
        class Saved(val stem: String, val date: Long, val bytes: Int)

        val saved = ArrayList<Saved>()
        val progress = ArrayList<Float>()
        var failWith: IOException? = null
        var flipBeforeAsk: () -> Unit = {}

        override fun stagingDirectory(): File = dir

        override suspend fun publish(staged: File, stem: String, dateTakenMillis: Long, stillAllowed: () -> Boolean, onProgress: (Float) -> Unit): String {
            flipBeforeAsk()
            if (!stillAllowed()) throw ReelProtectedException()
            failWith?.let { throw it }
            saved += Saved(stem, dateTakenMillis, staged.readBytes().size)
            onProgress(0.5f)
            onProgress(1f)
            return "content://media/external/video/media/${100 + saved.size}"
        }
    }

    private companion object {
        fun box(type: String, payload: ByteArray = ByteArray(0)): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply { writeInt(8 + payload.size); write(type.toByteArray(Charsets.ISO_8859_1)); write(payload) }
            return out.toByteArray()
        }

        fun cleanMp4(): ByteArray = box("ftyp", "isom".toByteArray()) + box("mdat", ByteArray(2000)) + box("moov", box("mvhd", ByteArray(100)))
        fun placeMp4(): ByteArray = box("ftyp", "isom".toByteArray()) + box("mdat", ByteArray(2000)) + box("moov", box("udta", byteArrayOf(0xA9.toByte()) + "xyz+38.7-009.1/".toByteArray()))
    }

    // ---- building a case -----------------------------------------------------------------------------------------

    private fun still(n: Int, tr: ReelTransition = ReelTransition.CUT, motion: ReelMotion = ReelMotion.None) = StillItem("r$n", n.toLong(), 3_000L, motion, tr)
    private fun clip(n: Int, tr: ReelTransition = ReelTransition.CUT) = ClipItem("r$n", n.toLong(), 1_000L, 4_000L, 10_000L, tr)
    private fun card(n: Int) = TitleItem("r$n", "June 2025\nLisbon", 0xFF223344.toInt(), 2_500L)

    private fun library(vararg ids: Long, videos: Set<Long> = emptySet()) = ids.associateWith { asset(it, video = it in videos) }

    private class Case(val host: FakeHost, val encoder: FakeEncoder, val sink: FakeSink)

    private fun case(vararg ids: Long, videos: Set<Long> = emptySet()) = Case(FakeHost(library(*ids, videos = videos)), FakeEncoder(), FakeSink(work))

    private fun run(
        c: Case,
        project: ReelProject,
        keep: Boolean = false,
        start: Int = 1080,
        transitions: Boolean = true,
        silent: Boolean = false,
        progress: (Float) -> Unit = {},
    ): ReelOutcome = runBlocking {
        ReelJob.run(ReelRequest(project, keep, start, transitions, silent, ZoneOffset.UTC), c.host, c.encoder, c.sink, progress)
    }

    private fun describe(o: ReelOutcome): String = when (o) {
        is ReelOutcome.Done -> "Done"
        is ReelOutcome.Failed -> "Failed(${o.message})"
        is ReelOutcome.AskSilent -> "AskSilent(${o.message})"
    }

    private fun failed(o: ReelOutcome): String = (o as? ReelOutcome.Failed)?.message ?: error("expected a failure, was ${describe(o)}")
    private fun asks(o: ReelOutcome): String = (o as? ReelOutcome.AskSilent)?.message ?: error("expected a question, was ${describe(o)}")
    private fun done(o: ReelOutcome): ReelOutcome.Done = o as? ReelOutcome.Done ?: error("expected Done, was ${describe(o)}")

    private fun leftover() = work.listFiles().orEmpty().toList()

    // ---- the happy path -----------------------------------------------------------------------------------------

    @Test
    fun `a reel of photos and a clip is made once, on the first rung, and published`() {
        val c = case(1, 2, 3, videos = setOf(2))
        val p = ReelProject(listOf(still(1), clip(2), still(3)), name = "Lisbon")
        val seen = ArrayList<Float>()
        val d = done(run(c, p, progress = { seen += it }))
        assertEquals(1, c.encoder.plans.size)
        assertEquals(ReelAttempt(1080, true), c.encoder.plans.single().attempt)
        assertEquals(1, c.sink.saved.size)
        assertEquals("Lisbon", c.sink.saved.single().stem)
        assertEquals(ReelCopy.SAVED, d.message)
        assertEquals(1920, d.width)
        assertEquals(1080, d.height)
        assertEquals(9_000L, d.durationMs)
        assertFalse(d.steppedDown)
        assertFalse(d.cutsOnly)
        assertFalse(d.hasSound)
        assertTrue(d.notes.isEmpty())
        assertTrue("progress rises", seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, seen.last(), 0f)
        assertTrue(seen.dropLast(1).all { it < 1f })
    }

    @Test
    fun `the encoder is given the pictures it needs and the clip's own address`() {
        val c = case(1, 2, videos = setOf(2))
        done(run(c, ReelProject(listOf(card(9), still(1), clip(2)))))
        val staged = c.encoder.stagedSeen.single()
        assertEquals(setOf("r9", "r1"), staged.pictures.keys)
        assertEquals("content://media/external/video/media/2", staged.clipUris["r2"])
        assertTrue(staged.pictures.getValue("r1").file.name.startsWith("still-"))
        assertEquals("the card is the size of the frame", 1920, staged.pictures.getValue("r9").width)
    }

    @Test
    fun `stills are staged for the project's frame, fit and motion`() {
        val zoom = ReelMotion.ZoomIn()
        val c = case(1)
        done(run(c, ReelProject(listOf(still(1, motion = zoom)), aspect = ReelAspect.TALL)))
        val (id, spec, _) = c.host.staged.single()
        assertEquals(1L, id)
        assertEquals(ReelFrame(1080, 1920), spec.frame)
        assertEquals(ReelFit.COVER, spec.fit)
        assertEquals(zoom, spec.motion)
    }

    @Test
    fun `the project's hand-overs reach the encoder as two layers`() {
        val c = case(1, 2)
        done(run(c, ReelProject(listOf(still(1, ReelTransition.CROSS_FADE), still(2)))))
        assertEquals(2, c.encoder.plans.single().layerCount)
    }

    @Test
    fun `the original shape asks the first picture, and the frame follows`() {
        val c = case(1, 2)
        c.host.shape = 0.75f
        val d = done(run(c, ReelProject(listOf(card(9), still(1), still(2)), aspect = ReelAspect.ORIGINAL)))
        assertEquals(listOf(1L), c.host.shapeAsked)
        assertEquals(1080, d.width)
        assertEquals(1440, d.height)
        assertEquals(ReelFit.CONTAIN, c.encoder.plans.single().fit)
        assertEquals(ReelFit.CONTAIN, c.host.staged.first().second.fit)
    }

    @Test
    fun `a fixed shape never asks for the picture's shape`() {
        val c = case(1)
        done(run(c, ReelProject(listOf(still(1)))))
        assertTrue(c.host.shapeAsked.isEmpty())
    }

    @Test
    fun `a reel of title cards alone needs no photo`() {
        val c = case()
        val d = done(run(c, ReelProject(listOf(card(1), card(2)))))
        assertEquals(5_000L, d.durationMs)
        assertEquals(2, c.host.cards.size)
        assertTrue("no photo, so dated now", c.sink.saved.single().date > photoDate)
    }

    @Test
    fun `the date is the first picture's, and the name falls back to the date`() {
        val c = case(1, 2)
        done(run(c, ReelProject(listOf(card(9), still(2), still(1)))))
        assertEquals(photoDate + 2, c.sink.saved.single().date)
        assertEquals("Reel-2025-06-15", c.sink.saved.single().stem)
    }

    @Test
    fun `working files are gone afterwards`() {
        val c = case(1, 2)
        done(run(c, ReelProject(listOf(still(1), card(3), still(2)))))
        assertTrue("left: ${leftover()}", leftover().isEmpty())
    }

    // ---- refusing ----------------------------------------------------------------------------------------------

    @Test
    fun `a plan the check blocks is refused before anything is read`() {
        val c = case(1)
        assertEquals(ReelCopy.issue(ReelIssue(ReelIssueKind.NO_ITEMS)), failed(run(c, ReelProject())))
        val tooShort = ReelProject(listOf(StillItem("r1", 1L, 100L)))
        assertEquals(ReelCopy.issue(ReelIssue(ReelIssueKind.ITEM_TOO_SHORT, 0)), failed(run(c, tooShort)))
        assertTrue(c.host.staged.isEmpty())
        assertTrue(c.encoder.plans.isEmpty())
    }

    @Test
    fun `a phone with no encoder says so and reads nothing`() {
        val c = case(1)
        c.encoder.can = false
        assertEquals(ReelCopy.NO_ENCODER, failed(run(c, ReelProject(listOf(still(1))))))
        assertTrue(c.host.staged.isEmpty())
    }

    @Test
    fun `an item the library does not show is named by its place in the reel`() {
        val c = case(1)
        assertEquals(ReelCopy.noLongerAvailable(2), failed(run(c, ReelProject(listOf(still(1), still(2))))))
        assertTrue(c.host.staged.isEmpty())
        assertTrue(c.sink.saved.isEmpty())
    }

    @Test
    fun `a protected photo or clip is refused with the protected sentence`() {
        val c = case(1, 2, videos = setOf(2))
        c.host.blocked += 1L
        assertEquals(ReelCopy.PROTECTED, failed(run(c, ReelProject(listOf(still(1), clip(2))))))
        c.host.blocked.clear()
        c.host.blocked += 2L
        assertEquals(ReelCopy.PROTECTED, failed(run(c, ReelProject(listOf(still(1), clip(2))))))
        assertTrue(c.host.staged.isEmpty())
    }

    @Test
    fun `a clip item that names a photo, and a still that names a video, cannot be read`() {
        val c = case(1, 2, videos = setOf(2))
        assertEquals(ReelCopy.cannotRead(1), failed(run(c, ReelProject(listOf(clip(1))))))
        assertEquals(ReelCopy.cannotRead(1), failed(run(c, ReelProject(listOf(still(2))))))
    }

    @Test
    fun `a photo that cannot be read is named, not skipped`() {
        val c = case(1, 2)
        c.host.unreadable += 2L
        assertEquals(ReelCopy.cannotRead(2), failed(run(c, ReelProject(listOf(still(1), still(2))))))
        assertTrue(c.encoder.plans.isEmpty())
        assertTrue(c.sink.saved.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `a photo protected while the others are staged is named as no longer available`() {
        val c = case(1, 2, 3)
        c.host.onStage = { if (it.id.value == 2L) c.host.blocked += 2L }
        assertEquals(ReelCopy.noLongerAvailable(2), failed(run(c, ReelProject(listOf(still(1), still(2), still(3))))))
        assertTrue(c.encoder.plans.isEmpty())
    }

    @Test
    fun `a photo protected after the render stops the publish`() {
        val c = case(1, 2)
        c.encoder.behaviour = { _, _ -> c.host.blocked += 2L }
        assertEquals(ReelCopy.PROTECTED, failed(run(c, ReelProject(listOf(still(1), still(2))))))
        assertTrue(c.sink.saved.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `the sink's own last look is honoured`() {
        val c = case(1)
        var calls = 0
        c.sink.flipBeforeAsk = { calls++; c.host.blocked += 1L }
        assertEquals(ReelCopy.PROTECTED, failed(run(c, ReelProject(listOf(still(1))))))
        assertEquals(1, calls)
        assertTrue(c.sink.saved.isEmpty())
    }

    // ---- the sound ------------------------------------------------------------------------------------------------

    private val track = ReelSoundtrack("content://docs/music/1", trimStartMs = 5_000L, fadeOutMs = 1_500L)

    @Test
    fun `a sound file is laid under the reel`() {
        val c = case(1, 2)
        val d = done(run(c, ReelProject(listOf(still(1), still(2)), soundtrack = track)))
        assertEquals(listOf(track.uri), c.host.probed)
        val audio = c.encoder.plans.single().audio!!
        assertEquals(track.uri, audio.uri)
        assertEquals(5_000_000L, audio.startUs)
        assertEquals(6_000_000L, audio.lengthUs)
        assertTrue(d.hasSound)
    }

    @Test
    fun `a sound file that cannot be used asks, and saves nothing`() {
        val c = case(1)
        c.host.probe = SoundtrackProbe.Unusable
        assertEquals(ReelCopy.SOUND_UNREADABLE, asks(run(c, ReelProject(listOf(still(1)), soundtrack = track))))
        assertTrue(c.host.staged.isEmpty())
        assertTrue(c.encoder.plans.isEmpty())
        assertTrue(c.sink.saved.isEmpty())
    }

    @Test
    fun `a sound file that ends before it should start asks too`() {
        val c = case(1)
        c.host.probe = SoundtrackProbe.Usable(4_000L)
        assertEquals(ReelCopy.SOUND_STARTS_LATE, asks(run(c, ReelProject(listOf(still(1)), soundtrack = track))))
        c.host.probe = SoundtrackProbe.Usable(5_000L)
        assertEquals(ReelCopy.SOUND_STARTS_LATE, asks(run(c, ReelProject(listOf(still(1)), soundtrack = track))))
        assertTrue(c.sink.saved.isEmpty())
    }

    @Test
    fun `a file that does not say its length is not refused for it`() {
        val c = case(1)
        c.host.probe = SoundtrackProbe.Usable(0L)
        done(run(c, ReelProject(listOf(still(1)), soundtrack = track)))
        assertNotNull(c.encoder.plans.single().audio)
    }

    @Test
    fun `when the person agrees, the reel is made with no sound at all`() {
        val c = case(1, 2, videos = setOf(2))
        c.host.probe = SoundtrackProbe.Unusable
        val d = done(run(c, ReelProject(listOf(still(1), clip(2)), soundtrack = track), keep = true, silent = true))
        assertTrue("the file is not even looked at", c.host.probed.isEmpty())
        val plan = c.encoder.plans.single()
        assertNull(plan.audio)
        assertFalse("clip sound is dropped too", plan.keepClipSound)
        assertFalse(d.hasSound)
        assertEquals(listOf(ReelCopy.SAVED_SILENT), d.notes)
    }

    @Test
    fun `agreeing to silence says nothing about sound when there was none to give up`() {
        val c = case(1)
        val d = done(run(c, ReelProject(listOf(still(1))), silent = true))
        assertTrue(d.notes.isEmpty())
    }

    @Test
    fun `the sound of clips is kept only when asked`() {
        val c = case(1, 2, videos = setOf(2))
        done(run(c, ReelProject(listOf(still(1), clip(2)))))
        assertFalse(c.encoder.plans.last().keepClipSound)
        done(run(c, ReelProject(listOf(still(1), clip(2))), keep = true))
        assertTrue(c.encoder.plans.last().keepClipSound)
    }

    @Test
    fun `a sound failure in the export asks, and never drops the sound by itself`() {
        val c = case(1)
        c.encoder.behaviour = { _, _ -> throw ReelEncodeException(ReelFailureKind.AUDIO, "audio") }
        assertEquals(ReelCopy.SOUND_FAILED, asks(run(c, ReelProject(listOf(still(1)), soundtrack = track))))
        assertEquals("no second try without the sound", 1, c.encoder.plans.size)
        assertTrue(c.sink.saved.isEmpty())
    }

    @Test
    fun `a sound failure in a render with no sound is just another failure to step down from`() {
        val c = case(1)
        c.encoder.behaviour = { i, _ -> if (i == 0) throw ReelEncodeException(ReelFailureKind.AUDIO, "audio") }
        done(run(c, ReelProject(listOf(still(1)))))
        assertEquals(2, c.encoder.plans.size)
    }

    // ---- stepping down ------------------------------------------------------------------------------------------

    @Test
    fun `a codec failure steps down the ladder in order`() {
        val c = case(1, 2)
        c.encoder.behaviour = { i, _ -> if (i < 3) throw ReelEncodeException(ReelFailureKind.STEP_DOWN, "codec") }
        val project = ReelProject(listOf(still(1, ReelTransition.CROSS_FADE), still(2)))
        val d = done(run(c, project))
        assertEquals(
            listOf(ReelAttempt(1080, true), ReelAttempt(720, true), ReelAttempt(720, false), ReelAttempt(480, false)),
            c.encoder.plans.map { it.attempt },
        )
        assertTrue(d.steppedDown)
        assertTrue(d.cutsOnly)
        assertEquals(listOf(ReelCopy.SAVED_SMALLER, ReelCopy.SAVED_CUTS), d.notes)
        assertEquals(854, d.width)
        assertEquals("cuts only: the overlap is not taken off", 6_000L, d.durationMs)
    }

    @Test
    fun `a memory failure steps down just the same`() {
        val c = case(1)
        c.encoder.behaviour = { i, _ -> if (i == 0) throw OutOfMemoryError("heap") }
        val d = done(run(c, ReelProject(listOf(still(1)))))
        assertEquals(listOf(1080, 720), c.encoder.plans.map { it.attempt.shortSide })
        assertTrue(d.steppedDown)
        assertFalse("nothing to give up in a project of cuts", d.cutsOnly)
    }

    @Test
    fun `a failure on every rung says what the last one said`() {
        val c = case(1)
        c.encoder.behaviour = { _, _ -> throw ReelEncodeException(ReelFailureKind.STEP_DOWN, "codec") }
        assertEquals(ReelCopy.FAILED, failed(run(c, ReelProject(listOf(still(1))))))
        assertEquals(4, c.encoder.plans.size)
        val m = case(1)
        m.encoder.behaviour = { _, _ -> throw OutOfMemoryError() }
        assertEquals(ReelCopy.OUT_OF_MEMORY, failed(run(m, ReelProject(listOf(still(1))))))
        assertTrue(m.sink.saved.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `a smaller frame is staged again, the same size is not`() {
        val c = case(1)
        c.encoder.behaviour = { i, _ -> if (i < 3) throw ReelEncodeException(ReelFailureKind.STEP_DOWN, "codec") }
        done(run(c, ReelProject(listOf(still(1)))))
        // Rungs: 1080, 720, 720 with cuts, 480 with cuts: three distinct sizes.
        assertEquals(listOf(ReelFrame(1920, 1080), ReelFrame(1280, 720), ReelFrame(854, 480)), c.host.staged.map { it.second.frame })
    }

    @Test
    fun `a request for 720 starts there, and one for plain cuts never lays two tracks`() {
        val c = case(1, 2)
        done(run(c, ReelProject(listOf(still(1, ReelTransition.CROSS_FADE), still(2))), start = 720, transitions = false))
        val plan = c.encoder.plans.single()
        assertEquals(ReelAttempt(720, false), plan.attempt)
        assertEquals(1, plan.layerCount)
        assertFalse("asked for cuts, so no note about giving up fades", done(run(c, ReelProject(listOf(still(1, ReelTransition.CROSS_FADE), still(2))), transitions = false)).cutsOnly)
    }

    @Test
    fun `a source the phone cannot read ends the job at once`() {
        val c = case(1)
        c.encoder.behaviour = { _, _ -> throw ReelEncodeException(ReelFailureKind.SOURCE, "gone") }
        assertEquals(ReelCopy.SOURCE_UNREADABLE, failed(run(c, ReelProject(listOf(still(1))))))
        assertEquals(1, c.encoder.plans.size)
    }

    @Test
    fun `a file that cannot be written ends the job at once`() {
        val c = case(1)
        c.encoder.behaviour = { _, _ -> throw ReelEncodeException(ReelFailureKind.STORAGE, "mux") }
        assertEquals(ReelCopy.SAVE_FAILED, failed(run(c, ReelProject(listOf(still(1))))))
        assertEquals(1, c.encoder.plans.size)
    }

    @Test
    fun `an unexpected exception is a plain failure, not a crash`() {
        val c = case(1)
        c.encoder.behaviour = { _, _ -> throw IllegalStateException("boom") }
        assertEquals(ReelCopy.FAILED, failed(run(c, ReelProject(listOf(still(1))))))
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `an empty output file is a failure`() {
        val c = case(1)
        c.encoder.content = ByteArray(0)
        assertEquals(ReelCopy.FAILED, failed(run(c, ReelProject(listOf(still(1))))))
        assertTrue(c.sink.saved.isEmpty())
    }

    // ---- the finished file and the publish ---------------------------------------------------------------------

    @Test
    fun `a file that still carries a place is not saved`() {
        val c = case(1)
        c.encoder.content = placeMp4()
        assertEquals(ReelCopy.PLACE_LEFT_IN, failed(run(c, ReelProject(listOf(still(1))))))
        assertTrue(c.sink.saved.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `a full phone and a failed save are told apart`() {
        val c = case(1)
        c.sink.failWith = ReelNoSpaceException()
        assertEquals(ReelCopy.NO_SPACE, failed(run(c, ReelProject(listOf(still(1))))))
        c.sink.failWith = IOException("write failed")
        assertEquals(ReelCopy.SAVE_FAILED, failed(run(c, ReelProject(listOf(still(1))))))
        c.sink.failWith = IOException("ENOSPC (No space left on device)")
        assertEquals(ReelCopy.NO_SPACE, failed(run(c, ReelProject(listOf(still(1))))))
    }

    @Test
    fun `a card that cannot be written is a save failure`() {
        val c = case(1)
        c.host.failCardWith = IOException("disk")
        assertEquals(ReelCopy.SAVE_FAILED, failed(run(c, ReelProject(listOf(card(9), still(1))))))
        assertTrue(leftover().isEmpty())
    }

    @Test
    fun `names that are not safe are cleaned`() {
        val c = case(1)
        done(run(c, ReelProject(listOf(still(1)), name = "Summer/trip: day 1.mp4")))
        assertEquals("Summer_trip_ day 1", c.sink.saved.single().stem)
    }

    // ---- cancelling ----------------------------------------------------------------------------------------------

    @Test
    fun `cancelling during the render stops it, publishes nothing and leaves no file`() {
        val c = case(1, 2)
        val started = CompletableDeferred<Unit>()
        c.encoder.behaviour = { _, _ -> started.complete(Unit); awaitCancellation() }
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch {
                ReelJob.run(ReelRequest(ReelProject(listOf(still(1), still(2)))), c.host, c.encoder, c.sink)
            }
            withTimeout(30_000) { started.await() }
            withTimeout(30_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
        assertTrue(c.sink.saved.isEmpty())
        assertTrue("left: ${leftover()}", leftover().isEmpty())
    }

    @Test
    fun `cancelling while the pictures are staged stops before any render`() {
        val c = case(1, 2, 3)
        val started = CompletableDeferred<Unit>()
        c.host.onStage = { if (it.id.value == 2L) { started.complete(Unit); awaitCancellation() } }
        val encoder = SlowEncoder()
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch {
                ReelJob.run(ReelRequest(ReelProject(listOf(still(1), still(2), still(3)))), c.host, encoder, c.sink)
            }
            withTimeout(30_000) { started.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }
        assertTrue(c.sink.saved.isEmpty())
        assertFalse("the render never started", encoder.started)
        assertTrue(leftover().isEmpty())
    }

    // ---- as a job in the runner -------------------------------------------------------------------------------

    private fun launched(c: Case, project: ReelProject, withHandle: (com.fotoxplorr.app.jobs.JobHandle, JobRunner) -> Unit = { _, _ -> }): JobOutcome<*> = runBlocking {
        val runner = JobRunner(CoroutineScope(Dispatchers.Default))
        val outcome = async(Dispatchers.Default) { runner.outcomes.first() }
        // Let the collector subscribe first: the runner's outcomes have no replay.
        kotlinx.coroutines.delay(50)
        val handle = ReelJob.launch(runner, ReelRequest(project), c.host, c.encoder, c.sink)
        withHandle(handle, runner)
        withTimeout(30_000) { outcome.await() }
    }

    @Test
    fun `as a job it is an export with the reel title, and a finished reel is a success`() {
        val c = case(1)
        val o = launched(c, ReelProject(listOf(still(1))))
        assertEquals(ActivityKind.EXPORTING, o.kind)
        assertEquals(ReelCopy.JOB_TITLE, o.title)
        assertTrue(o.result.getOrThrow() is ReelOutcome.Done)
    }

    @Test
    fun `as a job a refusal is a failure that carries the sentence`() {
        val o = launched(case(), ReelProject(listOf(still(1))))
        val e = o.result.exceptionOrNull()
        assertTrue(e is ReelFailure)
        assertEquals(ReelCopy.noLongerAvailable(1), e!!.message)
    }

    @Test
    fun `as a job a sound problem is a question for the screen, not a failure`() {
        val c = case(1)
        c.host.probe = SoundtrackProbe.Unusable
        val e = launched(c, ReelProject(listOf(still(1)), soundtrack = track)).result.exceptionOrNull()
        assertTrue(e is ReelNeedsAnswer)
        assertEquals(ReelCopy.SOUND_UNREADABLE, e!!.message)
    }

    @Test
    fun `as a job it can be cancelled from the runner, and nothing is saved`() {
        val c = case(1)
        val started = CompletableDeferred<Unit>()
        c.encoder.behaviour = { _, _ -> started.complete(Unit); awaitCancellation() }
        val o = launched(c, ReelProject(listOf(still(1)))) { handle, runner ->
            runBlocking {
                withTimeout(30_000) { started.await() }
                val running = runner.jobs.value.single()
                assertEquals(ReelCopy.JOB_TITLE, running.title)
                assertEquals(ActivityKind.EXPORTING, running.kind)
                running.cancel()
            }
        }
        assertTrue(o.result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(c.sink.saved.isEmpty())
        assertTrue(leftover().isEmpty())
    }

    private class SlowEncoder : ReelEncoder {
        @Volatile var started = false

        override fun canEncode() = true

        override suspend fun encode(plan: ReelRenderPlan, staged: ReelStaged, output: File, onProgress: (Float) -> Unit) {
            started = true
            awaitCancellation()
        }
    }
}
