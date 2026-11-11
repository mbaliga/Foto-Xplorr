package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.composite.CompositeFixtures.googleMotionPhoto
import com.fotoxplorr.core.formats.composite.CompositeFixtures.microVideo
import com.fotoxplorr.core.formats.composite.CompositeFixtures.mp4
import com.fotoxplorr.core.formats.composite.CompositeFixtures.samsungMotionPhoto
import com.fotoxplorr.core.formats.composite.CompositeFixtures.source
import com.fotoxplorr.core.formats.raw.RecordingSource
import com.fotoxplorr.core.formats.raw.jpeg
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionPhotoLocatorTest {

    @Test
    fun `a Motion Photo container directory gives the clip at the end of the file`() {
        val video = mp4(200)
        val (file, stillEnd) = googleMotionPhoto(video)
        val found = MotionPhotoLocator.locate(source(file))!!
        assertEquals(MotionPhotoLayout.GOOGLE_CONTAINER, found.layout)
        assertEquals(EmbeddedRange(stillEnd.toLong(), video.size.toLong()), found.video)
        assertEquals(EmbeddedRange(0, stillEnd.toLong()), found.still)
        assertEquals(1_234_567L, found.presentationUs)
    }

    @Test
    fun `the primary padding sits between the still and the clip`() {
        val video = mp4(90)
        val (file, stillEnd) = googleMotionPhoto(video, padding = 40)
        val found = MotionPhotoLocator.locate(source(file))!!
        assertEquals(stillEnd.toLong() + 40, found.video.offset)
        assertEquals(stillEnd.toLong(), found.still.length)
    }

    @Test
    fun `any prefix bound to the container namespaces is read`() {
        val (file, stillEnd) = googleMotionPhoto(containerPrefix = "GContainer", itemPrefix = "GItem")
        assertEquals(stillEnd.toLong(), MotionPhotoLocator.locate(source(file))!!.video.offset)
    }

    @Test
    fun `an unknown presentation timestamp is null`() {
        val (file, _) = googleMotionPhoto(timestampUs = -1)
        assertNull(MotionPhotoLocator.locate(source(file))!!.presentationUs)
    }

    @Test
    fun `a directory length longer than the file is refused`() {
        val (file, _) = googleMotionPhoto(lengthOverride = 10_000_000)
        assertNull(MotionPhotoLocator.locate(source(file)))
    }

    @Test
    fun `a directory length that misses the ftyp box is refused`() {
        val video = mp4(200)
        val (file, _) = googleMotionPhoto(video, lengthOverride = video.size - 3L)
        assertNull(MotionPhotoLocator.locate(source(file)))
    }

    @Test
    fun `the legacy micro video offset counts back from the end`() {
        val video = mp4(120)
        val (file, stillEnd) = microVideo(video)
        val found = MotionPhotoLocator.locate(source(file))!!
        assertEquals(MotionPhotoLayout.GOOGLE_MICRO_VIDEO, found.layout)
        assertEquals(EmbeddedRange(stillEnd.toLong(), video.size.toLong()), found.video)
    }

    @Test
    fun `the micro video flags are read in the element form too`() {
        val (file, stillEnd) = microVideo(elementForm = true)
        assertEquals(stillEnd.toLong(), MotionPhotoLocator.locate(source(file))!!.video.offset)
    }

    @Test
    fun `a micro video offset as large as the file is refused`() {
        val (file, _) = microVideo(offsetOverride = 1L shl 40)
        assertNull(MotionPhotoLocator.locate(source(file)))
    }

    @Test
    fun `the Samsung trailer gives the MotionPhoto_Data payload`() {
        val video = mp4(150)
        val (file, payloadAt, stillEnd) = samsungMotionPhoto(video)
        val found = MotionPhotoLocator.locate(source(file))!!
        assertEquals(MotionPhotoLayout.SAMSUNG_SEF, found.layout)
        assertEquals(EmbeddedRange(payloadAt, video.size.toLong()), found.video)
        assertEquals(stillEnd.toLong(), found.still.length)
    }

    @Test
    fun `a Samsung entry pointing before the file start is skipped`() {
        val (file, _, _) = samsungMotionPhoto(corruptDistance = true)
        assertNull(MotionPhotoLocator.locate(source(file)))
    }

    @Test
    fun `a plain JPEG is not a motion photo`() {
        assertNull(MotionPhotoLocator.locate(source(jpeg(64, 48, size = 800))))
        assertNull(MotionPhotoLocator.locate(source(ByteArray(0))))
        assertNull(MotionPhotoLocator.locate(source(ByteArray(7) { 1 })))
    }

    @Test
    fun `no read ever leaves the file and every truncation is safe`() {
        val (file, _) = googleMotionPhoto(mp4(300))
        val recording = RecordingSource(source(file))
        MotionPhotoLocator.locate(recording)
        assertEquals(0, recording.violations)
        for (cut in listOf(1, 2, 10, 100, file.size / 2, file.size - 1)) {
            MotionPhotoLocator.locate(source(file.copyOf(cut)))
        }
    }

    @Test
    fun `random bytes after a motion photo XMP never throw`() {
        val random = Random(42)
        val (file, _) = samsungMotionPhoto()
        repeat(200) {
            val mutated = file.copyOf()
            repeat(8) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            MotionPhotoLocator.locate(source(mutated))
        }
        assertTrue(true)
    }
}

class MotionPhotoStillTest {
    @Test
    fun `disarming turns both flag forms off without changing the length`() {
        val text = "<a GCamera:MotionPhoto=\"1\" GCamera:MicroVideo='1'/><GCamera:MotionPhoto>1</GCamera:MotionPhoto>"
        val bytes = text.encodeToByteArray()
        assertEquals(3, MotionPhotoStill.disarm(bytes))
        assertEquals(text.length, bytes.size)
        assertEquals(text.replace("\"1\"", "\"0\"").replace("'1'", "'0'").replace(">1<", ">0<"), bytes.decodeToString())
    }

    @Test
    fun `a disarmed still is no longer a motion photo`() {
        val (file, stillEnd) = googleMotionPhoto()
        val still = file.copyOf(stillEnd)
        assertEquals(1, MotionPhotoStill.disarm(still))
        assertNull(MotionPhotoLocator.locate(source(still)))
    }
}
