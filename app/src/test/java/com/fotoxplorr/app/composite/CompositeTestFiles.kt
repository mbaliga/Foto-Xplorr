package com.fotoxplorr.app.composite

import android.graphics.Bitmap
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.core.model.MediaId
import java.io.ByteArrayOutputStream

/** Synthetic composite files for the app-side tests, built from real Bitmap.compress JPEGs. */
internal object CompositeTestFiles {
    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun jpeg(color: Int, w: Int = 64, h: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
    }

    fun withSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), marker.toByte()) + be16(payload.size + 2) + payload +
            jpeg.copyOfRange(2, jpeg.size)

    fun mp4(filler: Int = 64): ByteArray {
        val ftypBody = "isom".toByteArray() + be32(0x200) + "isomiso2mp41".toByteArray()
        return be32(8L + ftypBody.size) + "ftyp".toByteArray() + ftypBody + be32(8L + filler) + "mdat".toByteArray() + ByteArray(filler) { 0x11 }
    }

    /** A legacy micro-video motion photo: still, then the clip; returns the file and the clip. */
    fun microVideo(color: Int = 0xFF3366CC.toInt()): Pair<ByteArray, ByteArray> {
        val clip = mp4(500)
        val packet = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
            "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"${clip.size}\"/>" +
            "</rdf:RDF></x:xmpmeta>"
        val still = withSegment(jpeg(color), 0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + packet.toByteArray())
        return (still + clip) to clip
    }

    /** A two-eye MPO from two real JPEGs, little-endian MP index, both typed disparity. */
    fun mpo(left: ByteArray, right: ByteArray): ByteArray {
        val ifdSize = 2 + 3 * 12 + 4
        val tableAt = 8 + ifdSize
        val app2Len = 4 + tableAt + 32
        val headerPos = 2 + 4 + 4
        val firstSize = left.size + 4 + app2Len
        val header = "II".toByteArray() + le16(42) + le32(8)
        val ifd = le16(3) +
            le16(0xB000) + le16(7) + le32(4) + "0100".toByteArray() +
            le16(0xB001) + le16(4) + le32(1) + le32(2) +
            le16(0xB002) + le16(7) + le32(32) + le32(tableAt.toLong()) +
            le32(0)
        val table = le32(0x020002L or 0x20000000L) + le32(firstSize.toLong()) + le32(0) + le16(0) + le16(0) +
            le32(0x020002L) + le32(right.size.toLong()) + le32((firstSize - headerPos).toLong()) + le16(0) + le16(0)
        val first = withSegment(left, 0xE2, "MPF\u0000".toByteArray() + header + ifd + table)
        check(first.size == firstSize)
        return first + right
    }

    fun asset(
        id: Long,
        name: String,
        taken: Long = 0L,
        mime: String = if (name.endsWith(".MOV", true)) "video/quicktime" else "image/jpeg",
        bucket: Long = 1L,
        uri: String = "content://media/external/images/media/$id",
    ) = MediaAsset(
        id = MediaId(id),
        contentUriString = uri,
        displayName = name,
        mimeType = mime,
        bucketName = "Camera",
        bucketId = bucket,
        dateTakenMillis = taken,
        dateModifiedSeconds = 1L,
        width = 64,
        height = 48,
        sizeBytes = 1000L,
        relativePath = "DCIM/Camera/",
        isFavorite = false,
        isTrashed = false,
    )
}
