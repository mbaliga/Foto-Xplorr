package com.fotoxplorr.app.backup

import java.io.InputStream
import java.io.OutputStream

/**
 * The one seam between the backup format and a cipher implementation (ADR-014). A chunked,
 * authenticated stream: the plaintext is cut into segments, each sealed on its own, the last one
 * flagged final, so truncation, appended data, reordered segments and segments from another
 * stream all fail authentication. Today's implementation is [TinkChunkedAead]; a plain-JCA one can
 * replace it without touching [BackupHeader], [BackupKeys] or [BackupStreams].
 *
 * Contract for every implementation:
 * - [key] is the 32-byte master key; the implementation derives a fresh per-stream key from it
 *   and never reuses a nonce.
 * - [associatedData] (the encoded [BackupHeader]) is authenticated: any change makes
 *   decryption fail.
 * - No plaintext of a segment is released before that segment's tag verifies, so memory use is
 *   bounded by [segmentSize].
 * - Failures surface as [java.io.IOException] from the returned streams; a failure of the
 *   underlying source or sink is passed through unchanged.
 * - Closing the encrypting stream writes the final segment and closes [encrypt]'s sink; closing
 *   twice is harmless.
 */
interface ChunkedAead {
    fun encrypt(sink: OutputStream, key: ByteArray, segmentSize: Int, associatedData: ByteArray): OutputStream

    fun decrypt(source: InputStream, key: ByteArray, segmentSize: Int, associatedData: ByteArray): InputStream

    /** Bytes the stream occupies for a plaintext of [plaintextBytes], the file header NOT included. */
    fun encryptedSize(plaintextBytes: Long, segmentSize: Int): Long
}
