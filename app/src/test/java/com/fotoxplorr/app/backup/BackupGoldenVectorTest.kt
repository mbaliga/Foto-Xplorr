package com.fotoxplorr.app.backup

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device self-check runs the cipher as well as the key derivation. These pin that it passes
 * here (BackupFormatTest pins that the copy carried in the app is the published vector), and that
 * each way a phone's crypto could be broken (the decrypt side only, or authentication switched off) is a failed check
 * with its own answer rather than a pass.
 */
class BackupGoldenVectorTest {
    @Test
    fun `the check passes on this machine`() {
        assertEquals(BackupGoldenVector.Result.PASSED, BackupGoldenVector.run())
    }

    @Test
    fun `keys that are not the vector's are a key derivation failure`() {
        val keys = BackupTestSupport.newKeys()
        assertEquals(BackupGoldenVector.Result.KEY_DERIVATION, BackupGoldenVector.checked(keys))
        keys.close()
    }

    @Test
    fun `a decrypt side that returns something else is a decrypt failure`() {
        val garbage = object : ChunkedAead by TinkChunkedAead {
            override fun decrypt(source: InputStream, key: ByteArray, segmentSize: Int, associatedData: ByteArray): InputStream =
                ByteArrayInputStream(ByteArray(200) { 7 })
        }
        assertEquals(BackupGoldenVector.Result.DECRYPT, BackupGoldenVector.run(garbage))
    }

    @Test
    fun `an encrypt side that cannot be opened again is a round trip failure`() {
        val plainSink = object : ChunkedAead by TinkChunkedAead {
            override fun encrypt(sink: OutputStream, key: ByteArray, segmentSize: Int, associatedData: ByteArray): OutputStream = sink
        }
        assertEquals(BackupGoldenVector.Result.ROUND_TRIP, BackupGoldenVector.run(plainSink))
    }

    @Test
    fun `a decrypt side that swallows a damaged segment is a round trip failure`() {
        // Opens the known file, opens what it sealed, and hides the failure when a byte was changed.
        val forgiving = object : ChunkedAead by TinkChunkedAead {
            override fun decrypt(source: InputStream, key: ByteArray, segmentSize: Int, associatedData: ByteArray): InputStream {
                val inner = TinkChunkedAead.decrypt(source, key, segmentSize, associatedData)
                return object : java.io.FilterInputStream(inner) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                        super.read(b, off, len)
                    } catch (e: java.io.IOException) {
                        -1
                    }

                    override fun read(): Int = try {
                        super.read()
                    } catch (e: java.io.IOException) {
                        -1
                    }
                }
            }
        }
        assertEquals(BackupGoldenVector.Result.ROUND_TRIP, BackupGoldenVector.run(forgiving))
    }
}
