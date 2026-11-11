package com.fotoxplorr.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun c(id: Long, name: String = "IMG_$id.jpg", size: Long = 100, taken: Long = 1_700_000_000_000L + id * 86_400_000L, mtime: Long = id, folder: String = "DCIM/Camera/") =
        SyncCandidate(id, name, folder, "image/jpeg", size, taken, mtime, folder.trimEnd('/').lowercase(), false)

    private fun done(c: SyncCandidate, path: String, sha: String? = null) = SyncDone(c.key, c.sizeBytes, c.dateModifiedSeconds, path, c.sizeBytes, sha, 1)

    @Test
    fun `newest first and everything planned when the manifest is empty`() {
        val plan = SyncPlanner.plan(listOf(c(1), c(3), c(2)), SyncManifest())
        assertEquals(listOf(3L, 2L, 1L), plan.uploads.map { it.candidate.mediaId })
        assertEquals(0, plan.upToDate)
        assertTrue(plan.uploads.all { RemoteLayout.isSafePath(it.remotePath) && it.remotePath.startsWith("Fotoz/") })
    }

    @Test
    fun `what the manifest holds unchanged is skipped`() {
        val a = c(1)
        val m = SyncManifest().apply { recordDone(done(a, "Fotoz/x/a.jpg")) }
        val plan = SyncPlanner.plan(listOf(a, c(2)), m)
        assertEquals(listOf(2L), plan.uploads.map { it.candidate.mediaId })
        assertEquals(1, plan.upToDate)
    }

    @Test
    fun `a changed file is sent beside the old copy, never over it`() {
        val a = c(1)
        val first = RemoteLayout.pathFor(a)
        val m = SyncManifest().apply { recordDone(done(a, first)) }
        val changed = a.copy(sizeBytes = 999, dateModifiedSeconds = 50)
        val plan = SyncPlanner.plan(listOf(changed), m)
        assertEquals(1, plan.uploads.size)
        assertTrue(plan.uploads[0].remotePath != first)
        assertTrue(plan.uploads[0].remotePath.endsWith(" (2).jpg"))
        assertFalse(plan.uploads[0].overwriteOwnPartial)
    }

    @Test
    fun `two photos with one name on one day do not share a path`() {
        val a = c(1, "same.jpg", taken = 1_700_000_000_000L)
        val b = c(2, "same.jpg", taken = 1_700_000_000_000L, folder = "Download/")
        val paths = SyncPlanner.plan(listOf(a, b), SyncManifest()).uploads.map { it.remotePath.lowercase() }
        assertEquals(2, paths.toSet().size)
    }

    @Test
    fun `a half sent file is planned to the same path and flagged as our own`() {
        val a = c(1)
        val path = RemoteLayout.pathFor(a)
        val m = SyncManifest().apply { recordBegun(SyncBegun(a.key, path, a.sizeBytes, a.dateModifiedSeconds)) }
        val item = SyncPlanner.plan(listOf(a), m).uploads.single()
        assertEquals(path, item.remotePath)
        assertTrue(item.overwriteOwnPartial)
    }

    @Test
    fun `a begun file that changed since is not resumed over`() {
        val a = c(1)
        val path = RemoteLayout.pathFor(a)
        val m = SyncManifest().apply { recordBegun(SyncBegun(a.key, path, a.sizeBytes + 1, a.dateModifiedSeconds)) }
        val item = SyncPlanner.plan(listOf(a), m).uploads.single()
        assertFalse(item.overwriteOwnPartial)
        assertTrue(item.remotePath != path)
    }

    @Test
    fun `the byte cap takes files in order and defers the rest`() {
        val files = listOf(c(1, size = 400), c(2, size = 400), c(3, size = 400))
        val plan = SyncPlanner.plan(files, SyncManifest(), byteCap = 900)
        assertEquals(listOf(3L, 2L), plan.uploads.map { it.candidate.mediaId })
        assertEquals(1, plan.deferredByCap)
        assertEquals(800, plan.totalBytes)
    }

    @Test
    fun `a file bigger than the whole cap is reported, not deferred`() {
        val plan = SyncPlanner.plan(listOf(c(1, size = 5_000), c(2, size = 100)), SyncManifest(), byteCap = 1_000)
        assertEquals(listOf(2L), plan.uploads.map { it.candidate.mediaId })
        assertEquals(1, plan.tooBigForCap)
        assertEquals(0, plan.deferredByCap)
    }

    @Test
    fun `no cap means no limit`() {
        val plan = SyncPlanner.plan((1L..50L).map { c(it, size = 1_000_000_000) }, SyncManifest())
        assertEquals(50, plan.uploads.size)
    }

    @Test
    fun `a renamed twin with a known hash is flagged for hashing`() {
        val old = c(1, "old.jpg")
        val m = SyncManifest().apply { recordDone(done(old, "Fotoz/x/old.jpg", sha = "b".repeat(64))) }
        val renamed = old.copy(displayName = "new.jpg")
        val item = SyncPlanner.plan(listOf(renamed), m).uploads.single()
        assertEquals("old.jpg", item.lookalike!!.key.substringAfterLast('/'))
        // Without a stored hash there is nothing to compare, so no lookalike is offered.
        val noHash = SyncManifest().apply { recordDone(done(old, "Fotoz/x/old.jpg", sha = null)) }
        assertNull(SyncPlanner.plan(listOf(renamed), noHash).uploads.single().lookalike)
    }

    @Test
    fun `planning is deterministic`() {
        val files = (1L..20L).map { c(it, taken = 1_700_000_000_000L) }
        assertEquals(SyncPlanner.plan(files, SyncManifest()), SyncPlanner.plan(files.shuffled(java.util.Random(3)), SyncManifest()))
    }

    @Test
    fun `an encrypted destination names files with the suffix`() {
        val plan = SyncPlanner.plan(listOf(c(1)), SyncManifest(), remoteSuffix = SyncEncryption.SUFFIX)
        assertTrue(plan.uploads.single().remotePath.endsWith(".jpg.fzs"))
    }
}
