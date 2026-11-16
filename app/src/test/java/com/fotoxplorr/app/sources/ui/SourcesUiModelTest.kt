package com.fotoxplorr.app.sources.ui

import com.fotoxplorr.app.sources.runtime.AddResult
import com.fotoxplorr.app.sources.runtime.IndexPhase
import com.fotoxplorr.app.sources.runtime.IndexProgress
import com.fotoxplorr.app.sources.runtime.SourceMeta
import com.fotoxplorr.app.sources.runtime.SourceStatus
import com.fotoxplorr.core.index.sources.SourceInfo
import com.fotoxplorr.core.index.sources.SourceKind
import com.fotoxplorr.core.index.sources.SourceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesUiModelTest {
    private fun status(
        state: SourceState = SourceState.ONLINE,
        files: Int = 12,
        bytes: Long = 34_500_000,
        sized: Int = 12,
        unreadable: Int = 0,
        progress: IndexProgress? = null,
        meta: SourceMeta = SourceMeta(lastIndexedMs = 5),
    ) = SourceStatus(SourceInfo(3, SourceKind.SAF_TREE, "content://a/tree/b", "primary", "Camera", state), meta, files, bytes, sized, unreadable, progress)

    private val fmt = { ms: Long -> "day $ms" }

    @Test
    fun `a settled source shows its counts and when it was last read`() {
        val c = SourcesUiModel.card(status(), fmt)
        assertEquals("12 files", c.filesLabel)
        assertEquals("34.5 MB", c.bytesLabel)
        assertEquals("Last read day 5.", c.lastRead)
        assertEquals("Online", c.stateLabel)
        assertNull(c.stateNote)
        assertFalse(c.busy)
        assertTrue(c.canRescan)
        assertFalse(c.sizesPending)
        assertTrue(c.problems.isEmpty())
    }

    @Test
    fun `a source never read says so`() {
        val c = SourcesUiModel.card(status(files = 0, bytes = 0, meta = SourceMeta()), fmt)
        assertEquals("Not read yet.", c.lastRead)
        assertEquals("0 files", c.filesLabel)
        assertEquals("0 B", c.bytesLabel)
    }

    @Test
    fun `each state has a word and a sentence that is not only a colour`() {
        val labels = SourceState.entries.associateWith { SourcesUiModel.card(status(it), fmt) }
        assertEquals(setOf("Online", "Offline", "Revoked", "Error"), labels.values.map { it.stateLabel }.toSet())
        assertNull(labels.getValue(SourceState.ONLINE).stateNote)
        SourceState.entries.filter { it != SourceState.ONLINE }.forEach { assertTrue(labels.getValue(it).stateNote!!.endsWith(".")) }
    }

    @Test
    fun `while reading it shows progress and no rescan`() {
        val c = SourcesUiModel.card(status(progress = IndexProgress(IndexPhase.WALKING, 1234, 0.4f)), fmt)
        assertTrue(c.busy)
        assertFalse(c.canRescan)
        assertEquals("Reading folders. 1234 files found so far.", c.progressText)
        assertEquals(0.4f, c.progressFraction!!, 0f)
        assertEquals("Finding sizes.", SourcesUiModel.card(status(progress = IndexProgress(IndexPhase.SIZING, 9, 0.9f)), fmt).progressText)
        assertEquals("Reading folders.", SourcesUiModel.card(status(progress = IndexProgress(IndexPhase.WALKING, 0, null)), fmt).progressText)
    }

    @Test
    fun `unreadable files and the last error are listed once and a state sentence is not repeated`() {
        val c = SourcesUiModel.card(status(unreadable = 2, sized = 5, meta = SourceMeta(lastIndexedMs = 5, lastError = "The app that manages this folder did not answer, so nothing was changed."), state = SourceState.ERROR), fmt)
        assertEquals(listOf("2 files could not be read."), c.problems)
        val other = SourcesUiModel.card(status(meta = SourceMeta(lastError = "Something odd.")), fmt)
        assertEquals(listOf("Something odd."), other.problems)
        assertEquals("1 file could not be read.", SourcesCopy.unreadable(1))
    }

    @Test
    fun `tiles that may look square are called out only when sizes are missing and nothing is running`() {
        assertTrue(SourcesUiModel.card(status(sized = 3, unreadable = 0), fmt).sizesPending)
        assertFalse(SourcesUiModel.card(status(sized = 10, unreadable = 2), fmt).sizesPending)
        assertFalse(SourcesUiModel.card(status(sized = 3, progress = IndexProgress(IndexPhase.SIZING, 12, 0.2f)), fmt).sizesPending)
    }

    @Test
    fun `byte sizes read like a drive label`() {
        assertEquals("999 B", SourcesUiModel.formatBytes(999))
        assertEquals("1 KB", SourcesUiModel.formatBytes(1000))
        assertEquals("1.5 MB", SourcesUiModel.formatBytes(1_500_000))
        assertEquals("120 GB", SourcesUiModel.formatBytes(120_000_000_000))
        assertEquals("2.5 TB", SourcesUiModel.formatBytes(2_500_000_000_000))
    }

    @Test
    fun `every sentence is one short sentence`() {
        val strings = listOf(
            SourcesCopy.ROW_CAPTION, SourcesCopy.INTRO, SourcesCopy.EMPTY_TITLE, SourcesCopy.EMPTY_BODY, SourcesCopy.INCLUDE_HELP,
            SourcesCopy.ON_CONNECT_HELP, SourcesCopy.NEVER_READ, SourcesCopy.READING, SourcesCopy.SIZING, SourcesCopy.SIZES_PENDING,
            SourcesCopy.REMOVE_BODY, SourcesCopy.ADDED, SourcesCopy.ADD_FAILED,
            SourcesCopy.found(5), SourcesCopy.unreadable(3), SourcesCopy.lastRead("today"),
            SourcesCopy.forResult(AddResult.Duplicate("DCIM"))!!, SourcesCopy.forResult(AddResult.InsideExisting("DCIM"))!!,
            SourcesCopy.forResult(AddResult.ContainsExisting("DCIM"))!!,
        )
        strings.forEach {
            assertTrue("ends with a full stop: $it", it.endsWith("."))
            assertEquals("one sentence: $it", 1, it.count { c -> c == '.' })
            assertTrue("short: $it", it.length <= 100)
        }
        // The labels are words, not sentences.
        listOf(SourcesCopy.ADD, SourcesCopy.RESCAN, SourcesCopy.STOP, SourcesCopy.REMOVE, SourcesCopy.INCLUDE, SourcesCopy.ON_CONNECT).forEach {
            assertFalse(it, it.endsWith(".")); assertTrue(it.length <= 40)
        }
    }

    @Test
    fun `refusals read as the reason and the grant sentence passes through`() {
        assertEquals("Android would not keep access to that folder.", SourcesCopy.forResult(AddResult.GrantRefused("Android would not keep access to that folder.")))
        assertEquals("DCIM is already added.", SourcesCopy.forResult(AddResult.Duplicate("DCIM")))
        assertEquals("That folder is already part of DCIM.", SourcesCopy.forResult(AddResult.InsideExisting("DCIM")))
        assertEquals("That folder holds Camera, so remove that one first.", SourcesCopy.forResult(AddResult.ContainsExisting("Camera")))
    }
}
