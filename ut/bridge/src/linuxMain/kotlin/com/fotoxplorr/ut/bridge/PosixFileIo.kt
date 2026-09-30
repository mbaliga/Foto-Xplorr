@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fotoxplorr.ut.bridge

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

/**
 * Whole-file read/write over `platform.posix`, not `okio` -- this module is Linux-only native by
 * definition (ADR-013 point 2), so the POSIX C library is the dependency-free, exactly-right tool
 * here, rather than pulling in a new external library for one small file-I/O need. Whole-file, not
 * streamed: matches [com.fotoxplorr.core.metadata.MetadataStripper]'s own documented assumption
 * ("the images this pipeline handles... are small enough that this is not a meaningful memory
 * concern").
 */

internal fun readWholeFile(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < 0) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        if (size == 0L) return ByteArray(0)
        val buffer = ByteArray(size.toInt())
        val read = buffer.usePinned { pinned -> fread(pinned.addressOf(0), 1u, size.convert(), file) }
        if (read.toLong() != size) return null
        return buffer
    } finally {
        fclose(file)
    }
}

internal fun writeWholeFile(path: String, bytes: ByteArray): Boolean {
    val file = fopen(path, "wb") ?: return false
    try {
        if (bytes.isEmpty()) return true
        val written = bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u, bytes.size.convert(), file) }
        return written.toLong() == bytes.size.toLong()
    } finally {
        fclose(file)
    }
}
