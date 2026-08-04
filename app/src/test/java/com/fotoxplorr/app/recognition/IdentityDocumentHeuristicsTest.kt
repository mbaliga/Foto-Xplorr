package com.fotoxplorr.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityDocumentHeuristicsTest {

    @Test
    fun `empty text is not a document`() {
        assertEquals(IdentityVerdict.NONE, IdentityDocumentHeuristics.classify(""))
        assertEquals(IdentityVerdict.NONE, IdentityDocumentHeuristics.classify("   \n  "))
    }

    @Test
    fun `passport page with fields is a document`() {
        val text = """
            PASSPORT
            Surname  DOE
            Given names  JANE
            Nationality  BRITISH CITIZEN
            Date of birth  01 JAN 1990
            Date of expiry  01 JAN 2032
        """.trimIndent()
        assertEquals(IdentityVerdict.DOCUMENT, IdentityDocumentHeuristics.classify(text))
    }

    @Test
    fun `driving licence is a document`() {
        val text = """
            DRIVING LICENCE
            1. SMITH
            2. JOHN
            3. Date of birth 12.04.1988
            4b. Date of expiry 12.04.2030
            5. Licence no SMITH901124J99AB
        """.trimIndent()
        assertEquals(IdentityVerdict.DOCUMENT, IdentityDocumentHeuristics.classify(text))
    }

    @Test
    fun `bare mention of a document type is not enough`() {
        val text = "Remember to renew your passport before the holiday"
        assertEquals(IdentityVerdict.NONE, IdentityDocumentHeuristics.classify(text))
    }

    @Test
    fun `ordinary screenshot text is not a document`() {
        val text = """
            Good morning! The meeting has moved to 3pm.
            I'll send the deck over shortly, and the class notes too.
        """.trimIndent()
        assertEquals(IdentityVerdict.NONE, IdentityDocumentHeuristics.classify(text))
    }

    @Test
    fun `machine readable zone alone is conclusive`() {
        val mrz = """
            P<GBRDOE<<JANE<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
            1234567897GBR9001011F3201017<<<<<<<<<<<<<<06
        """.trimIndent()
        assertTrue(IdentityDocumentHeuristics.hasMachineReadableZone(mrz))
        assertEquals(IdentityVerdict.DOCUMENT, IdentityDocumentHeuristics.classify(mrz))
    }

    @Test
    fun `a single chevron line is not a machine readable zone`() {
        val text = "P<GBRDOE<<JANE<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        assertFalse(IdentityDocumentHeuristics.hasMachineReadableZone(text))
    }

    @Test
    fun `word boundaries are respected`() {
        // "sussex" contains "sex" and "dobson" contains "dob"; neither should score.
        val text = "Sussex office, contact Dobson about the class trip"
        assertEquals(0, IdentityDocumentHeuristics.score(text))
    }

    @Test
    fun `several field cues without a type phrase still score`() {
        val text = "Surname\nGiven names\nDate of birth\nDate of issue\nIssuing authority"
        assertTrue(IdentityDocumentHeuristics.score(text) >= 4)
    }

    @Test
    fun `score never goes negative`() {
        assertTrue(IdentityDocumentHeuristics.score("passport") >= 0)
    }

    @Test
    fun `isIdentity reflects the verdict`() {
        assertFalse(IdentityVerdict.NONE.isIdentity)
        assertTrue(IdentityVerdict.DOCUMENT.isIdentity)
    }
}
