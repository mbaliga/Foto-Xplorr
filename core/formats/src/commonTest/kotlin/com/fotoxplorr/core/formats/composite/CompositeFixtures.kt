package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.raw.ByteArraySource
import com.fotoxplorr.core.formats.raw.jpeg

/**
 * Synthetic composite files built byte by byte from the published layouts. Nothing here came from
 * a real camera: licence-clean, and exactly as wrong as our reading of the documents would be.
 */
object CompositeFixtures {

    fun be32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun be16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun ascii(s: String): ByteArray = s.encodeToByteArray()

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return be32(8L + body.size) + ascii(type) + body
    }

    /** A tiny MP4: an `ftyp` box and an `mdat` of [filler] bytes. */
    fun mp4(filler: Int = 64): ByteArray =
        box("ftyp", ascii("isom"), be32(0x200), ascii("isomiso2mp41")) + box("mdat", ByteArray(filler) { 0x11 })

    /** [jpegBytes] with one extra segment ([marker], [payload]) right after its SOI. */
    fun withSegment(jpegBytes: ByteArray, marker: Int, payload: ByteArray): ByteArray =
        jpegBytes.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), marker.toByte()) + be16(payload.size + 2) + payload +
            jpegBytes.copyOfRange(2, jpegBytes.size)

    fun xmpSegment(packet: String): ByteArray = ascii("http://ns.adobe.com/xap/1.0/\u0000") + ascii(packet)

    fun packet(description: String): String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            description + "</rdf:RDF></x:xmpmeta>"

    /**
     * A Motion Photo 1.0 JPEG: the primary, [padding] bytes, then [video]. The directory is written
     * with Container/Item prefixes [containerPrefix]/[itemPrefix] to prove prefixes are not assumed.
     */
    fun googleMotionPhoto(
        video: ByteArray = mp4(),
        padding: Int = 0,
        containerPrefix: String = "Container",
        itemPrefix: String = "Item",
        lengthOverride: Long? = null,
        timestampUs: Long = 1_234_567,
    ): Pair<ByteArray, Int> {
        val c = containerPrefix
        val i = itemPrefix
        val description =
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"${XmpLite.GCAMERA_NS}\" " +
                "xmlns:$c=\"${XmpLite.CONTAINER_NS}\" xmlns:$i=\"${XmpLite.ITEM_NS}\" " +
                "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" " +
                "GCamera:MotionPhotoPresentationTimestampUs=\"$timestampUs\">" +
                "<$c:Directory><rdf:Seq>" +
                "<rdf:li rdf:parseType=\"Resource\"><$c:Item $i:Mime=\"image/jpeg\" $i:Semantic=\"Primary\" $i:Length=\"0\" $i:Padding=\"$padding\"/></rdf:li>" +
                "<rdf:li rdf:parseType=\"Resource\"><$c:Item $i:Mime=\"video/mp4\" $i:Semantic=\"MotionPhoto\" $i:Length=\"${lengthOverride ?: video.size.toLong()}\"/></rdf:li>" +
                "</rdf:Seq></$c:Directory></rdf:Description>"
        val still = withSegment(jpeg(64, 48, size = 600), 0xE1, xmpSegment(packet(description)))
        return (still + ByteArray(padding) + video) to still.size
    }

    /** The legacy micro-video form: the clip is the last MicroVideoOffset bytes. */
    fun microVideo(video: ByteArray = mp4(), offsetOverride: Long? = null, elementForm: Boolean = false): Pair<ByteArray, Int> {
        val offset = offsetOverride ?: video.size.toLong()
        val description = if (elementForm) {
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"${XmpLite.GCAMERA_NS}\">" +
                "<GCamera:MicroVideo>1</GCamera:MicroVideo><GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>" +
                "<GCamera:MicroVideoOffset>$offset</GCamera:MicroVideoOffset></rdf:Description>"
        } else {
            "<rdf:Description rdf:about=\"\" xmlns:GCamera=\"${XmpLite.GCAMERA_NS}\" " +
                "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"$offset\"/>"
        }
        val still = withSegment(jpeg(64, 48, size = 600), 0xE1, xmpSegment(packet(description)))
        return (still + video) to still.size
    }

    /** A Samsung JPEG with an SEF trailer: one `MotionPhoto_Info` block and one `MotionPhoto_Data` block. */
    fun samsungMotionPhoto(video: ByteArray = mp4(), corruptDistance: Boolean = false): Triple<ByteArray, Long, Int> {
        val still = jpeg(64, 48, size = 600)
        val info = le16(0) + le16(0x0A20) + le32(16) + ascii("MotionPhoto_Info") + ascii("v1")
        val data = le16(0) + le16(0x0A30) + le32(16) + ascii("MotionPhoto_Data") + video
        val infoPos = still.size.toLong()
        val dataPos = infoPos + info.size
        val dirPos = dataPos + data.size
        val entries = le16(0) + le16(0x0A20) + le32(dirPos - infoPos) + le32(info.size.toLong()) +
            le16(0) + le16(0x0A30) + le32(if (corruptDistance) dirPos + 10_000 else dirPos - dataPos) + le32(data.size.toLong())
        val dir = ascii("SEFH") + le32(107) + le32(2) + entries
        val file = still + info + data + dir + le32(dir.size.toLong()) + ascii("SEFT")
        return Triple(file, dataPos + 8 + 16, still.size)
    }

    /** A JPEG whose Exif-area bytes contain an Apple maker note with the given identifiers. */
    fun appleStill(contentId: String?, burstUuid: String?, littleEndian: Boolean = false): ByteArray {
        val tags = buildList {
            if (burstUuid != null) add(0x000B to burstUuid)
            if (contentId != null) add(0x0011 to contentId)
        }
        val ifdStart = 14
        val ifdSize = 2 + tags.size * 12 + 4
        var dataAt = ifdStart + ifdSize
        val u16 = if (littleEndian) ::le16 else ::be16
        val u32 = if (littleEndian) ::le32 else ::be32
        var ifd = u16(tags.size)
        var data = ByteArray(0)
        for ((tag, text) in tags) {
            val bytes = ascii(text) + byteArrayOf(0)
            ifd += u16(tag) + u16(2) + u32(bytes.size.toLong()) + u32(dataAt.toLong())
            data += bytes
            dataAt += bytes.size
        }
        ifd += u32(0)
        val note = ascii("Apple iOS\u0000") + be16(1) + ascii(if (littleEndian) "II" else "MM") + ifd + data
        return withSegment(jpeg(64, 48, size = 600), 0xE1, ascii("Exif\u0000\u0000") + ascii("MM") + ByteArray(6) + note)
    }

    /** A QuickTime movie: ftyp, mdat, then moov/meta with keys + ilst holding the content identifier. */
    fun appleMovie(contentId: String, fullBoxMeta: Boolean = false, otherKeysFirst: Boolean = true): ByteArray {
        val keyNames = buildList {
            if (otherKeysFirst) add("com.apple.quicktime.location.ISO6709")
            add(AppleIdentifierReader.CONTENT_KEY)
        }
        val keyEntries = keyNames.fold(ByteArray(0)) { acc, k -> acc + be32(8L + k.length) + ascii("mdta") + ascii(k) }
        val keys = box("keys", be32(0), be32(keyNames.size.toLong()), keyEntries)
        val index = keyNames.indexOf(AppleIdentifierReader.CONTENT_KEY) + 1
        val dataBox = box("data", be32(1), be32(0), ascii(contentId))
        val other = box("data", be32(1), be32(0), ascii("+1+2/"))
        val items = (if (otherKeysFirst) be32(8L + other.size) + be32(1) + other else ByteArray(0)) +
            be32(8L + dataBox.size) + be32(index.toLong()) + dataBox
        val ilst = box("ilst", items)
        val hdlr = box("hdlr", be32(0), be32(0), ascii("mdta"), ByteArray(12))
        val meta = if (fullBoxMeta) box("meta", be32(0), hdlr, keys, ilst) else box("meta", hdlr, keys, ilst)
        val moov = box("moov", box("mvhd", ByteArray(100)), meta)
        return box("ftyp", ascii("qt  "), be32(0), ascii("qt  ")) + box("mdat", ByteArray(300) { 7 }) + moov
    }

    /**
     * A two-image MPO: the first JPEG carries the APP2 MPF index; the second is appended. [types]
     * are the MP type codes written for the two entries.
     */
    fun mpo(types: IntArray = intArrayOf(MpoReader.TYPE_DISPARITY, MpoReader.TYPE_DISPARITY), littleEndian: Boolean = true, secondOffsetDelta: Long = 0): ByteArray {
        val second = jpeg(80, 60, size = 700)
        val firstBody = jpeg(80, 60, size = 700)
        val u16 = if (littleEndian) ::le16 else ::be16
        val u32 = if (littleEndian) ::le32 else ::be32
        // MP header: byte order, 42, offset of the index IFD (8). IFD: 3 entries.
        val entryCount = 3
        val ifdSize = 2 + entryCount * 12 + 4
        val tableAt = 8 + ifdSize
        val app2Len = 4 /* MPF\0 */ + tableAt + 32
        val headerPos = 2 /* SOI */ + 4 /* marker+len */ + 4 /* MPF\0 */
        val firstSize = firstBody.size + 4 + app2Len
        val secondRel = firstSize - headerPos + secondOffsetDelta
        val header = ascii(if (littleEndian) "II" else "MM") + u16(42) + u32(8)
        val ifd = u16(entryCount) +
            u16(0xB000) + u16(7) + u32(4) + ascii("0100") +
            u16(0xB001) + u16(4) + u32(1) + u32(2) +
            u16(0xB002) + u16(7) + u32(32) + u32(tableAt.toLong()) +
            u32(0)
        val table = u32(types[0].toLong() or 0x20000000L) + u32(firstSize.toLong()) + u32(0) + u16(0) + u16(0) +
            u32(types[1].toLong()) + u32(second.size.toLong()) + u32(secondRel) + u16(0) + u16(0)
        val first = withSegment(firstBody, 0xE2, ascii("MPF\u0000") + header + ifd + table)
        check(first.size == firstSize)
        return first + second
    }

    /** A HEIF whose meta holds a `ster` entity group naming items [left] and [right]. */
    fun spatialHeif(left: Long = 1, right: Long = 2, withGroup: Boolean = true): ByteArray {
        val hdlr = box("hdlr", be32(0), be32(0), ascii("pict"), ByteArray(12))
        val grpl = box("grpl", box("ster", be32(0), be32(100), be32(2), be32(left), be32(right)))
        val meta = if (withGroup) box("meta", be32(0), hdlr, grpl) else box("meta", be32(0), hdlr)
        return box("ftyp", ascii("heic"), be32(0), ascii("mif1heic")) + meta + box("mdat", ByteArray(64))
    }

    fun source(bytes: ByteArray) = ByteArraySource(bytes)
}
