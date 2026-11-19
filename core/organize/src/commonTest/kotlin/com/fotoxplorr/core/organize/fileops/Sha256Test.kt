package com.fotoxplorr.core.organize.fileops

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {
    private fun hex(s: String) = Sha256().also { it.update(s.encodeToByteArray()) }.digestHex()

    @Test
    fun emptyInput() = assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))

    @Test
    fun abcVector() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))

    @Test
    fun twoBlockVector() = assertEquals(
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
    )

    @Test
    fun millionAs() {
        val h = Sha256()
        val chunk = ByteArray(1000) { 'a'.code.toByte() }
        repeat(1000) { h.update(chunk) }
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", h.digestHex())
    }

    @Test
    fun chunkingDoesNotChangeTheHash() {
        val data = bytesOf(3, 1000)
        val whole = hashOf(data)
        for (step in listOf(1, 7, 63, 64, 65, 999)) {
            val h = Sha256()
            var i = 0
            while (i < data.size) {
                val n = minOf(step, data.size - i)
                h.update(data, i, n)
                i += n
            }
            assertEquals(whole, h.digestHex(), "step $step")
        }
    }
}
