package com.fotoxplorr.core.formats.composite

import com.fotoxplorr.core.formats.composite.CompositeFixtures.appleMovie
import com.fotoxplorr.core.formats.composite.CompositeFixtures.appleStill
import com.fotoxplorr.core.formats.composite.CompositeFixtures.ascii
import com.fotoxplorr.core.formats.composite.CompositeFixtures.be32
import com.fotoxplorr.core.formats.composite.CompositeFixtures.box
import com.fotoxplorr.core.formats.composite.CompositeFixtures.mp4
import com.fotoxplorr.core.formats.composite.CompositeFixtures.source
import com.fotoxplorr.core.formats.raw.jpeg
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleIdentifierReaderTest {
    private val id = "6F5A1A02-6B2C-4B3E-9C2D-5E7F00112233"
    private val burst = "B0A1C2D3-E4F5-4677-8899-AABBCCDDEEFF"

    @Test
    fun `the maker note gives the content identifier and the burst id`() {
        val found = AppleIdentifierReader.fromStill(source(appleStill(id, burst)))!!
        assertEquals(id, found.contentIdentifier)
        assertEquals(burst, found.burstUuid)
    }

    @Test
    fun `a little endian maker note reads the same`() {
        val found = AppleIdentifierReader.fromStill(source(appleStill(id, null, littleEndian = true)))!!
        assertEquals(id, found.contentIdentifier)
        assertNull(found.burstUuid)
    }

    @Test
    fun `a still with no Apple maker note has no identifiers`() {
        assertNull(AppleIdentifierReader.fromStill(source(jpeg(64, 48, size = 500))))
    }

    @Test
    fun `a QuickTime movie gives its content identifier from the keys and items`() {
        assertEquals(id, AppleIdentifierReader.fromMovie(source(appleMovie(id)))!!.contentIdentifier)
        assertEquals(id, AppleIdentifierReader.fromMovie(source(appleMovie(id, otherKeysFirst = false)))!!.contentIdentifier)
    }

    @Test
    fun `an ISO style meta box with version and flags is read too`() {
        assertEquals(id, AppleIdentifierReader.fromMovie(source(appleMovie(id, fullBoxMeta = true)))!!.contentIdentifier)
    }

    @Test
    fun `a movie with no moov or no key has none`() {
        assertNull(AppleIdentifierReader.fromMovie(source(mp4(100))))
        assertNull(AppleIdentifierReader.fromMovie(source(ByteArray(3))))
    }

    @Test
    fun `mangled movies and stills never throw`() {
        val random = Random(7)
        val movie = appleMovie(id)
        val still = appleStill(id, burst)
        repeat(300) {
            val m = movie.copyOf()
            val s = still.copyOf()
            repeat(6) {
                m[random.nextInt(m.size)] = random.nextInt(256).toByte()
                s[random.nextInt(s.size)] = random.nextInt(256).toByte()
            }
            AppleIdentifierReader.fromMovie(source(m))
            AppleIdentifierReader.fromStill(source(s))
        }
    }

    @Test
    fun `entry sizes just under two gigabytes give null and never throw`() {
        val huge = 0x7FFFFFF9L
        val hdlr = box("hdlr", be32(0), be32(0), ascii("mdta"), ByteArray(12))
        fun movie(keys: ByteArray, ilst: ByteArray) =
            box("ftyp", ascii("qt  "), be32(0), ascii("qt  ")) + box("moov", box("meta", hdlr, keys, ilst))
        val goodKeys = box("keys", be32(0), be32(1), be32(8L + AppleIdentifierReader.CONTENT_KEY.length), ascii("mdta"), ascii(AppleIdentifierReader.CONTENT_KEY))
        val goodData = box("data", be32(1), be32(0), ascii(id))
        // A key entry, an item and an item's data box each claim the hostile size in turn.
        val hostileKeys = box("keys", be32(0), be32(1), be32(huge), ascii("mdta"), ascii(AppleIdentifierReader.CONTENT_KEY))
        val hostileItem = box("ilst", be32(huge), be32(1), goodData)
        val hostileData = box("ilst", be32(8L + 16 + id.length), be32(1), be32(huge), ascii("data"), be32(1), be32(0), ascii(id))
        assertEquals(id, AppleIdentifierReader.fromMovie(source(movie(goodKeys, box("ilst", be32(8L + goodData.size), be32(1), goodData))))!!.contentIdentifier)
        assertNull(AppleIdentifierReader.fromMovie(source(movie(hostileKeys, box("ilst", be32(8L + goodData.size), be32(1), goodData)))))
        assertNull(AppleIdentifierReader.fromMovie(source(movie(goodKeys, hostileItem))))
        assertNull(AppleIdentifierReader.fromMovie(source(movie(goodKeys, hostileData))))
    }
}
