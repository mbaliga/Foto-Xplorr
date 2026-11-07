package com.fotoxplorr.app.backup

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A children query is not an answer until the provider says it is one. A cloud-backed tree provider
 * that is still syncing returns an empty or partial cursor with the loading flag set; reading that
 * as the folder's content turns an existing backup into "an empty folder, choose a passphrase".
 */
class SafListingTest {
    private fun row(name: String, id: String = "id-$name") = SafRow(id, name, 10, isDirectory = false)

    private fun page(vararg names: String, loading: Boolean = false, error: String? = null) =
        SafPage(names.map { row(it) }, loading, error)

    /** Answers the pages in order, then repeats the last; remembers how often it was asked and the pauses taken. */
    private class Provider(private val answers: List<SafPage>) {
        var asked = 0
        val pauses = ArrayList<Long>()

        fun query(): SafPage = answers[minOf(asked++, answers.size - 1)]
    }

    @Test
    fun `a listing that is complete is returned at once, without waiting`() {
        val provider = Provider(listOf(page("manifest-0001.fzbm")))
        val result = SafListing.settle(pause = { provider.pauses += it }, query = provider::query)
        assertEquals(listOf("manifest-0001.fzbm"), result.rows.map { it.name })
        assertEquals(1, provider.asked)
        assertTrue(provider.pauses.isEmpty())
    }

    @Test
    fun `an empty answer that says it is still loading is asked again until the real files show`() {
        val provider = Provider(listOf(page(loading = true), page("pack-0001.fzbp", loading = true), page("pack-0001.fzbp", "manifest-0001.fzbm")))
        val result = SafListing.settle(pause = { provider.pauses += it }, query = provider::query)
        assertEquals(listOf("pack-0001.fzbp", "manifest-0001.fzbm"), result.rows.map { it.name })
        assertEquals(3, provider.asked)
        assertEquals(SafListing.DEFAULT_DELAYS_MILLIS.take(2), provider.pauses)
    }

    @Test
    fun `a folder that never finishes loading is an error, not an empty folder`() {
        val provider = Provider(listOf(page(loading = true)))
        try {
            SafListing.settle(pause = { provider.pauses += it }, query = provider::query)
            fail("a listing that was still loading must not be trusted")
        } catch (e: FolderNotReadyException) {
            assertTrue(e.message, e.message!!.contains("still loading"))
        }
        assertEquals(SafListing.DEFAULT_DELAYS_MILLIS.size + 1, provider.asked)
        assertEquals(SafListing.DEFAULT_DELAYS_MILLIS, provider.pauses)
    }

    @Test
    fun `an error the provider reports is thrown, even with rows beside it`() {
        val provider = Provider(listOf(page("pack-0001.fzbp", error = "Sign in again")))
        try {
            SafListing.settle(pause = { provider.pauses += it }, query = provider::query)
            fail("a provider error must not be read as a listing")
        } catch (e: FolderNotReadyException) {
            assertTrue(e.message, e.message!!.contains("Sign in again"))
        }
        assertEquals(1, provider.asked)
    }

    @Test
    fun `a blank error string is not an error`() {
        val provider = Provider(listOf(page("a", error = "  ")))
        assertEquals(1, SafListing.settle(pause = {}, query = provider::query).rows.size)
    }

    @Test
    fun `a failure of the query itself passes through`() {
        try {
            SafListing.settle(pause = {}) { throw IOException("The folder could not be read") }
            fail()
        } catch (e: IOException) {
            assertEquals("The folder could not be read", e.message)
        }
    }

    @Test
    fun `a created document is writable only when it is new and empty`() {
        val listed = setOf("a", "b")
        assertTrue(SafDocumentIds.isFreshDocument("c", listed, 0L))
        assertTrue(SafDocumentIds.isFreshDocument("c", listed, null))
        // The provider answered a create with a file the folder already had: writing would truncate it.
        assertFalse(SafDocumentIds.isFreshDocument("a", listed, 0L))
        // ...or with one its listing had not shown yet, but which has content.
        assertFalse(SafDocumentIds.isFreshDocument("c", listed, 4_000_000L))
    }
}
