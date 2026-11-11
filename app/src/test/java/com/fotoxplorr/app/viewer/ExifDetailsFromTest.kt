package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.core.metadata.XmpPacket
import java.io.File
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

/**
 * The facts [exifDetailsFrom] adds for the detail sections, read back from real file-backed JPEGs
 * (the reader itself cannot open a descriptor on a fabricated URI -- TRAPS 67 -- so the mapping is
 * tested from the [ExifInterface] it is handed). A tag a file does not carry, or carries
 * malformed, stays null and never crashes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifDetailsFromTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun jpegWith(name: String, configure: ExifInterface.() -> Unit): ExifInterface {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            configure()
            saveAttributes()
        }
        return ExifInterface(file.absolutePath)
    }

    @Test
    fun `metering, program, white balance and software are read as raw codes and text`() {
        val details = exifDetailsFrom(
            jpegWith("capture.jpg") {
                setAttribute(ExifInterface.TAG_METERING_MODE, "5")
                setAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM, "3")
                setAttribute(ExifInterface.TAG_WHITE_BALANCE, "1")
                setAttribute(ExifInterface.TAG_SOFTWARE, "  Pixel Camera 9.4  ")
            },
        )
        assertEquals(5, details.meteringMode)
        assertEquals(3, details.exposureProgram)
        assertEquals(1, details.whiteBalance)
        assertEquals("Pixel Camera 9.4", details.software)
        assertEquals("Multi-segment", DetailFormatting.meteringText(details.meteringMode))
        assertEquals("Aperture priority", DetailFormatting.exposureProgramText(details.exposureProgram))
        assertEquals("Manual", DetailFormatting.whiteBalanceText(details.whiteBalance))
    }

    @Test
    fun `a bare file has none of the new facts`() {
        val details = exifDetailsFrom(jpegWith("bare.jpg") {})
        assertNull(details.meteringMode)
        assertNull(details.exposureProgram)
        assertNull(details.whiteBalance)
        assertNull(details.software)
        assertNull(details.dateTimeOriginal)
        assertNull(details.offsetTimeOriginal)
        assertNull(details.gpsAltitudeMeters)
        assertNull(details.gpsBearingDegrees)
        assertNull(details.gpsSpeedKmh)
        assertFalse(details.isMotionPhoto)
        assertFalse(details.hasDepthMap)
        assertFalse(details.isPanorama)
    }

    @Test
    fun `the photo's own clock and its offset come through as stored`() {
        val details = exifDetailsFrom(
            jpegWith("time.jpg") {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:01 22:33:00")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+05:30")
            },
        )
        assertEquals("2026:08:01 22:33:00", details.dateTimeOriginal)
        assertEquals("+05:30", details.offsetTimeOriginal)
        assertEquals(19_800, DetailFormatting.exifOffset(details.offsetTimeOriginal)!!.totalSeconds)
    }

    @Test
    fun `altitude above and below sea level, bearing and speed are read from the fix`() {
        val above = exifDetailsFrom(
            jpegWith("above.jpg") {
                setLatLong(46.5, 8.0)
                setAltitude(1234.0)
                setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "45/1")
                setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, "M")
                setAttribute(ExifInterface.TAG_GPS_SPEED, "30/1")
                setAttribute(ExifInterface.TAG_GPS_SPEED_REF, "K")
            },
        )
        assertEquals(1234.0, above.gpsAltitudeMeters!!, 0.5)
        assertEquals(45.0, above.gpsBearingDegrees!!, 1e-6)
        assertTrue(above.gpsBearingMagnetic)
        assertEquals(30.0, above.gpsSpeedKmh!!, 1e-6)

        val below = exifDetailsFrom(jpegWith("below.jpg") { setLatLong(31.5, 35.5); setAltitude(-430.0) })
        assertEquals(-430.0, below.gpsAltitudeMeters!!, 0.5)
        assertEquals("430 m below sea level", DetailFormatting.altitudeText(below.gpsAltitudeMeters))
        assertFalse(below.gpsBearingMagnetic)
    }

    @Test
    fun `a malformed value stays null and does not crash`() {
        val details = exifDetailsFrom(
            jpegWith("junk.jpg") {
                setAttribute(ExifInterface.TAG_METERING_MODE, "banana")
                setAttribute(ExifInterface.TAG_GPS_SPEED, "fast")
                setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "north-ish")
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "banana")
            },
        )
        assertNull(details.meteringMode)
        assertNull(details.gpsSpeedKmh)
        assertNull(details.gpsBearingDegrees)
        // The raw offset text is kept; it is the formatter that refuses to call it a zone.
        assertNull(DetailFormatting.exifOffset(details.offsetTimeOriginal))
    }

    @Test
    fun `the Motion Photo, depth and panorama flags come from the XMP`() {
        fun packet(vararg descriptions: String) =
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"${XmpPacket.RDF_NS}\">" +
                descriptions.joinToString("") + "</rdf:RDF></x:xmpmeta>"

        val motion = exifDetailsFrom(
            jpegWith("motion.jpg") {
                setAttribute(
                    ExifInterface.TAG_XMP,
                    packet("<rdf:Description rdf:about=\"\" xmlns:GCamera=\"${XmpPacket.GCAMERA_NS}\" GCamera:MotionPhoto=\"1\"/>"),
                )
            },
        )
        assertTrue(motion.isMotionPhoto)
        assertFalse(motion.isPanorama)

        val all = exifDetailsFrom(
            jpegWith("all.jpg") {
                setAttribute(
                    ExifInterface.TAG_XMP,
                    packet(
                        "<rdf:Description rdf:about=\"\" xmlns:GDepth=\"${XmpPacket.GDEPTH_NS}\" GDepth:Format=\"RangeInverse\"" +
                            " xmlns:GPano=\"${XmpPacket.GPANO_NS}\" GPano:ProjectionType=\"equirectangular\"/>",
                    ),
                )
            },
        )
        assertTrue(all.hasDepthMap)
        assertTrue(all.isPanorama)
        assertFalse(all.isMotionPhoto)
    }

    @Test
    fun `the XMP keywords a file carries are in the professional metadata`() {
        // The keywords are read once, into professionalMetadata; the Library section reads them there.
        val details = exifDetailsFrom(
            jpegWith("keywords.jpg") {
                setAttribute(
                    ExifInterface.TAG_XMP,
                    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"${XmpPacket.RDF_NS}\">" +
                        "<rdf:Description rdf:about=\"\" xmlns:dc=\"${XmpPacket.DC_NS}\"><dc:subject><rdf:Bag>" +
                        "<rdf:li>harbour</rdf:li><rdf:li>dusk</rdf:li></rdf:Bag></dc:subject></rdf:Description>" +
                        "</rdf:RDF></x:xmpmeta>",
                )
            },
        )
        assertNotNull(details.professionalMetadata)
        assertEquals(listOf("harbour", "dusk"), details.professionalMetadata.keywords)
    }

    @Test
    fun `GPS speed is normalised to kilometres per hour from whichever unit the file names`() {
        assertEquals(50.0, gpsSpeedKmh(50.0, "K")!!, 1e-9)
        assertEquals(50.0, gpsSpeedKmh(50.0, null)!!, 1e-9)
        assertEquals(80.4672, gpsSpeedKmh(50.0, "M")!!, 1e-4)
        assertEquals(18.52, gpsSpeedKmh(10.0, "N")!!, 1e-9)
        assertNull("an unknown unit is not guessed at", gpsSpeedKmh(10.0, "X"))
        assertNull(gpsSpeedKmh(-1.0, "K"))
        assertNull(gpsSpeedKmh(Double.NaN, "K"))
    }

    @Test
    fun `implausible altitudes and bearings are dropped`() {
        assertTrue(plausibleAltitude(0.0))
        assertTrue(plausibleAltitude(-430.0))
        assertFalse(plausibleAltitude(Double.NaN))
        assertFalse(plausibleAltitude(1.0e9))
        assertTrue(plausibleBearing(0.0))
        assertTrue(plausibleBearing(359.99))
        assertFalse(plausibleBearing(-1.0))
        assertFalse(plausibleBearing(720.0))
        assertFalse(plausibleBearing(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `no serial number or owner name is ever read into the model`() {
        val names = ImageExifDetails::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(names.none { "serial" in it || "owner" in it || "bodyserial" in it })
    }
}
