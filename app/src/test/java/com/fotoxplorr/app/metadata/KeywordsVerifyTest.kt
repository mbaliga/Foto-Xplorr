package com.fotoxplorr.app.metadata

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [keywordsVerify], the read-back check [MetadataWriter] runs on a staged file. It has to agree
 * with [applyToXmp]'s "removal wins" rule or a correctly written edit is reported as a failure.
 */
class KeywordsVerifyTest {

    @Test
    fun `an added keyword must be present`() {
        assertTrue(keywordsVerify(current = listOf("a", "b"), add = listOf("b"), remove = emptyList()))
        assertFalse(keywordsVerify(current = listOf("a"), add = listOf("b"), remove = emptyList()))
    }

    @Test
    fun `a removed keyword must be absent`() {
        assertTrue(keywordsVerify(current = listOf("a"), add = emptyList(), remove = listOf("b")))
        assertFalse(keywordsVerify(current = listOf("a", "b"), add = emptyList(), remove = listOf("b")))
    }

    @Test
    fun `a keyword in both lists must be absent, because removal wins`() {
        assertTrue(keywordsVerify(current = listOf("a"), add = listOf("b"), remove = listOf("b")))
        assertFalse(keywordsVerify(current = listOf("a", "b"), add = listOf("b"), remove = listOf("b")))
    }

    @Test
    fun `an unrelated keyword already on the file does not fail verification`() {
        assertTrue(keywordsVerify(current = listOf("keep", "new"), add = listOf("new"), remove = listOf("gone")))
    }

    @Test
    fun `an edit that names no keywords verifies trivially`() {
        assertTrue(keywordsVerify(current = listOf("a"), add = emptyList(), remove = emptyList()))
    }
}
