package com.fotoxplorr.app.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.io.FileInputStream
import java.io.InputStream

/**
 * Reading a whole file for a simple-format decode, ONCE: the length is asked first (no byte is read for a file
 * over the cap), then the bytes go straight into one array of exactly that size. Nothing is left buffered in
 * Coil's source, so a decline costs the decoders after it nothing.
 */
internal object BoundedBytes {

    /** Exactly [length] bytes of [stream] in one array; null when [length] is unknown (< 0), over [cap], or the
     * stream ends first. */
    fun read(stream: InputStream, length: Long, cap: Long): ByteArray? {
        if (length < 0 || length > cap || length > Int.MAX_VALUE) return null
        val out = ByteArray(length.toInt())
        var at = 0
        while (at < out.size) {
            val n = stream.read(out, at, out.size - at)
            if (n < 0) return null
            at += n
        }
        return out
    }

    /**
     * The bytes of [uri] under [cap], or null (unreadable, a pipe with no length, too large). [onLength] hears the
     * file's length once it is known, before any byte is read, so a caller can tell "too large" from "unreadable".
     */
    fun read(context: Context, uri: Uri, cap: Long, onLength: (Long) -> Unit = {}): ByteArray? = runCatching {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return null
        afd.use {
            val length = if (it.length != AssetFileDescriptor.UNKNOWN_LENGTH) it.length else {
                runCatching { FileInputStream(it.fileDescriptor).channel.size() - it.startOffset }.getOrDefault(-1L)
            }
            onLength(length)
            if (length < 0 || length > cap) return null
            it.createInputStream().use { stream -> read(stream, length, cap) }
        }
    }.getOrNull()
}

/**
 * Whether a TIFF header says the file is a RAW (an extension-less DNG, NEF or ARW sniffs as TIFF): IFD0 carries
 * DNGVersion, or is marked reduced-resolution (NewSubfileType bit 0), which is how a RAW stores its thumbnail in
 * front. The baseline TIFF decoder refuses such an IFD0 anyway, so the RAW preview is tried first and the whole
 * file is never read for a refusal. [header] is the start of the file; an IFD0 past it answers false.
 */
internal object TiffRawHint {
    const val HEADER_BYTES = 64 * 1024

    private const val NEW_SUBFILE_TYPE = 254
    private const val DNG_VERSION = 0xC612

    fun looksLikeRaw(header: ByteArray): Boolean {
        if (header.size < 8) return false
        val le = when {
            header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> true
            header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> false
            else -> return false
        }
        fun u16(at: Int): Int = if (le) (header[at].u8() or (header[at + 1].u8() shl 8)) else ((header[at].u8() shl 8) or header[at + 1].u8())
        fun u32(at: Int): Long = if (le) {
            u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        if (u16(2) != 42) return false
        val ifd = u32(4)
        if (ifd < 8 || ifd + 2 > header.size) return false
        val at = ifd.toInt()
        val count = u16(at)
        for (i in 0 until count) {
            val e = at + 2 + i * 12
            if (e + 12 > header.size) return false
            val tag = u16(e)
            if (tag == DNG_VERSION) return true
            if (tag == NEW_SUBFILE_TYPE) {
                val value = when (u16(e + 2)) {
                    3 -> u16(e + 8).toLong()
                    4 -> u32(e + 8)
                    else -> 0L
                }
                if (value and 1L != 0L) return true
            }
        }
        return false
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
