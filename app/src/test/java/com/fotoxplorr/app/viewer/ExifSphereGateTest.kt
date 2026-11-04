package com.fotoxplorr.app.viewer

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.fotoxplorr.core.metadata.XmpPacket
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "View in 360" is offered only for what the 360 view can open: an equirectangular GPano block, not
 * any panorama flag (a cylindrical panorama used to get the button and always end in the failure line).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ExifSphereGateTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun withGpano(name: String, attributes: String): ImageExifDetails {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_XMP,
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"${XmpPacket.RDF_NS}\">" +
                    "<rdf:Description rdf:about=\"\" xmlns:GPano=\"${XmpPacket.GPANO_NS}\" $attributes/>" +
                    "</rdf:RDF></x:xmpmeta>",
            )
            saveAttributes()
        }
        return exifDetailsFrom(ExifInterface(file.absolutePath))
    }

    @Test
    fun `an equirectangular sphere is offered`() {
        val d = withGpano("sphere.jpg", "GPano:ProjectionType=\"equirectangular\"")
        assertTrue(d.isPanorama)
        assertTrue(d.isSphere)
    }

    @Test
    fun `a cylindrical panorama is a panorama but not a sphere`() {
        val d = withGpano("cylinder.jpg", "GPano:ProjectionType=\"cylindrical\"")
        assertTrue(d.isPanorama)
        assertFalse(d.isSphere)
    }

    @Test
    fun `a bare FullPano width is a panorama but not a sphere`() {
        val d = withGpano("bare.jpg", "GPano:FullPanoWidthPixels=\"8192\"")
        assertTrue(d.isPanorama)
        assertFalse(d.isSphere)
    }
}
