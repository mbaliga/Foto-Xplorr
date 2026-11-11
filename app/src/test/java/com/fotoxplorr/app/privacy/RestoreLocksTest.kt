package com.fotoxplorr.app.privacy

import android.util.Base64
import com.fotoxplorr.app.favorites.FavoriteStore
import com.fotoxplorr.core.model.MediaId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Putting locks back from an encrypted backup ([PrivateFolderStore.restoreLocks]) and the three
 * stores that must stay in step when a second instance writes (the favourites and sensitive marks, below).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreLocksTest {
    private val context = RuntimeEnvironment.getApplication()
    private val key = "path:dcim/private"

    private fun b64(size: Int, seed: Int = 1) = Base64.encodeToString(ByteArray(size) { (it + seed).toByte() }, Base64.NO_WRAP)

    @Test
    fun `a lock from another phone opens with the password it had`() {
        val old = PrivateFolderStore(context)
        assertTrue(runBlocking { old.protect(key, "secret-pw".toCharArray()) }.isSuccess)
        val records = old.lockRecords()
        assertTrue(runBlocking { old.removeProtection(key, "secret-pw".toCharArray()) })
        assertFalse(key in old.observeLockedFolders().value)

        val fresh = PrivateFolderStore(context)
        val result = fresh.restoreLocks(records)

        assertEquals(1, result.added)
        assertEquals(0, result.kept + result.rejected)
        assertTrue(key in fresh.observeLockedFolders().value)
        assertFalse("restored locked, not unlocked", key in fresh.observeUnlockedFolders().value)
        assertFalse(runBlocking { fresh.unlock(key, "wrong-password".toCharArray()) })
        assertTrue(runBlocking { fresh.unlock(key, "secret-pw".toCharArray()) })
    }

    @Test
    fun `a lock the phone already has is kept and the person's password still works`() {
        val store = PrivateFolderStore(context)
        assertTrue(runBlocking { store.protect(key, "mine-mine-mine".toCharArray()) }.isSuccess)
        store.lock(key)

        val result = store.restoreLocks(listOf(FolderLockRecord(key, b64(16), b64(32))))

        assertEquals(0, result.added)
        assertEquals(1, result.kept)
        assertTrue(runBlocking { store.unlock(key, "mine-mine-mine".toCharArray()) })
    }

    @Test
    fun `the same folder twice in one backup is added once`() {
        val store = PrivateFolderStore(context)
        val record = FolderLockRecord(key, b64(16), b64(32))
        val result = store.restoreLocks(listOf(record, record))
        assertEquals(1, result.added)
        assertEquals(1, result.kept)
    }

    @Test
    fun `records that are not a lock are refused and nothing is stored for them`() {
        val store = PrivateFolderStore(context)
        val result = store.restoreLocks(
            listOf(
                FolderLockRecord("", b64(16), b64(32)),
                FolderLockRecord("   ", b64(16), b64(32)),
                FolderLockRecord("k".repeat(5000), b64(16), b64(32)),
                FolderLockRecord("path:a", b64(15), b64(32)),
                FolderLockRecord("path:b", b64(16), b64(31)),
                FolderLockRecord("path:c", "not base64 !!!", b64(32)),
                FolderLockRecord("path:d", "", ""),
            ),
        )
        assertEquals(0, result.added)
        assertEquals(7, result.rejected)
        assertTrue(store.observeLockedFolders().value.isEmpty())
        assertTrue(PrivateFolderStore(context).observeLockedFolders().value.isEmpty())
    }

    @Test
    fun `a restored lock is on disk when the call returns`() {
        PrivateFolderStore(context).restoreLocks(listOf(FolderLockRecord(key, b64(16), b64(32))))
        assertEquals(setOf(key), PrivateFolderStore(context).observeLockedFolders().value)
    }

    @Test
    fun `the instance the app is showing learns of a restored lock at once`() {
        val shown = PrivateFolderStore(context) // the activity's instance
        assertTrue(shown.observeLockedFolders().value.isEmpty())

        PrivateFolderStore(context).restoreLocks(listOf(FolderLockRecord(key, b64(16), b64(32)))) // the restore's own

        assertEquals(setOf(key), shown.observeLockedFolders().value)
    }

    @Test
    fun `a favourite written by one instance is seen by the others and survives their next change`() {
        val shown = FavoriteStore(context)
        val restoring = FavoriteStore(context)

        restoring.setFavorite(setOf(MediaId(1), MediaId(2)), true)
        assertEquals(setOf(MediaId(1), MediaId(2)), shown.observeNow())

        shown.toggle(MediaId(3))
        assertEquals(setOf(MediaId(1), MediaId(2), MediaId(3)), FavoriteStore(context).observeNow())
    }

    @Test
    fun `a sensitive mark written by one instance is seen by the others and survives their next change`() {
        val shown = SensitiveStore(context)
        val restoring = SensitiveStore(context)

        restoring.setSensitive(setOf(MediaId(5)), true)
        assertEquals(setOf(MediaId(5)), shown.observe().value)

        shown.setSensitive(MediaId(6), true)
        assertEquals(setOf(MediaId(5), MediaId(6)), SensitiveStore(context).observe().value)
    }

    private fun FavoriteStore.observeNow(): Set<MediaId> = runBlocking { observe().first() }
}
