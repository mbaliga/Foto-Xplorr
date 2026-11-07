package com.fotoxplorr.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Motion Photo, depth-map and panorama flags, in both shapes a property can take. */
class XmpPacketVendorFlagsTest {

    private fun packet(description: String): XmpPacket = XmpPacket.parse(
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"${XmpPacket.RDF_NS}\">" +
            description + "</rdf:RDF></x:xmpmeta>",
    )!!

    private val gcamera = "xmlns:GCamera=\"${XmpPacket.GCAMERA_NS}\""
    private val gdepth = "xmlns:GDepth=\"${XmpPacket.GDEPTH_NS}\""
    private val gpano = "xmlns:GPano=\"${XmpPacket.GPANO_NS}\""

    @Test
    fun `the namespaces are Google's published ones`() {
        assertEquals("http://ns.google.com/photos/1.0/camera/", XmpPacket.GCAMERA_NS)
        assertEquals("http://ns.google.com/photos/1.0/depthmap/", XmpPacket.GDEPTH_NS)
        assertEquals("http://ns.google.com/photos/1.0/panorama/", XmpPacket.GPANO_NS)
    }

    @Test
    fun `a Motion Photo is found in the attribute form`() {
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gcamera GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"/>").isMotionPhoto())
    }

    @Test
    fun `a Motion Photo is found in the element form`() {
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gcamera><GCamera:MotionPhoto>1</GCamera:MotionPhoto></rdf:Description>").isMotionPhoto())
    }

    @Test
    fun `the older micro video flag counts`() {
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gcamera GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"4000\"/>").isMotionPhoto())
    }

    @Test
    fun `a flag written as 0 is a no`() {
        assertFalse(packet("<rdf:Description rdf:about=\"\" $gcamera GCamera:MotionPhoto=\"0\"/>").isMotionPhoto())
    }

    @Test
    fun `a packet that never mentions the flags is not a motion photo, depth map or panorama`() {
        val plain = XmpPacket.empty()
        plain.setLangAlt(XmpPacket.DC_NS, "dc", "description", "just a caption")
        assertFalse(plain.isMotionPhoto())
        assertFalse(plain.hasDepthMap())
        assertFalse(plain.isPanorama())
    }

    @Test
    fun `the camera namespace alone is not a motion photo`() {
        assertFalse(packet("<rdf:Description rdf:about=\"\" $gcamera GCamera:SpecialTypeID=\"HDRP_MAKER_NOTE\"/>").isMotionPhoto())
    }

    @Test
    fun `a depth map is found in either shape`() {
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gdepth GDepth:Format=\"RangeInverse\"/>").hasDepthMap())
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gdepth><GDepth:Format>RangeLinear</GDepth:Format></rdf:Description>").hasDepthMap())
    }

    @Test
    fun `a declared but unused depth namespace is not a depth map`() {
        assertFalse(packet("<rdf:Description rdf:about=\"\" $gdepth/>").hasDepthMap())
    }

    @Test
    fun `a panorama is found by its projection or its full width`() {
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gpano GPano:ProjectionType=\"equirectangular\"/>").isPanorama())
        assertTrue(packet("<rdf:Description rdf:about=\"\" $gpano><GPano:FullPanoWidthPixels>8192</GPano:FullPanoWidthPixels></rdf:Description>").isPanorama())
    }

    @Test
    fun `a panorama namespace with neither defining property is not a panorama`() {
        assertFalse(packet("<rdf:Description rdf:about=\"\" $gpano GPano:UsePanoramaViewer=\"True\"/>").isPanorama())
    }
}
