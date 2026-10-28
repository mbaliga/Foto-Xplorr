package com.fotoxplorr.app.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.util.SecretBytes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException

/**
 * [ChunkedAead] on Tink's AES-GCM-HKDF streaming AEAD: AES-256-GCM, HKDF-SHA256 with a random
 * per-stream salt, a random 7-byte nonce prefix, a segment counter and a final-segment flag in the
 * nonce. Built from the key class directly, so no Tink registry or keyset parsing is involved (and
 * none of the reflection-based protobuf paths R8 would have to be told about).
 *
 * The stream starts with a 40-byte Tink header (length byte, 32-byte salt, 7-byte nonce prefix) and
 * every segment carries a 16-byte tag; the first segment is 40 bytes shorter in plaintext because
 * the header sits inside it.
 */
object TinkChunkedAead : ChunkedAead {
    private const val KEY_BYTES = 32
    private const val NONCE_PREFIX_BYTES = 7
    private const val TAG_BYTES = 16
    private const val STREAM_HEADER_BYTES = 1 + KEY_BYTES + NONCE_PREFIX_BYTES

    override fun encrypt(
        sink: OutputStream,
        key: ByteArray,
        segmentSize: Int,
        associatedData: ByteArray,
    ): OutputStream = try {
        streamingAead(key, segmentSize).newEncryptingStream(sink, associatedData)
    } catch (e: GeneralSecurityException) {
        throw IOException("Could not start the encrypted stream", e)
    }

    override fun decrypt(
        source: InputStream,
        key: ByteArray,
        segmentSize: Int,
        associatedData: ByteArray,
    ): InputStream = try {
        streamingAead(key, segmentSize).newDecryptingStream(source, associatedData)
    } catch (e: GeneralSecurityException) {
        throw IOException("Could not start the decrypting stream", e)
    }

    override fun encryptedSize(plaintextBytes: Long, segmentSize: Int): Long {
        require(plaintextBytes >= 0) { "A plaintext cannot have a negative length" }
        val firstCapacity = (segmentSize - STREAM_HEADER_BYTES - TAG_BYTES).toLong()
        val laterCapacity = (segmentSize - TAG_BYTES).toLong()
        val segments = if (plaintextBytes <= firstCapacity) {
            1L
        } else {
            1L + (plaintextBytes - firstCapacity + laterCapacity - 1) / laterCapacity
        }
        return STREAM_HEADER_BYTES + plaintextBytes + segments * TAG_BYTES
    }

    private fun streamingAead(key: ByteArray, segmentSize: Int): StreamingAead {
        require(key.size == KEY_BYTES) { "The master key must be $KEY_BYTES bytes" }
        val parameters = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(KEY_BYTES)
            .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(segmentSize)
            .build()
        val tinkKey = AesGcmHkdfStreamingKey.create(
            parameters,
            SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()),
        )
        return AesGcmHkdfStreaming.create(tinkKey)
    }
}
