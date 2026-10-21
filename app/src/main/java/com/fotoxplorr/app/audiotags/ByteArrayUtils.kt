package com.fotoxplorr.app.audiotags

import java.io.ByteArrayOutputStream

/** First index at or after [from] holding [value], or -1 — `ByteArray` has no built-in `indexOf`
 *  taking a start offset, and every codec in this package needs one to find string terminators. */
internal fun ByteArray.indexOf(value: Byte, from: Int): Int {
    for (i in from until size) if (this[i] == value) return i
    return -1
}

/** First index at or after [from], aligned to an even offset from [from], holding two consecutive
 *  zero bytes — the UTF-16 string terminator. Alignment matters: an odd-aligned `0x00 0x00` can
 *  occur inside a genuine UTF-16 code unit and is not a terminator. */
internal fun ByteArray.indexOfUtf16Terminator(from: Int): Int {
    var i = from
    while (i + 1 < size) {
        if (this[i] == 0.toByte() && this[i + 1] == 0.toByte()) return i
        i += 2
    }
    return -1
}

/** Big-endian unsigned 16-bit read. */
internal fun ByteArray.beUShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

/** Big-endian unsigned 24-bit read — ID3v2.2 frame sizes and MP4 box header partials use this
 *  width. */
internal fun ByteArray.beUInt24(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset + 2].toInt() and 0xFF)

/** Big-endian 32-bit read, as a plain (signed) Int — every size field this package reads fits
 *  comfortably under 2^31. */
internal fun ByteArray.beInt32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/** Big-endian 64-bit read — MP4 `co64` chunk offsets and the rare 64-bit extended atom size. */
internal fun ByteArray.beLong64(offset: Int): Long {
    var result = 0L
    for (i in 0 until 8) result = (result shl 8) or (this[offset + i].toLong() and 0xFF)
    return result
}

internal fun ByteArrayOutputStream.writeBeInt32(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun ByteArrayOutputStream.writeBeInt24(value: Int) {
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun ByteArrayOutputStream.writeBeInt16(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun ByteArrayOutputStream.writeBeLong64(value: Long) {
    for (shift in 56 downTo 0 step 8) write(((value ushr shift) and 0xFF).toInt())
}

/** Little-endian 32-bit read — FLAC's Vorbis comment block is the one format here that is
 *  little-endian; everything else (ID3v2, MP4) is big-endian. */
internal fun ByteArray.leInt32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArrayOutputStream.writeLeInt32(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

/** Sniffs an image's MIME type from its own magic bytes — [com.fotoxplorr.app.audiotags.AudioTags]
 *  carries cover art as plain bytes with no separate MIME field, so every writer that needs one
 *  (ID3 `APIC`, MP4 `covr`'s data-atom flags, FLAC `PICTURE`) derives it the same way rather than
 *  three slightly different guesses. Defaults to JPEG, the overwhelmingly common embedded-cover
 *  format, when the bytes match neither known signature — a wrong guess here mislabels the MIME
 *  string but never corrupts the image bytes themselves, which are stored and returned verbatim
 *  regardless.
 */
internal fun sniffImageMime(bytes: ByteArray): String = when {
    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() &&
        bytes[3] == 'G'.code.toByte()
    -> "image/png"
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    -> "image/jpeg"
    else -> "image/jpeg"
}
